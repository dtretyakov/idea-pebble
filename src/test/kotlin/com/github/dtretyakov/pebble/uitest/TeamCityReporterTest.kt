package com.github.dtretyakov.pebble.uitest

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.Path

class TeamCityReporterTest {

    @Test
    fun `a finished test says how long it took`() {
        val messages = mutableListOf<String>()
        val reporter = TeamCityReporter(Path("tests/ui/menu.pbtest")) { messages += it }
        reporter.testStarted("menu", 3)
        Thread.sleep(20)
        reporter.testFinished("menu")

        val duration = Regex("""duration='(\d+)'""").find(messages.last())?.groupValues?.get(1)?.toLong()
        assertTrue(duration != null && duration >= 20, messages.last())
    }
}
