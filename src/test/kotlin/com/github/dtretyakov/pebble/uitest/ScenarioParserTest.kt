package com.github.dtretyakov.pebble.uitest

import com.github.dtretyakov.pebble.uitest.Scenario.Step
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScenarioParserTest {

    @Test
    fun `steps become pebble-tool commands, touches and expectations`() {
        val scenario = ScenarioParser.parse(
            """
            # the menu
            platforms emery basalt
            install
            time 9:05
            press select
            press back 800
            wait 500   # let it settle
            tap 100 120
            battery 20 charging
            bluetooth off
            quick-view on
            motion tilt-left
            shake y-
            expect menu
            """.trimIndent(),
        )
        assertEquals(emptyList<Scenario.Problem>(), scenario.problems)
        assertEquals(listOf("emery", "basalt"), scenario.platforms)
        assertEquals(
            listOf(
                Step.Install(3),
                Step.Pebble(4, listOf("emu-set-time", "09:05:00")),
                Step.Pebble(5, listOf("emu-button", "click", "select", "--duration", "100")),
                Step.Pebble(6, listOf("emu-button", "click", "back", "--duration", "800")),
                Step.Wait(7, 500),
                Step.Tap(8, 100, 120),
                Step.Pebble(9, listOf("emu-battery", "--percent", "20", "--charging")),
                Step.Pebble(10, listOf("emu-bt-connection", "--connected", "no")),
                Step.Pebble(11, listOf("emu-set-timeline-quick-view", "on")),
                Step.Pebble(12, listOf("emu-accel", "tilt-left")),
                Step.Pebble(13, listOf("emu-tap", "--direction", "y-")),
                Step.Expect(14, "menu"),
            ),
            scenario.steps,
        )
    }

    @Test
    fun `mistakes are reported by line`() {
        val scenario = ScenarioParser.parse("install\npress middle\nwait soon\nexpect\nfly\nplatforms emery\nexpect home")
        assertEquals(listOf(2, 3, 4, 5, 6), scenario.problems.map { it.line })
        assertEquals("'press' takes a button: back, up, select or down", scenario.problems[0].message)
        assertEquals("'platforms' comes before the steps", scenario.problems[4].message)
    }

    @Test
    fun `a scenario without an expectation tests nothing`() {
        assertEquals(listOf("A scenario expects at least one screen"), ScenarioParser.parse("install\npress up").problems.map { it.message })
        assertNull(ScenarioParser.parse("expect home").platforms)
    }
}
