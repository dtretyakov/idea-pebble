package com.github.dtretyakov.pebble.uitest

import com.github.dtretyakov.pebble.emulator.EmulatorScreen
import com.github.dtretyakov.pebble.emulator.EmulatorState
import com.github.dtretyakov.pebble.emulator.RfbClient
import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.ui.PebbleIcons
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.FileUrlProvider
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ui.FormBuilder
import java.awt.image.BufferedImage
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

class PebbleUiTestConfigurationType : ConfigurationTypeBase(
    ID,
    "Pebble UI Tests",
    "Scenarios that drive the emulator and compare its screen with reference screenshots",
    PebbleIcons.PEBBLE,
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun getId(): String = ID
            override fun createTemplateConfiguration(project: Project): RunConfiguration = PebbleUiTestConfiguration(project, this)
            override fun getOptionsClass() = PebbleUiTestOptions::class.java
        })
    }

    companion object {
        const val ID = "PebbleUiTests"
    }
}

class PebbleUiTestOptions : RunConfigurationOptions() {
    /** A `.pbtest` file, or a directory of them; relative to the project when not absolute. */
    var scenario: String? by string("tests/ui")
}

class PebbleUiTestConfiguration(project: Project, factory: ConfigurationFactory) :
    RunConfigurationBase<PebbleUiTestOptions>(project, factory, "Pebble UI Tests") {

    public override fun getOptions(): PebbleUiTestOptions = super.getOptions() as PebbleUiTestOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = Editor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = PebbleUiTestState(this, environment)

    fun scenarioPath(): Path? {
        val root = PebbleProject.getInstance(project).primaryRoot() ?: return null
        val path = options.scenario?.takeIf { it.isNotBlank() } ?: return null
        return Path.of(path).let { if (it.isAbsolute) it else root.resolve(it) }.normalize()
    }

    private class Editor(project: Project) : SettingsEditor<PebbleUiTestConfiguration>() {
        private val field = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor().withTitle("Scenario"))
        }

        override fun resetEditorFrom(configuration: PebbleUiTestConfiguration) {
            field.text = configuration.options.scenario.orEmpty()
        }

        override fun applyEditorTo(configuration: PebbleUiTestConfiguration) {
            configuration.options.scenario = field.text
        }

        override fun createEditor(): JComponent =
            FormBuilder.createFormBuilder().addLabeledComponent("Scenario file or directory:", field).panel
    }
}

/** The run: each scenario on each of its platforms, in the IDE's test runner. */
class PebbleUiTestState(private val configuration: PebbleUiTestConfiguration, private val environment: ExecutionEnvironment) : RunProfileState {

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val project = configuration.project
        val model = PebbleProject.getInstance(project)
        val root = model.primaryRoot() ?: throw ExecutionException("No Pebble project here.")
        val tool = PebbleSdkService.getInstance().tool() ?: throw ExecutionException("pebble-tool is not installed.")
        val target = configuration.scenarioPath() ?: throw ExecutionException("No scenario is set.")
        val files = scenarios(target).ifEmpty { throw ExecutionException("No .pbtest scenarios in $target.") }

        val handler = ScenarioProcessHandler(files, root, model.platforms(root), PebbleToolEmulator(tool, root))
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(FRAMEWORK, handler, object : SMTRunnerConsoleProperties(configuration, FRAMEWORK, executor) {
            override fun getTestLocator(): SMTestLocator = ScenarioLocator
        })
        return DefaultExecutionResult(console, handler)
    }

    companion object {
        const val FRAMEWORK = "Pebble UI tests"

        fun scenarios(target: Path): List<Path> = when {
            target.isDirectory() -> target.listDirectoryEntries("*.$SCENARIO_EXTENSION").sortedBy { it.name }
            Files.isRegularFile(target) -> listOf(target)
            else -> emptyList()
        }
    }
}

const val SCENARIO_EXTENSION = "pbtest"

/**
 * The scenarios, run on a pooled thread, reported to the test runner as TeamCity service
 * messages — the test runner's own protocol — on what reads as the process's output.
 */
class ScenarioProcessHandler(
    private val files: List<Path>,
    private val root: Path,
    private val targetPlatforms: List<String>,
    private val emulator: ScenarioRunner.Emulator,
) : ProcessHandler() {

    @Volatile
    private var cancelled = false

    override fun startNotify() {
        super.startNotify()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                run()
            } finally {
                notifyProcessTerminated(0)
            }
        }
    }

    private fun run() {
        for (file in files) {
            val scenario = ScenarioParser.parse(file.readText())
            val reporter = TeamCityReporter(file) { message(it) }
            reporter.suiteStarted(file.name)
            if (scenario.problems.isNotEmpty()) {
                val name = "${file.name} cannot be read"
                reporter.testStarted(name, scenario.problems.first().line)
                reporter.testFailed(name, scenario.problems.joinToString("\n") { "line ${it.line}: ${it.message}" })
                reporter.testFinished(name)
            } else {
                val paths = ScenarioPaths(file, root)
                ScenarioRunner(emulator, reporter, paths.references, paths.results, { cancelled })
                    .run(scenario, scenario.platforms ?: targetPlatforms)
            }
            reporter.suiteFinished(file.name)
            if (cancelled) return
        }
    }

    private fun message(text: String) = notifyTextAvailable(text, ProcessOutputTypes.STDOUT)

    override fun destroyProcessImpl() {
        cancelled = true
    }

    override fun detachProcessImpl() {
        cancelled = true
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null
}

/** The runner's events as the test runner's service messages; locations point at the scenario's lines. */
class TeamCityReporter(private val file: Path, private val write: (String) -> Unit) : ScenarioRunner.Reporter {

    override fun suiteStarted(name: String) = write(ServiceMessageBuilder.testSuiteStarted(name).addAttribute("locationHint", ScenarioLocator.url(file, 1)).toString() + "\n")

    override fun suiteFinished(name: String) = write(ServiceMessageBuilder.testSuiteFinished(name).toString() + "\n")

    override fun testStarted(name: String, line: Int) = write(ServiceMessageBuilder.testStarted(name).addAttribute("locationHint", ScenarioLocator.url(file, line)).toString() + "\n")

    override fun output(text: String) = write(text)

    override fun testFailed(name: String, message: String, expected: Path?, actual: Path?) {
        val failure = ServiceMessageBuilder.testFailed(name).addAttribute("message", message)
        if (expected != null && actual != null) {
            failure.addAttribute("type", "comparisonFailure")
                .addAttribute("expectedFile", expected.toString())
                .addAttribute("actualFile", actual.toString())
        }
        write(failure.toString() + "\n")
    }

    override fun testFinished(name: String) = write(ServiceMessageBuilder.testFinished(name).toString() + "\n")
}

/** `pebble-ui://<file>:<line>`: a test is the `expect` line that names it, a suite the scenario. */
object ScenarioLocator : SMTestLocator {

    const val PROTOCOL = "pebble-ui"

    fun url(file: Path, line: Int): String = "$PROTOCOL://$file:$line"

    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
        if (protocol != PROTOCOL) return emptyList()
        val file = LocalFileSystem.getInstance().findFileByPath(path.substringBeforeLast(':')) ?: return emptyList()
        val line = path.substringAfterLast(':').toIntOrNull()?.minus(1) ?: 0
        return listOfNotNull(FileUrlProvider.createLocationFor(project, file, line))
    }
}

/**
 * Where a scenario's screenshots are: the references beside it, which belong in version control,
 * and this run's under the project's `build/`, which does not.
 */
class ScenarioPaths(scenario: Path, root: Path) {
    val references: Path = scenario.parent.resolve("screenshots")
    val results: Path = root.resolve("build/ui-tests").resolve(scenario.name.removeSuffix(".$SCENARIO_EXTENSION"))

    fun reference(platform: String, name: String): Path = references.resolve(platform).resolve("$name.png")

    fun result(platform: String, name: String): Path = results.resolve(platform).resolve("$name.png")
}

/** The emulator through pebble-tool, and touches through its screen. */
class PebbleToolEmulator(private val tool: Path, private val root: Path) : ScenarioRunner.Emulator {

    override fun pebble(platform: String, arguments: List<String>): Pair<Int, String> {
        val output = CapturingProcessHandler(PebbleCommands.command(tool, arguments + listOf("--emulator", platform, "--vnc"), root))
            .runProcess(TIMEOUT_MILLIS)
        return (if (output.isTimeout) -1 else output.exitCode) to (output.stdout + output.stderr)
    }

    override fun screenshot(platform: String, file: Path): Pair<Int, String> =
        pebble(platform, listOf("screenshot", "--no-open", file.toString()))

    override fun tap(platform: String, x: Int, y: Int) {
        val frame = CountDownLatch(1)
        var size: Pair<Int, Int>? = null
        val client = RfbClient("127.0.0.1", EmulatorState.VNC_PORT, object : RfbClient.Listener {
            override fun frame(image: BufferedImage) {
                size = image.width to image.height
                frame.countDown()
            }

            override fun disconnected(cause: Throwable?) = frame.countDown()
        })
        client.use {
            it.start()
            if (!frame.await(10, TimeUnit.SECONDS)) error("the emulator's screen did not answer")
            val (width, height) = size ?: error("the emulator's screen is not available")
            val point = EmulatorScreen.pointerFor(EmulatorScreen.of(platform, width, height), width, height, x, y)
            it.pointer(point.x, point.y, true)
            Thread.sleep(100)
            it.pointer(point.x, point.y, false)
            Thread.sleep(100)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 300_000
    }
}
