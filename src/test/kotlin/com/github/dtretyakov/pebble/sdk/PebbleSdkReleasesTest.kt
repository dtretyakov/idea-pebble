package com.github.dtretyakov.pebble.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PebbleSdkReleasesTest {

    @Test
    fun `the newest of what is installed and what is offered`() {
        assertEquals("4.33.1", PebbleSdkReleases.latest(listOf("4.33.1"), listOf("4.4", "4.17", "4.9.169")))
        assertEquals("4.34", PebbleSdkReleases.latest(listOf("4.33.1"), listOf("4.34", "4.17")))
        assertNull(PebbleSdkReleases.latest(emptyList(), emptyList()))
    }

    @Test
    fun `the release notes' address is read out of the server's sentence`() {
        // What sdk.repebble.com/v1/files/sdk-core/4.17 answered, October 2026.
        val description = """{"channel":"","release_notes":"Check out the release notes at https://developer.repebble.com/sdk/changelogs/4.17/index.html","requirements":[],"url":"https://sdk.repebble.com/releases/4.17/sdk-core.tar.gz","version":"4.17"}"""
        assertEquals("https://developer.repebble.com/sdk/changelogs/4.17/index.html", PebbleSdkReleases.notesUrl(description))
        assertNull(PebbleSdkReleases.notesUrl("""{"version":"4.4"}"""))
        assertNull(PebbleSdkReleases.notesUrl("not json"))
    }
}
