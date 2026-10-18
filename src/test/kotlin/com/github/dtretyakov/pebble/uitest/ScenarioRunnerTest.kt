package com.github.dtretyakov.pebble.uitest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.invariantSeparatorsPathString

/** The runner against a stand-in emulator whose screen is whatever colour the test says. */
class ScenarioRunnerTest {

    private val root = Files.createTempDirectory("scenario")
    private val references = root.resolve("tests/ui/screenshots")
    private val results = root.resolve("build/ui-tests/menu")
    private val events = mutableListOf<String>()
    private val commands = mutableListOf<String>()
    private var screen = 0x000000
    private var failing: String? = null

    private val emulator = object : ScenarioRunner.Emulator {
        override fun pebble(platform: String, arguments: List<String>): Pair<Int, String> {
            commands += "$platform ${arguments.joinToString(" ")}"
            return if (arguments.first() == failing) 1 to "failed\n" else 0 to ""
        }

        override fun tap(platform: String, x: Int, y: Int) {
            commands += "$platform tap $x $y"
        }

        override fun screenshot(platform: String, file: Path): Pair<Int, String> {
            val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
            for (x in 0 until 4) image.setRGB(x, 0, screen)
            ImageIO.write(image, "png", file.toFile())
            return 0 to ""
        }

        override fun sleep(millis: Long) {
            commands += "sleep $millis"
        }
    }

    private val reporter = object : ScenarioRunner.Reporter {
        override fun suiteStarted(name: String) { events += "suite $name" }
        override fun suiteFinished(name: String) { events += "/suite $name" }
        override fun testStarted(name: String, line: Int) { events += "test $name:$line" }
        override fun output(text: String) = Unit
        override fun testFailed(name: String, message: String, expected: Path?, actual: Path?) {
            events += "failed $name: $message" + (if (expected != null) " [${references.relativize(expected).invariantSeparatorsPathString} vs ${results.relativize(actual!!).invariantSeparatorsPathString}]" else "")
        }
        override fun testFinished(name: String) { events += "/test $name" }
    }

    private val scenario = ScenarioParser.parse("install\npress select\nexpect menu\ntap 10 20\nwait 300\nexpect detail")

    private fun run() {
        events.clear()
        ScenarioRunner(emulator, reporter, references, results).run(scenario, listOf("emery"))
    }

    @Test
    fun `the first run records the references, the next compares with them`() {
        run()
        assertEquals(listOf("suite emery", "test menu:3", "/test menu", "test detail:6", "/test detail", "/suite emery"), events)
        assertTrue(Files.exists(references.resolve("emery/menu.png")))
        assertEquals(listOf("emery install", "emery emu-button click select --duration 100", "emery tap 10 20", "sleep 300"), commands)

        run()
        assertEquals(6, events.size, events.toString())

        screen = 0xFF0000
        run()
        assertTrue(events.any { it.startsWith("failed menu: 4 of 16 pixels differ") && it.endsWith("[emery/menu.png vs emery/menu.png]") }, events.toString())
        assertTrue(Files.exists(results.resolve("emery/menu.diff.png")))
    }

    @Test
    fun `a step that fails fails its test and every one after it`() {
        failing = "emu-button"
        run()
        assertEquals(
            listOf(
                "suite emery", "test menu:3", "failed menu: Line 2 failed: pebble exited with 1", "/test menu",
                "test detail:6", "failed detail: An earlier step failed: line 2, pebble exited with 1", "/test detail", "/suite emery",
            ),
            events,
        )
    }
}
