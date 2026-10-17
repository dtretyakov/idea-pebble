package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.PebbleEnvironment.Concern
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Fix
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Impact
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Item
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Status
import com.github.dtretyakov.pebble.sdk.HostOs
import com.github.dtretyakov.pebble.sdk.HostPackages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What a terminal fix says, and where the command it runs is shown. */
class EnvironmentFixesTest {

    private fun item(command: String) = Item(
        Concern.EMULATOR, "Emulator", Status.MISSING, "The emulator cannot start: it needs Homebrew's `libpng`.",
        Fix.RUN_IN_TERMINAL, Impact.RUN, command,
    )

    @Test
    fun `a short command without sudo is the button, and is not repeated in the text`() {
        val item = item("/opt/homebrew/bin/brew install libpng")
        assertEquals("Run brew install libpng", EnvironmentFixes.label(item))
        assertFalse("brew install" in EnvironmentFixes.text(item))
    }

    @Test
    fun `a sudo command is shown in the text, to be read before the password is typed`() {
        val item = item("sudo apt install libsdl2-2.0-0 libglib2.0-0 libpixman-1-0 zlib1g libsndio7.0")
        assertEquals("Run in Terminal", EnvironmentFixes.label(item))
        assertTrue("<code>sudo apt install" in EnvironmentFixes.text(item))
    }

    @Test
    fun `installing Homebrew first is said in words, not as the script it runs`() {
        val command = HostPackages(HostOs.MAC, null, null).emulatorLibraries("libpng")!!
        val item = item(command)
        assertEquals("Install Homebrew and libpng", EnvironmentFixes.label(item))
        assertFalse("curl" in EnvironmentFixes.text(item))
    }
}
