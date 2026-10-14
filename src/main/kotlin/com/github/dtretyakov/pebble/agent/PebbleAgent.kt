package com.github.dtretyakov.pebble.agent

import com.github.dtretyakov.pebble.build.BuildMessage
import com.github.dtretyakov.pebble.build.BuildResult
import com.github.dtretyakov.pebble.build.PebbleBuild
import com.github.dtretyakov.pebble.emulator.EmulatorState
import com.github.dtretyakov.pebble.emulator.PlatformScreenshots
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.run.LogLevel
import com.github.dtretyakov.pebble.run.PebbleRunConfiguration
import com.github.dtretyakov.pebble.run.PebbleRunConfigurationType
import com.github.dtretyakov.pebble.run.PebbleRunTarget
import com.github.dtretyakov.pebble.sdk.PebblePlatform
import com.github.dtretyakov.pebble.sdk.PebbleSdkList
import com.github.dtretyakov.pebble.uitest.PebbleToolEmulator
import com.github.dtretyakov.pebble.uitest.PebbleUiTestConfiguration
import com.github.dtretyakov.pebble.uitest.PebbleUiTestConfigurationType
import com.github.dtretyakov.pebble.uitest.PebbleUiTestState
import com.github.dtretyakov.pebble.uitest.ScenarioPaths
import com.github.dtretyakov.pebble.uitest.ScenarioParser
import com.github.dtretyakov.pebble.uitest.ScenarioRunner
import com.intellij.execution.ExecutionListener
import com.intellij.execution.Executor
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.invariantSeparatorsPathString

/** Something an agent asked for that cannot be done, in words it can act on. */
class AgentProblem(message: String) : Exception(message)

@Serializable
data class ProjectSummary(
    val root: String,
    val name: String?,
    /** `native` (C), `moddable` (Alloy), `rocky` or `package`. */
    val type: String,
    val watchface: Boolean,
    val uuid: String?,
    val targetPlatforms: List<String>,
    /** The platform Build, Run and code insight use unless told otherwise. */
    val activePlatform: String?,
    val sdk: String?,
    val pebbleTool: String?,
    val emulators: List<RunningEmulatorInfo>,
    val platforms: List<PlatformInfo>,
)

@Serializable
data class RunningEmulatorInfo(val platform: String, val sdk: String, val screen: Boolean, val debuggable: Boolean)

@Serializable
data class PlatformInfo(val id: String, val watches: String, val width: Int, val height: Int, val color: Boolean, val round: Boolean, val touch: Boolean)

@Serializable
data class BuildOutcome(
    val succeeded: Boolean,
    val exitCode: Int,
    val errors: List<BuildProblem>,
    val warnings: List<BuildProblem>,
    val memory: List<MemoryLine>,
    /** The `.pbw` built, relative to the project. */
    val bundle: String? = null,
    /** Why there was no build at all. */
    val problem: String? = null,
)

@Serializable
data class BuildProblem(
    /** Relative to the project root where it is inside it. */
    val file: String?,
    val line: Int?,
    val column: Int?,
    val message: String,
    /** The platforms it was reported on: one fault is reported once per platform built. */
    val platforms: List<String>,
)

@Serializable
data class MemoryLine(
    val platform: String,
    val binary: String,
    val ram: Long,
    val ramLimit: Long?,
    val heapFree: Long?,
    val resources: Long?,
    val resourceLimit: Long?,
    /** Little heap left, or resources the store would refuse. */
    val tight: Boolean,
)

@Serializable
data class RunOutcome(
    val installed: Boolean,
    val platform: String,
    /** The build before it, when it failed. */
    val build: BuildOutcome? = null,
    /** pebble-tool's last words, when the install did not go through. */
    val output: List<String> = emptyList(),
    /** Pass to `pebble_logs` as `after` to read this run's logs from the start. */
    val logsAfter: Long,
)

@Serializable
data class EmulatorOutcome(
    val platform: String,
    /** One line per step: what was done, or why it was not. */
    val steps: List<String>,
    val failed: Boolean,
    val screenshot: Screenshot? = null,
)

@Serializable
data class Screenshot(
    val platform: String,
    /** The screenshot, pixel for pixel. */
    val file: String,
    val width: Int,
    val height: Int,
    val round: Boolean,
    /** The same, enlarged for reading: open this image to see the screen. */
    val view: String? = null,
)

@Serializable
data class EmulatorReset(val action: String, val output: List<String>)

@Serializable
data class SdkState(
    val current: String?,
    val installed: List<String>,
    /** Versions pebble-tool can install, when asked for the list. */
    val available: List<String> = emptyList(),
    /** pebble-tool's last words, after an install or an activation. */
    val output: List<String> = emptyList(),
)

/**
 * What the MCP tools do, apart from MCP: everything an agent asks of the plugin, on the plugin's
 * own pieces — `pebble build`, the Pebble run configuration, the UI test steps, pebble-tool's
 * screenshots, the SDK's headers — so the IDE is in the same state the user would have put it in.
 */
@Service(Service.Level.PROJECT)
class PebbleAgent(private val project: Project) {

    /** The run this service started last, stopped before it starts another. */
    @Volatile
    private var lastRun: ProcessHandler? = null

    suspend fun describe(): ProjectSummary {
        val (root, info, active) = readAction {
            val model = PebbleProject.getInstance(project)
            val root = model.primaryRoot()
            Triple(root, root?.let(model::infoAt), root?.let(model::activePlatform))
        }
        root ?: throw AgentProblem("No Pebble project is open in this IDE window.")
        val service = PebbleSdkService.getInstance()
        val targets = info?.targetPlatforms.orEmpty()
        return ProjectSummary(
            root = root.toString(),
            name = info?.name,
            type = info?.projectType?.id ?: "unknown",
            watchface = info?.watchface == true,
            uuid = info?.uuid,
            targetPlatforms = targets,
            activePlatform = active,
            sdk = service.currentSdk()?.version,
            pebbleTool = service.tool()?.toString(),
            emulators = EmulatorState.running().map { RunningEmulatorInfo(it.platform, it.sdkVersion, it.vnc, it.gdbPort != null) },
            platforms = targets.mapNotNull(PebblePlatform::of).map { PlatformInfo(it.id, it.watches, it.width, it.height, it.color, it.round, it.touch) },
        )
    }

    suspend fun build(debug: Boolean = false): BuildOutcome {
        val root = root()
        saveDocuments()
        val result = withContext(Dispatchers.IO) { PebbleBuild.run(project, root, debug, null) }
        return outcome(root, result)
    }

    /**
     * Builds, then installs on the emulator with the Pebble run configuration, as Run does —
     * the run in the Run window, its logs there and in [PebbleRunLogs] — and returns once
     * pebble-tool says the app is installed and started.
     */
    suspend fun run(platform: String?, timeoutSeconds: Long = 240): RunOutcome {
        val root = root()
        val chosen = platform(root, platform)
        val build = build()
        val logs = PebbleRunLogs.getInstance(project).buffer
        if (!build.succeeded) return RunOutcome(false, chosen, build = build, logsAfter = logs.position - 1)
        lastRun?.takeIf { !it.isProcessTerminated }?.let { previous ->
            previous.destroyProcess()
            withContext(Dispatchers.IO) { previous.waitFor(10_000) }
        }
        val start = logs.position - 1
        var attempt = launch(chosen, timeoutSeconds)
        // pebble-tool gives an emulator it has just started a few seconds; a cold one can need more.
        if (!attempt.installed && attempt.output.any { "Connection refused" in it }) attempt = launch(chosen, timeoutSeconds)
        return attempt.copy(logsAfter = start)
    }

    private suspend fun launch(platform: String, timeoutSeconds: Long): RunOutcome {
        val settings = withContext(ui) {
            val type = ConfigurationTypeUtil.findConfigurationType(PebbleRunConfigurationType::class.java)
            RunManager.getInstance(project).createConfiguration("Pebble on $platform", type.configurationFactories.first()).also {
                val configuration = it.configuration as PebbleRunConfiguration
                configuration.options.target = PebbleRunTarget.EMULATOR
                configuration.options.platform = platform
                configuration.options.logs = true
                // Built already, just now.
                configuration.beforeRunTasks = emptyList()
            }
        }
        val (output, installed) = execute(settings, timeoutSeconds, onStart = { lastRun = it }) { INSTALLED in it }.let { it.output to it.matched }
        val lines = output.lines().map { ANSI.replace(it, "").trimEnd() }.filter { it.isNotBlank() }
        return RunOutcome(installed, platform, output = if (installed) emptyList() else lines.takeLast(30), logsAfter = 0)
    }

    internal data class Execution(val output: String, val matched: Boolean, val ended: Boolean)

    /**
     * Runs [settings] as Run does, in the Run window, and waits until its output satisfies [done],
     * its process ends, or [timeoutSeconds] pass.
     */
    internal suspend fun execute(
        settings: RunnerAndConfigurationSettings,
        timeoutSeconds: Long,
        executor: Executor = DefaultRunExecutor.getRunExecutorInstance(),
        onStart: (ProcessHandler) -> Unit = {},
        onText: (String) -> Unit = {},
        done: (CharSequence) -> Boolean,
    ): Execution {
        val environment = ExecutionEnvironmentBuilder.create(executor, settings).build()
        val output = StringBuffer()
        val finished = CompletableDeferred<Boolean>()
        val ended = java.util.concurrent.atomic.AtomicBoolean()
        val connection = project.messageBus.connect()
        connection.subscribe(
            ExecutionManager.EXECUTION_TOPIC,
            object : ExecutionListener {
                override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                    if (env !== environment) return
                    onStart(handler)
                    handler.addProcessListener(
                        object : ProcessListener {
                            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                                output.append(event.text)
                                onText(event.text)
                                if (done(output)) finished.complete(true)
                            }

                            override fun processTerminated(event: ProcessEvent) {
                                ended.set(true)
                                finished.complete(done(output))
                            }
                        },
                    )
                }

                override fun processNotStarted(executorId: String, env: ExecutionEnvironment, cause: Throwable?) {
                    if (env !== environment) return
                    output.append(cause?.message ?: "The run did not start.")
                    ended.set(true)
                    finished.complete(false)
                }
            },
        )
        try {
            withContext(ui) { ProgramRunnerUtil.executeConfiguration(environment, false, true) }
            val result = withTimeoutOrNull(timeoutSeconds * 1000) { finished.await() } ?: false
            return Execution(output.toString(), result, ended.get())
        } finally {
            Disposer.dispose(connection)
        }
    }

    /**
     * Runs UI test scenarios — a `.pbtest` file, or a directory of them — with the Pebble UI
     * Tests run configuration, in the IDE's test runner, and returns each screen's verdict.
     */
    suspend fun uiTests(path: String?, timeoutSeconds: Long = 1800): UiTestOutcome {
        val root = root()
        tool()
        val target = (path?.takeIf { it.isNotBlank() } ?: "tests/ui").let { Path.of(it) }.let { if (it.isAbsolute) it else root.resolve(it) }.normalize()
        if (PebbleUiTestState.scenarios(target).isEmpty()) throw AgentProblem("No .pbtest scenarios at ${relative(root, target)}.")
        saveDocuments()
        val settings = withContext(ui) {
            val type = ConfigurationTypeUtil.findConfigurationType(PebbleUiTestConfigurationType::class.java)
            RunManager.getInstance(project).createConfiguration("UI tests: ${target.fileName}", type.configurationFactories.first()).also {
                (it.configuration as PebbleUiTestConfiguration).options.scenario = target.toString()
            }
        }
        val results = UiTestResults(root)
        val execution = execute(settings, timeoutSeconds, onText = { synchronized(results) { results.feed(it) } }) { false }
        val outcome = synchronized(results) { results.outcome() }
        if (!execution.ended) throw AgentProblem("The UI tests did not finish in $timeoutSeconds seconds; ${outcome.tests.size} screens were checked by then.")
        return outcome.copy(tests = outcome.tests.map { withView(root, it) })
    }

    /** Makes the last run's screenshot of [screen] on [platform] the scenario's reference. */
    suspend fun acceptScreenshot(scenario: String, platform: String, screen: String): String {
        val root = root()
        val file = Path.of(scenario).let { if (it.isAbsolute) it else root.resolve(it) }.normalize()
        if (!Files.isRegularFile(file)) throw AgentProblem("No scenario at $scenario.")
        val paths = ScenarioPaths(file, root)
        val actual = paths.result(platform, screen)
        if (!Files.exists(actual)) throw AgentProblem("No screenshot of '$screen' on $platform from the last run: run the scenario first.")
        val reference = paths.reference(platform, screen)
        withContext(Dispatchers.IO) {
            Files.createDirectories(reference.parent)
            Files.copy(actual, reference, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        refresh(reference.parent)
        return relative(root, reference)
    }

    /** Runs scenario steps — `press select`, `tap 100 120`, `time 10:10`, `battery 20` — on a running emulator. */
    suspend fun emulator(platform: String?, steps: String, screenshot: Boolean): EmulatorOutcome {
        val root = root()
        val chosen = runningPlatform(root, platform)
        val parsed = ScenarioParser.parse(steps, steps = true)
        parsed.problems.firstOrNull()?.let { throw AgentProblem("Line ${it.line}: ${it.message}") }
        if (parsed.platforms != null) throw AgentProblem("'platforms' belongs in a .pbtest file; pass the platform instead.")
        if (parsed.expectations.isNotEmpty()) throw AgentProblem("'expect' belongs in a .pbtest file; ask for a screenshot instead.")
        val emulator = PebbleToolEmulator(tool(), root)
        val report = mutableListOf<String>()
        val runner = ScenarioRunner(emulator, OutputOnly { report += it.trimEnd() }, root, root)
        var failed = false
        withContext(Dispatchers.IO) {
            for (step in parsed.steps) {
                runner.perform(chosen, step)?.let {
                    report += "failed: $it"
                    failed = true
                }
                if (failed) break
            }
        }
        val shot = if (screenshot && !failed) take(root, chosen) else null
        return EmulatorOutcome(chosen, report.filter { it.isNotBlank() }, failed, shot)
    }

    /** The emulator's screen now, saved where Capture Every Platform saves it; or every platform's, one after another. */
    suspend fun screenshot(platform: String?, all: Boolean): List<Screenshot> {
        val root = root()
        if (!all) return listOf(take(root, runningPlatform(root, platform)))
        saveDocuments()
        val (code, text) = withContext(Dispatchers.IO) {
            val output = com.intellij.execution.process.CapturingProcessHandler(
                com.github.dtretyakov.pebble.project.PebbleCommands.command(tool(), listOf("screenshot", "--all-platforms", "--no-open"), root),
            ).runProcess(15 * 60 * 1000)
            output.exitCode to (output.stdout + output.stderr)
        }
        if (code != 0) throw AgentProblem("pebble screenshot --all-platforms failed: ${text.lines().lastOrNull { it.isNotBlank() }.orEmpty()}")
        refresh(root.resolve(PlatformScreenshots.DIRECTORY))
        return PlatformScreenshots.latest(root.resolve(PlatformScreenshots.DIRECTORY), readAction { PebbleProject.getInstance(project).infoAt(root)?.targetPlatforms.orEmpty() })
            .map { describe(root, it.platform, it.file) }
    }

    /**
     * `pebble kill` — every emulator stopped, and with them any run or debug session on one — or
     * that and then `pebble wipe`, which forgets what the current SDK's emulators had installed and
     * set: for an emulator that fails to install apps or to boot. Never `wipe --everything`,
     * which would delete the SDKs too.
     */
    suspend fun resetEmulators(action: String): EmulatorReset {
        if (action != "kill" && action != "wipe") throw AgentProblem("Actions: kill, wipe.")
        val root = root()
        lastRun?.takeIf { !it.isProcessTerminated }?.destroyProcess()
        val output = mutableListOf<String>()
        suspend fun run(vararg arguments: String) {
            val (code, text) = pebble(root, arguments.toList(), 120)
            output += text.lines().map { ANSI.replace(it, "").trimEnd() }.filter { it.isNotBlank() }
            if (code != 0) throw AgentProblem("pebble ${arguments.joinToString(" ")} failed: ${output.lastOrNull().orEmpty()}")
        }
        run("kill")
        // SIGTERM takes a moment; what will not go is killed outright. Wiping waits for them all.
        if (withTimeoutOrNull(10_000) { while (EmulatorState.running().isNotEmpty()) kotlinx.coroutines.delay(200) } == null) {
            run("kill", "--force")
            withTimeoutOrNull(10_000) { while (EmulatorState.running().isNotEmpty()) kotlinx.coroutines.delay(200) }
        }
        if (EmulatorState.running().isNotEmpty()) throw AgentProblem("The emulators did not stop.")
        if (action == "wipe") run("wipe")
        return EmulatorReset(action, output)
    }

    /** The SDKs; or one installed, or made the current one, with pebble-tool. */
    suspend fun sdk(action: String, version: String?): SdkState {
        val root = root()
        val output = when (action) {
            "list" -> null
            "install", "activate" -> {
                val target = version?.takeIf { it.isNotBlank() } ?: if (action == "install") "latest" else throw AgentProblem("Which version to activate?")
                val (code, text) = pebble(root, listOf("sdk", action, target), if (action == "install") 1800 else 120)
                val lines = text.lines().map { ANSI.replace(it, "").trimEnd() }.filter { it.isNotBlank() }
                if (code != 0) throw AgentProblem("pebble sdk $action $target failed: ${lines.takeLast(3).joinToString(" ")}")
                lines.takeLast(10)
            }
            else -> throw AgentProblem("Actions: list, install, activate.")
        }
        val sdks = PebbleSdkService.getInstance().sdks()
        val available = if (action == "list") {
            pebble(root, listOf("sdk", "list"), 120).takeIf { it.first == 0 }?.second?.let(PebbleSdkList::available).orEmpty()
        } else {
            emptyList()
        }
        return SdkState(sdks.current?.version, sdks.installed.map { it.version }, available, output.orEmpty())
    }

    private suspend fun pebble(root: Path, arguments: List<String>, timeoutSeconds: Int): Pair<Int, String> = withContext(Dispatchers.IO) {
        val output = com.intellij.execution.process.CapturingProcessHandler(
            com.github.dtretyakov.pebble.project.PebbleCommands.command(tool(), arguments, root),
        ).runProcess(timeoutSeconds * 1000)
        (if (output.isTimeout) -1 else output.exitCode) to (output.stdout + output.stderr)
    }

    /** A release build, and what the appstore would say of it; also in the Build window. */
    suspend fun releaseCheck(): List<com.github.dtretyakov.pebble.build.ReleaseCheck.Item> {
        val root = root()
        saveDocuments()
        return withContext(Dispatchers.IO) { com.github.dtretyakov.pebble.build.PebbleRelease.check(project, root, null) }
            .map { item -> item.copy(file = item.file?.let { relative(root, Path.of(it)) }) }
    }

    fun logs(after: Long, limit: Int, minimum: String?, contains: String?): LogPage {
        val level = minimum?.let { name -> LogLevel.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: throw AgentProblem("Levels: ${LogLevel.entries.joinToString { it.name.lowercase() }}") }
        return PebbleRunLogs.getInstance(project).buffer.read(after, limit, level, contains)
    }

    suspend fun api(symbol: String, platform: String?): ApiLookup {
        val root = root()
        val sdk = PebbleSdkService.getInstance().currentSdk() ?: throw AgentProblem("No Pebble SDK is installed: pebble sdk install latest.")
        val platforms = platform?.let { listOf(it) }
            ?: readAction { PebbleProject.getInstance(project).infoAt(root)?.targetPlatforms }?.takeIf { it.isNotEmpty() }
            ?: sdk.platforms()
        return withContext(Dispatchers.IO) { PebbleApi.lookup(sdk, symbol, platforms) }
    }

    private suspend fun take(root: Path, platform: String): Screenshot {
        val version = EmulatorState.running().firstOrNull { it.platform == platform }?.sdkVersion ?: "unknown"
        val directory = root.resolve(PlatformScreenshots.DIRECTORY).createDirectories()
        val file = directory.resolve("${platform}_${version}_${LocalDateTime.now().format(STAMP)}.png")
        val (code, text) = withContext(Dispatchers.IO) { PebbleToolEmulator(tool(), root).screenshot(platform, file) }
        if (code != 0 || !Files.exists(file)) throw AgentProblem("The screenshot failed: ${text.lines().lastOrNull { it.isNotBlank() }.orEmpty()}")
        refresh(directory)
        return describe(root, platform, file)
    }

    private fun describe(root: Path, platform: String, file: Path): Screenshot {
        val image = runCatching { ImageIO.read(file.toFile()) }.getOrNull()
        val view = image?.let { ScreenViews.write(ScreenViews.enlarge(it), views(root).resolve(file.fileName.toString())) }
        return Screenshot(platform, relative(root, file), image?.width ?: 0, image?.height ?: 0, PebblePlatform.of(platform)?.round == true, view?.let { relative(root, it) })
    }

    /** Where the enlarged views go: under `build/`, which nobody commits. */
    private fun views(root: Path): Path = root.resolve("build/pebble-agent")

    /** A failed screen as one picture to look at; a recorded one enlarged, to check before committing it. */
    private fun withView(root: Path, result: UiTestResult): UiTestResult {
        fun read(path: String?) = path?.let { runCatching { ImageIO.read(root.resolve(it).toFile()) }.getOrNull() }
        val name = "${Path.of(result.scenario).fileName.toString().removeSuffix(".pbtest")}-${result.platform}-${result.screen}.png"
        val view = when (result.status) {
            "failed" -> {
                val reference = read(result.reference)
                val actual = read(result.actual) ?: return result
                val panels = listOfNotNull(reference?.let { "reference" to it }, "now" to actual, read(result.diff)?.let { "difference" to it })
                ScreenViews.write(ScreenViews.sideBySide(panels), views(root).resolve("ui-tests").resolve(name))
            }
            "recorded" -> {
                val paths = ScenarioPaths(root.resolve(result.scenario), root)
                val recorded = runCatching { ImageIO.read(paths.reference(result.platform ?: return result, result.screen).toFile()) }.getOrNull() ?: return result
                ScreenViews.write(ScreenViews.enlarge(recorded), views(root).resolve("ui-tests").resolve(name))
            }
            else -> return result
        }
        return result.copy(view = relative(root, view))
    }

    internal suspend fun root(): Path =
        readAction { PebbleProject.getInstance(project).primaryRoot() } ?: throw AgentProblem("No Pebble project is open in this IDE window.")

    internal fun tool(): Path = PebbleSdkService.getInstance().tool() ?: throw AgentProblem("pebble-tool is not installed: see https://developer.repebble.com/sdk/")

    internal suspend fun platform(root: Path, asked: String?): String {
        val model = PebbleProject.getInstance(project)
        val targets = readAction { model.infoAt(root)?.targetPlatforms.orEmpty() }
        if (asked != null) {
            if (PebblePlatform.of(asked) == null) throw AgentProblem("No such platform '$asked'. Platforms: ${PebblePlatform.entries.joinToString()}")
            if (targets.isNotEmpty() && asked !in targets) throw AgentProblem("The app does not target $asked; it targets ${targets.joinToString()}.")
            return asked
        }
        return readAction { model.activePlatform(root) } ?: targets.firstOrNull() ?: throw AgentProblem("The app targets no platform.")
    }

    /** [asked], or the one emulator with a screen; an emulator must be running either way. */
    private suspend fun runningPlatform(root: Path, asked: String?): String {
        val running = EmulatorState.running()
        val chosen = asked ?: running.singleOrNull { it.vnc }?.platform ?: platform(root, null)
        if (running.none { it.platform == chosen }) throw AgentProblem("No $chosen emulator is running: pebble_run installs the app on one.")
        return chosen
    }

    private fun outcome(root: Path, result: BuildResult): BuildOutcome {
        fun problems(severity: BuildMessage.Severity) = result.messages.filter { it.severity == severity }
            .groupBy { listOf(it.file?.let { file -> relative(root, file) }, it.line, it.column, it.text) }
            .map { (_, same) -> same.first().let { BuildProblem(it.file?.let { file -> relative(root, file) }, it.line, it.column, it.text, same.mapNotNull { m -> m.platform }.distinct()) } }
            .take(MAX_PROBLEMS)
        return BuildOutcome(
            succeeded = result.succeeded,
            exitCode = result.exitCode,
            errors = problems(BuildMessage.Severity.ERROR),
            warnings = problems(BuildMessage.Severity.WARNING),
            memory = result.memory.map { MemoryLine(it.platform, it.binary, it.ram, it.ramLimit, it.heapFree, it.resources, it.resourceLimit, it.isTight) },
            bundle = result.bundle?.let { relative(root, it) },
            problem = result.problem,
        )
    }

    internal suspend fun saveDocuments() = withContext(ui) { FileDocumentManager.getInstance().saveAllDocuments() }

    private fun refresh(directory: Path) = LocalFileSystem.getInstance().refreshNioFiles(listOf(directory), true, false, null)

    internal fun relative(root: Path, file: Path): String =
        if (file.startsWith(root)) root.relativize(file).invariantSeparatorsPathString else file.toString()

    private class OutputOnly(private val output: (String) -> Unit) : ScenarioRunner.Reporter {
        override fun suiteStarted(name: String) = Unit
        override fun suiteFinished(name: String) = Unit
        override fun testStarted(name: String, line: Int) = Unit
        override fun output(text: String) = text.lines().filter { it.isNotBlank() }.forEach(output)
        override fun testFailed(name: String, message: String, expected: Path?, actual: Path?) = Unit
        override fun testFinished(name: String) = Unit
    }

    companion object {
        /** What pebble-tool prints once the app is installed, and the watch has started it. */
        const val INSTALLED = "App install succeeded."

        private const val MAX_PROBLEMS = 100

        /** The UI thread, where writing — saving documents — is safe: not inside some dialog's modality. */
        internal val ui = Dispatchers.EDT + ModalityState.nonModal().asContextElement()
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val ANSI = Regex("\u001b\\[[0-9;?]*[A-Za-z]")

        fun getInstance(project: Project): PebbleAgent = project.service()
    }
}
