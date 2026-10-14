package com.github.dtretyakov.pebble.agent

import com.github.dtretyakov.pebble.uitest.ScenarioParser
import com.github.dtretyakov.pebble.uitest.ScenarioPaths
import com.github.dtretyakov.pebble.uitest.ScenarioRunner
import com.github.dtretyakov.pebble.uitest.TeamCityReporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** What the UI test run tells the test runner, read back into each screen's verdict. */
class UiTestResultsTest {

    /** An emulator whose screen is one colour, which a test can change. */
    private class Screen : ScenarioRunner.Emulator {
        var colour: Color = Color.WHITE
        override fun pebble(platform: String, arguments: List<String>) = 0 to ""
        override fun tap(platform: String, x: Int, y: Int) = Unit
        override fun sleep(millis: Long) = Unit
        override fun screenshot(platform: String, file: Path): Pair<Int, String> {
            val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply { color = colour; fillRect(0, 0, 20, 20) }
            ImageIO.write(image, "png", file.toFile())
            return 0 to ""
        }
    }

    private fun run(root: Path, scenario: Path, screen: Screen): UiTestOutcome {
        val results = UiTestResults(root)
        val paths = ScenarioPaths(scenario, root)
        val reporter = TeamCityReporter(scenario) { results.feed(it) }
        val parsed = ScenarioParser.parse(Files.readString(scenario))
        reporter.suiteStarted(scenario.fileName.toString())
        ScenarioRunner(screen, reporter, paths.references, paths.results).run(parsed, parsed.platforms!!)
        reporter.suiteFinished(scenario.fileName.toString())
        return results.outcome()
    }

    @Test
    fun `recorded the first time, passed the next, failed with pictures when the screen changes`(@TempDir root: Path) {
        val scenario = root.resolve("tests/ui").createDirectories().resolve("home.pbtest")
        scenario.writeText("platforms basalt emery\ninstall\nexpect home\npress select\nexpect menu\n")
        val screen = Screen()

        val first = run(root, scenario, screen)
        assertEquals(4, first.recorded)
        assertEquals(listOf("basalt", "basalt", "emery", "emery"), first.tests.map { it.platform })
        assertEquals(listOf("home", "menu", "home", "menu"), first.tests.map { it.screen })
        assertTrue(first.tests.all { it.scenario == "tests/ui/home.pbtest" })

        assertEquals(4, run(root, scenario, screen).passed)

        screen.colour = Color.BLACK
        val changed = run(root, scenario, screen)
        assertEquals(4, changed.failed)
        val failure = changed.tests.first()
        assertEquals("failed", failure.status)
        assertTrue(failure.message!!.contains("pixels differ"), failure.message)
        assertEquals("tests/ui/screenshots/basalt/home.png", failure.reference)
        assertEquals("build/ui-tests/home/basalt/home.png", failure.actual)
        assertEquals("build/ui-tests/home/basalt/home.diff.png", failure.diff)
        assertTrue(Files.exists(root.resolve(failure.diff!!)))
    }

    @Test
    fun `a scenario that cannot be read is one failed test, with no platform`(@TempDir root: Path) {
        val results = UiTestResults(root)
        val scenario = root.resolve("bad.pbtest")
        val reporter = TeamCityReporter(scenario) { results.feed(it) }
        reporter.suiteStarted("bad.pbtest")
        reporter.testStarted("bad.pbtest cannot be read", 1)
        reporter.testFailed("bad.pbtest cannot be read", "line 1: Unknown step 'jump'")
        reporter.testFinished("bad.pbtest cannot be read")
        reporter.suiteFinished("bad.pbtest")
        val outcome = results.outcome()
        assertEquals(1, outcome.failed)
        assertNull(outcome.tests.single().platform)
        assertEquals("line 1: Unknown step 'jump'", outcome.tests.single().message)
    }

    @Test
    fun `what was printed is kept when no test ran`(@TempDir root: Path) {
        val results = UiTestResults(root)
        results.feed("pebble-tool is not installed.\n")
        assertEquals(listOf("pebble-tool is not installed."), results.outcome().output)
    }
}
