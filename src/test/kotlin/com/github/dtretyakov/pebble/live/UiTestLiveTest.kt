package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.uitest.PebbleToolEmulator
import com.github.dtretyakov.pebble.uitest.ScenarioParser
import com.github.dtretyakov.pebble.uitest.ScenarioPaths
import com.github.dtretyakov.pebble.uitest.ScenarioRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** A scenario against emery, as the UI test run does it: recorded, passed, then failed by a change. */
class UiTestLiveTest {

    private fun app(root: Path, greeting: String) {
        root.resolve("src/c/fixture-c.c").writeText(
            """
            #include <pebble.h>

            static Window *s_window;
            static TextLayer *s_text;

            static void select_clicked(ClickRecognizerRef recognizer, void *context) {
              text_layer_set_text(s_text, "Pressed");
            }

            static void click_config(void *context) {
              window_single_click_subscribe(BUTTON_ID_SELECT, select_clicked);
            }

            int main(void) {
              s_window = window_create();
              window_set_click_config_provider(s_window, click_config);
              s_text = text_layer_create(GRect(0, 80, 200, 40));
              text_layer_set_text_alignment(s_text, GTextAlignmentCenter);
              text_layer_set_font(s_text, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
              text_layer_set_text(s_text, "$greeting");
              layer_add_child(window_get_root_layer(s_window), text_layer_get_layer(s_text));
              window_stack_push(s_window, false);
              app_event_loop();
            }
            """.trimIndent(),
        )
        assertEquals(0, LiveSdk.run(root, "build").exitCode)
    }

    @Test
    fun `a scenario records its screens, passes on them, and fails when the app changes`() {
        val tool = LiveSdk.tool()
        val root = LiveSdk.fixture("fixture-c")
        app(root, "Hello")
        LiveSdk.install(root, "--emulator", "emery", "--vnc")

        val scenario = root.resolve("tests/ui/greeting.pbtest").apply { parent.createDirectories() }
        scenario.writeText("platforms emery\ninstall\nwait 1500\ntap 100 100\nexpect hello\npress select\nwait 500\nexpect pressed\n")
        val paths = ScenarioPaths(scenario, root)
        val parsed = ScenarioParser.parse(Files.readString(scenario))
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())

        fun run(): List<String> {
            val events = mutableListOf<String>()
            val output = StringBuilder()
            val reporter = object : ScenarioRunner.Reporter {
                override fun suiteStarted(name: String) = Unit
                override fun suiteFinished(name: String) = Unit
                override fun testStarted(name: String, line: Int) = Unit
                override fun output(text: String) { output.append(text) }
                override fun testFailed(name: String, message: String, expected: Path?, actual: Path?) { events += "failed $name: $message" }
                override fun testFinished(name: String) { events += "finished $name" }
            }
            ScenarioRunner(PebbleToolEmulator(tool, root), reporter, paths.references, paths.results).run(parsed, listOf("emery"))
            return events.also { println(output) }
        }

        assertEquals(listOf("finished hello", "finished pressed"), run())
        assertTrue(Files.exists(paths.reference("emery", "hello")))
        assertTrue(Files.exists(paths.reference("emery", "pressed")))

        assertEquals(listOf("finished hello", "finished pressed"), run(), "the same app should give the same screens")

        app(root, "Goodbye")
        val changed = run()
        assertTrue(changed.first().startsWith("failed hello: "), changed.toString())
        assertTrue(Files.exists(paths.results.resolve("emery/hello.diff.png")))
        assertEquals("finished pressed", changed.last(), "the second screen is the same again")
    }
}
