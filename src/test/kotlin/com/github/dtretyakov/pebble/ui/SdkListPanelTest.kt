package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.ui.SdkListPanel.Detail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SdkListPanelTest {

    @Test
    fun `the Platforms column names what an SDK builds for, and in red what the project needs that it lacks`() {
        assertEquals(
            listOf(Detail("aplite, basalt, chalk, diorite"), Detail("no emery, gabbro", problem = true)),
            SdkListPanel.platformsLine(listOf("aplite", "basalt", "chalk", "diorite"), missing = listOf("emery", "gabbro")),
        )
        assertEquals(listOf(Detail("emery, gabbro")), SdkListPanel.platformsLine(listOf("emery", "gabbro"), missing = emptyList()))
    }

    @Test
    fun `sizes are said the way one would say them`() {
        assertEquals("763 MB", SdkListPanel.size(800_000_000))
        assertEquals("1.4 GB", SdkListPanel.size(1_500_000_000))
    }
}
