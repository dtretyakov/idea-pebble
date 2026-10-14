package com.github.dtretyakov.pebble.agent.mcp

import com.github.dtretyakov.pebble.agent.AgentProblem
import com.github.dtretyakov.pebble.agent.ApiLookup
import com.github.dtretyakov.pebble.agent.BreakpointOutcome
import com.github.dtretyakov.pebble.agent.BuildOutcome
import com.github.dtretyakov.pebble.agent.DebugState
import com.github.dtretyakov.pebble.agent.DebugValue
import com.github.dtretyakov.pebble.agent.EmulatorOutcome
import com.github.dtretyakov.pebble.agent.EmulatorReset
import com.github.dtretyakov.pebble.agent.SdkState
import com.github.dtretyakov.pebble.agent.LogPage
import com.github.dtretyakov.pebble.agent.PebbleAgent
import com.github.dtretyakov.pebble.agent.PebbleDebugger
import com.github.dtretyakov.pebble.agent.ProjectSummary
import com.github.dtretyakov.pebble.agent.RunOutcome
import com.github.dtretyakov.pebble.agent.Screenshot
import com.github.dtretyakov.pebble.agent.UiTestOutcome
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

/**
 * Pebble in the IDE's MCP server: what a coding agent needs to build, run, see and drive a
 * Pebble app, and to look the API up instead of remembering it. Each tool is a thin call into
 * [PebbleAgent]; this file exists only where the IDE has its MCP server.
 *
 * The names keep to the server's own `snake_case`, and each description says when to call the
 * tool — it is the only documentation an agent reads before calling.
 */
@Suppress("FunctionName")
// No bridges to the interface's default methods, some of which 2026.1 does not have.
@JvmDefaultWithoutCompatibility
class PebbleToolset : McpToolset {

    @McpTool(name = "pebble_project")
    @McpDescription(
        """
        |Describes the Pebble app open in the IDE: its type (native = C, moddable = Alloy JavaScript),
        |target platforms with their screens (size, colour, round, touch), the active platform, the SDK,
        |and which emulators are running. Call it first.
        """,
    )
    suspend fun pebble_project(): ProjectSummary = call { it.describe() }

    @McpTool(name = "pebble_build")
    @McpDescription(
        """
        |Builds the app with `pebble build` for every target platform, as the IDE's Build does (the Build
        |window shows it too). Returns errors and warnings with file and line, each listed once with the
        |platforms it occurred on, and each platform's RAM, free heap and resources against its limits
        |(tight = little heap left, or resources the app store would refuse).
        """,
    )
    suspend fun pebble_build(
        @McpDescription("A debug build, for the debugger; normally false") debug: Boolean = false,
    ): BuildOutcome = call { it.build(debug) }

    @McpTool(name = "pebble_run")
    @McpDescription(
        """
        |Builds, then installs and starts the app on the emulator of one platform with the IDE's Pebble
        |run configuration, starting the emulator if it is not running (allow a minute when it is cold).
        |Returns once the app is running; its logs then keep coming — read them with pebble_logs from
        |`logsAfter`. A failed build is returned instead. Look with pebble_screenshot, act with pebble_emulator.
        """,
    )
    suspend fun pebble_run(
        @McpDescription("The platform: aplite, basalt, chalk, diorite, emery, flint or gabbro; the active platform when left out") platform: String? = null,
    ): RunOutcome = call { it.run(platform) }

    @McpTool(name = "pebble_logs")
    @McpDescription(
        """
        |Reads the output of Pebble runs — the app's APP_LOG and console.log lines, crash reports, pebble-tool's
        |messages and the IDE's notes (memory an app did not free) — after a cursor. Pass the `next` it returns
        |as `after` next time to get only what is new. Each app line has its level.
        """,
    )
    suspend fun pebble_logs(
        @McpDescription("Return lines after this index; -1 for everything still kept") after: Long = -1,
        @McpDescription("At most this many lines") limit: Int = 200,
        @McpDescription("Only app lines at this level or above: verbose, debug, info, warning, error") level: String? = null,
        @McpDescription("Only lines containing this text") contains: String? = null,
    ): LogPage = call { it.logs(after, limit, level, contains) }

    @McpTool(name = "pebble_screenshot")
    @McpDescription(
        """
        |Saves what an emulator's screen shows now as a PNG in the project's `screenshots` directory. Open the
        |returned `view` — the same screen enlarged, pixel for pixel — as an image to see it: check that text is
        |legible and whole, nothing is cut off by a round screen's edge, and it reads in black and white where the
        |platform has no colour. With all = true, starts each target platform's emulator in turn
        |and screenshots it (slow: minutes), to compare layouts across round, rectangular, colour and
        |black-and-white screens.
        """,
    )
    suspend fun pebble_screenshot(
        @McpDescription("The platform whose emulator to screenshot; the running one when left out") platform: String? = null,
        @McpDescription("Every target platform, one after another") all: Boolean = false,
    ): Screenshots = call { Screenshots(it.screenshot(platform, all)) }

    @McpTool(name = "pebble_emulator")
    @McpDescription(
        """
        |Acts on a running emulator, one step per line, in the language of the plugin's UI test scenarios:
        |`press select` (back, up, select, down; optionally milliseconds held: `press up 800`), `tap 100 120`
        |(screen pixels; emery and gabbro only), `wait 500`, `time 10:10`, `battery 20` or `battery 20 charging`,
        |`bluetooth off`, `quick-view on`, `motion tilt-left`, `shake`, `install`. Returns what each step did and,
        |unless told not to, a screenshot after the last: open its `view` image to see what the steps led to.
        """,
    )
    suspend fun pebble_emulator(
        @McpDescription("The steps, one per line") steps: String,
        @McpDescription("The platform whose emulator to act on; the running one when left out") platform: String? = null,
        @McpDescription("Take a screenshot after the steps") screenshot: Boolean = true,
    ): EmulatorOutcome = call { it.emulator(platform, steps, screenshot) }

    @McpTool(name = "pebble_api")
    @McpDescription(
        """
        |Looks a name up in the SDK the app builds against — a C function, type, enum value or macro from each
        |platform's pebble.h with its documentation, or an Alloy class or module from the SDK's typings. Says on
        |which platforms it is missing or only a stub that does nothing, and suggests the nearest real names when
        |there is no such name. Use it before calling an API you are not sure of: the SDK is 4.x, not 3.x.
        """,
    )
    suspend fun pebble_api(
        @McpDescription("The name to look up, e.g. text_layer_set_text, GTextAlignment, PBL_IF_ROUND_ELSE or Poco") symbol: String,
        @McpDescription("Only this platform; every target platform when left out") platform: String? = null,
    ): ApiLookup = call { it.api(symbol, platform) }

    @McpTool(name = "pebble_ui_tests")
    @McpDescription(
        """
        |Runs UI test scenarios (.pbtest files: steps such as `install`, `press select`, `tap 100 120`, and
        |`expect <screen>` lines) on each of their platforms' emulators, in the IDE's test runner. Each expected
        |screen is compared with its reference screenshot: it passes, fails (with the reference, the actual
        |screenshot and a diff image), or is recorded when it had no reference yet. Each failed or recorded screen has
        |a `view` image to open: the reference, the screen now and their difference side by side, or the recorded
        |screen enlarged — look at it before accepting or committing anything.
        |Slow: minutes per platform.
        """,
    )
    suspend fun pebble_ui_tests(
        @McpDescription("A .pbtest file or a directory of them, relative to the project; tests/ui when left out") path: String? = null,
    ): UiTestOutcome = call { it.uiTests(path) }

    @McpTool(name = "pebble_ui_test_accept")
    @McpDescription(
        """
        |Makes the last run's screenshot of a screen the scenario's reference, when the app changed on purpose and
        |the new screen is right. Read the actual screenshot first. Returns the reference's path.
        """,
    )
    suspend fun pebble_ui_test_accept(
        @McpDescription("The .pbtest file, relative to the project") scenario: String,
        @McpDescription("The platform the screenshot was taken on") platform: String,
        @McpDescription("The screen's name, as in its `expect` line") screen: String,
    ): AcceptedScreenshot = call { AcceptedScreenshot(it.acceptScreenshot(scenario, platform, screen)) }

    @McpTool(name = "pebble_emulator_reset")
    @McpDescription(
        """
        |Stops every emulator (`kill`) — for one that hangs, or that pebble_run cannot install on — or stops them
        |and forgets what the current SDK's emulators had installed and set (`wipe`), for one that fails to boot or
        |keeps failing to install. Runs and debug sessions on them end. The next pebble_run starts a fresh emulator.
        """,
    )
    suspend fun pebble_emulator_reset(
        @McpDescription("kill, or wipe") action: String = "kill",
    ): EmulatorReset = call { it.resetEmulators(action) }

    @McpTool(name = "pebble_sdk")
    @McpDescription(
        """
        |The Pebble SDKs: `list` the installed ones, the current one and those pebble-tool can install; `install` a
        |version (`latest` when none is given; minutes); `activate` an installed one, which the app then builds with.
        """,
    )
    suspend fun pebble_sdk(
        @McpDescription("list, install or activate") action: String = "list",
        @McpDescription("The SDK version, e.g. 4.33.1") version: String? = null,
    ): SdkState = call { it.sdk(action, version) }

    @McpTool(name = "pebble_debug_start")
    @McpDescription(
        """
        |Starts the IDE's debugger on the app in one platform's emulator, as Debug does: a debug build, installed and
        |run with gdb attached (C) or the app's JavaScript engine connected (Alloy). Returns once attached, with the
        |app running. Set breakpoints with pebble_debug_breakpoint — before or after — then make the app reach them
        |(pebble_emulator) and pebble_debug_wait.
        """,
    )
    suspend fun pebble_debug_start(
        @McpDescription("The platform; the active platform when left out. C debugs on any, Alloy on emery and gabbro") platform: String? = null,
    ): DebugState = debug { it.start(platform) }

    @McpTool(name = "pebble_debug_breakpoint")
    @McpDescription(
        """
        |Sets a line breakpoint in the app's sources, optionally with a condition (C or JavaScript, as the source is) or
        |an expression to log when it is hit; or removes the breakpoints on that line. Says whether the debugger
        |could set it (verified), could not and why (invalid), or will when it attaches (pending).
        """,
    )
    suspend fun pebble_debug_breakpoint(
        @McpDescription("The source file, relative to the project") file: String,
        @McpDescription("The line, counting from 1") line: Int,
        @McpDescription("Stop only when this is true") condition: String? = null,
        @McpDescription("Log this expression's value when the line is hit") log: String? = null,
        @McpDescription("Remove the breakpoints on this line instead") remove: Boolean = false,
    ): BreakpointOutcome = debug { it.breakpoint(file, line, condition, log, remove) }

    @McpTool(name = "pebble_debug_wait")
    @McpDescription(
        """
        |Waits until the app stops — at a breakpoint, the end of a step, or a crash — and returns where: the file
        |and line, the stack, and the frame's variables with structs and objects one level deep. Returns at once if it
        |is stopped already, and with state `running` if it has not stopped when the time is up.
        """,
    )
    suspend fun pebble_debug_wait(
        @McpDescription("How long to wait, in seconds") timeout: Int = 30,
    ): DebugState = debug { it.await(timeout.toLong()) }

    @McpTool(name = "pebble_debug_control")
    @McpDescription(
        """
        |Steps or runs the stopped app: `over` (the next line), `into` (into the call on this line), `out` (to the
        |caller), `resume` (on to the next breakpoint), or `pause` a running app. Steps return the next stop,
        |as pebble_debug_wait does.
        """,
    )
    suspend fun pebble_debug_control(
        @McpDescription("over, into, out, resume or pause") action: String,
        @McpDescription("Seconds to wait for the next stop") timeout: Int = 30,
    ): DebugState = debug { it.control(action, wait = true, timeoutSeconds = timeout.toLong()) }

    @McpTool(name = "pebble_debug_evaluate")
    @McpDescription(
        """
        |Evaluates an expression in the stopped app — C in a C app, JavaScript in an Alloy app — and returns its value,
        |with its fields or elements one level deep. Use it to look into a variable further than pebble_debug_wait shows.
        """,
    )
    suspend fun pebble_debug_evaluate(
        @McpDescription("The expression, e.g. clicks->where.x or s_buffer") expression: String,
        @McpDescription("The stack frame to evaluate in, 0 being where it stopped") frame: Int = 0,
    ): DebugValue = debug { it.evaluate(expression, frame) }

    @McpTool(name = "pebble_debug_stop")
    @McpDescription("Ends the debug session; the app runs on in the emulator without the debugger.")
    suspend fun pebble_debug_stop(): DebugState = debug { it.stop() }

    @Serializable
    data class AcceptedScreenshot(val reference: String)

    @Serializable
    data class Screenshots(val screenshots: List<Screenshot>)

    private suspend fun <T> debug(block: suspend (PebbleDebugger) -> T): T {
        val debugger = PebbleDebugger.getInstance(currentCoroutineContext().project)
        return try {
            block(debugger)
        } catch (e: AgentProblem) {
            mcpFail(e.message ?: "The Pebble plugin could not do it.")
        }
    }

    private suspend fun <T> call(block: suspend (PebbleAgent) -> T): T {
        val agent = PebbleAgent.getInstance(currentCoroutineContext().project)
        return try {
            block(agent)
        } catch (e: AgentProblem) {
            mcpFail(e.message ?: "The Pebble plugin could not do it.")
        }
    }
}
