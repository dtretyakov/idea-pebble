package com.github.dtretyakov.pebble.agent.mcp

import com.github.dtretyakov.pebble.agent.AgentProblem
import com.github.dtretyakov.pebble.agent.ApiLookup
import com.github.dtretyakov.pebble.agent.BuildOutcome
import com.github.dtretyakov.pebble.agent.EmulatorOutcome
import com.github.dtretyakov.pebble.agent.LogPage
import com.github.dtretyakov.pebble.agent.PebbleAgent
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
        |Saves what an emulator's screen shows now as a PNG in the project's `screenshots` directory and returns
        |its path, to read as an image. With all = true, starts each target platform's emulator in turn
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
        |unless told not to, a screenshot after the last.
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
        |screenshot and a diff image to read), or is recorded when it had no reference yet — look at it then.
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

    @Serializable
    data class AcceptedScreenshot(val reference: String)

    @Serializable
    data class Screenshots(val screenshots: List<Screenshot>)

    private suspend fun <T> call(block: suspend (PebbleAgent) -> T): T {
        val agent = PebbleAgent.getInstance(currentCoroutineContext().project)
        return try {
            block(agent)
        } catch (e: AgentProblem) {
            mcpFail(e.message ?: "The Pebble plugin could not do it.")
        }
    }
}
