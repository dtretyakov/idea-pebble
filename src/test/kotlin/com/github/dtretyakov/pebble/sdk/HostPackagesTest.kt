package com.github.dtretyakov.pebble.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class HostPackagesTest {

    @Test
    fun `distributions are recognised by family`() {
        assertEquals(HostPackages.Distro.DEBIAN, HostPackages.distro("NAME=\"Ubuntu\"\nID=ubuntu\nID_LIKE=debian\n"))
        assertEquals(HostPackages.Distro.DEBIAN, HostPackages.distro("ID=linuxmint\nID_LIKE=\"ubuntu debian\"\n"))
        assertEquals(HostPackages.Distro.FEDORA, HostPackages.distro("ID=fedora\n"))
        assertEquals(HostPackages.Distro.FEDORA, HostPackages.distro("ID=\"rocky\"\nID_LIKE=\"rhel centos fedora\"\n"))
        assertNull(HostPackages.distro("ID=arch\n"))
    }

    @Test
    fun `the emulator's libraries are the SDK's whole list on Linux`() {
        assertEquals(
            "sudo apt install libsdl2-2.0-0 libglib2.0-0 libpixman-1-0 zlib1g libsndio7.0",
            HostPackages(HostOs.LINUX, null, HostPackages.Distro.DEBIAN).emulatorLibraries(null),
        )
        assertEquals("sudo dnf install SDL2 glib2 pixman zlib sndio", HostPackages(HostOs.LINUX, null, HostPackages.Distro.FEDORA).emulatorLibraries(null))
        assertNull(HostPackages(HostOs.LINUX, null, null).emulatorLibraries(null))
    }

    @Test
    fun `Homebrew serves the emulator's path only from the prefix it looks in`() {
        val libpng = "/opt/homebrew/opt/libpng/lib/libpng16.16.dylib"
        assertTrue(HostPackages(HostOs.MAC, Path.of("/opt/homebrew/bin/brew"), null).homebrewServes(libpng))
        assertTrue(HostPackages(HostOs.MAC, null, null, appleSilicon = true).homebrewServes(libpng))
        // An Intel Mac: Homebrew is in /usr/local, whether installed already or about to be.
        assertFalse(HostPackages(HostOs.MAC, Path.of("/usr/local/bin/brew"), null, appleSilicon = false).homebrewServes(libpng))
        assertFalse(HostPackages(HostOs.MAC, null, null, appleSilicon = false).homebrewServes(libpng))
    }

    @Test
    fun `on a Mac the formula comes from Homebrew, and Homebrew first when there is none`() {
        val brew = Path.of("/opt/homebrew/bin/brew")
        assertEquals("/opt/homebrew/bin/brew install libpng", HostPackages(HostOs.MAC, brew, null).emulatorLibraries("libpng"))

        val withoutBrew = HostPackages(HostOs.MAC, null, null).emulatorLibraries("libpng")!!
        assertTrue(withoutBrew.contains(HostPackages.HOMEBREW_INSTALLER) && withoutBrew.endsWith("brew install libpng"), withoutBrew)
        assertNull(HostPackages(HostOs.MAC, null, null).node())
    }
}
