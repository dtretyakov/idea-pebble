package com.github.dtretyakov.pebble.uitest

import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories

/**
 * Runs a scenario on each platform in turn: a suite per platform, a test per `expect`, the steps
 * before an `expect` part of its test.
 *
 * A reference screenshot that is not there yet is recorded and the test passes, saying so; one
 * that differs fails, with the screenshot and a picture of the difference beside the build output.
 */
class ScenarioRunner(
    private val emulator: Emulator,
    private val reporter: Reporter,
    /** Where references live: `<scenario dir>/screenshots/<platform>/<name>.png`. */
    private val references: Path,
    /** Where this run's screenshots go: `build/ui-tests/<platform>/<name>.png`. */
    private val results: Path,
    private val cancelled: () -> Boolean = { false },
) {

    interface Emulator {
        /** `pebble <arguments> --emulator <platform> --vnc`; the exit code and what it printed. */
        fun pebble(platform: String, arguments: List<String>): Pair<Int, String>

        fun tap(platform: String, x: Int, y: Int)

        fun screenshot(platform: String, file: Path): Pair<Int, String>

        fun sleep(millis: Long) = Thread.sleep(millis)
    }

    interface Reporter {
        fun suiteStarted(name: String)
        fun suiteFinished(name: String)
        fun testStarted(name: String, line: Int)
        fun output(text: String)
        fun testFailed(name: String, message: String, expected: Path? = null, actual: Path? = null)
        fun testFinished(name: String)
    }

    fun run(scenario: Scenario, platforms: List<String>) {
        for (platform in platforms) {
            if (cancelled()) return
            reporter.suiteStarted(platform)
            runOn(platform, scenario)
            reporter.suiteFinished(platform)
        }
    }

    private fun runOn(platform: String, scenario: Scenario) {
        val expectations = scenario.expectations
        var next = 0
        // A step that failed leaves the screen unknown: its test fails, and every one after it.
        var broken: String? = null
        var reported = false
        reporter.testStarted(expectations[0].name, expectations[0].line)
        for (step in scenario.steps) {
            if (cancelled()) return
            val current = expectations[next]
            if (step is Scenario.Step.Expect) {
                when {
                    broken == null -> expect(platform, step)
                    !reported -> reporter.testFailed(current.name, "An earlier step failed: $broken")
                }
                reporter.testFinished(current.name)
                reported = false
                if (++next < expectations.size) reporter.testStarted(expectations[next].name, expectations[next].line)
            } else if (broken == null) {
                perform(platform, step)?.let {
                    broken = "line ${step.line}, $it"
                    reporter.testFailed(current.name, "Line ${step.line} failed: $it")
                    reported = true
                }
            }
        }
    }

    /** Null when the step went through; otherwise why it did not. */
    private fun perform(platform: String, step: Scenario.Step): String? {
        reporter.output("line ${step.line}: ${describe(step)}\n")
        return when (step) {
            is Scenario.Step.Install -> checked(emulator.pebble(platform, listOf("install")))
            is Scenario.Step.Pebble -> checked(emulator.pebble(platform, step.arguments))
            is Scenario.Step.Wait -> null.also { emulator.sleep(step.millis) }
            is Scenario.Step.Tap -> runCatching { emulator.tap(platform, step.x, step.y) }.exceptionOrNull()?.let { it.message ?: "the tap failed" }
            is Scenario.Step.Expect -> null
        }
    }


    private fun expect(platform: String, step: Scenario.Step.Expect) {
        val actual = results.resolve(platform).createDirectories().resolve("${step.name}.png")
        checked(emulator.screenshot(platform, actual))?.let { return reporter.testFailed(step.name, "The screenshot failed: $it") }
        val reference = references.resolve(platform).resolve("${step.name}.png")
        if (!Files.exists(reference)) {
            reference.parent.createDirectories()
            Files.copy(actual, reference)
            reporter.output("Recorded ${references.relativize(reference)} as the reference: check it, and commit it.\n")
            return
        }
        val difference = ScreenComparison.compare(ImageIO.read(reference.toFile()), ImageIO.read(actual.toFile()))
        if (difference.matches) return
        val diff = results.resolve(platform).resolve("${step.name}.diff.png")
        difference.diff?.let { ImageIO.write(it, "png", diff.toFile()) }
        val message = if (difference.diff == null) "The screen is not the reference's size" else
            "${difference.pixels} of ${difference.total} pixels differ from ${references.relativize(reference)}; the difference is in $diff"
        reporter.testFailed(step.name, message, reference, actual)
    }

    private fun checked(result: Pair<Int, String>): String? {
        reporter.output(result.second.let { if (it.isEmpty() || it.endsWith("\n")) it else it + "\n" })
        return if (result.first == 0) null else "pebble exited with ${result.first}"
    }

    private fun describe(step: Scenario.Step): String = when (step) {
        is Scenario.Step.Install -> "install"
        is Scenario.Step.Wait -> "wait ${step.millis} ms"
        is Scenario.Step.Tap -> "tap ${step.x}, ${step.y}"
        is Scenario.Step.Pebble -> "pebble ${step.arguments.joinToString(" ")}"
        is Scenario.Step.Expect -> "expect ${step.name}"
    }
}
