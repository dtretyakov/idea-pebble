package com.github.dtretyakov.pebble.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** As text, so these hold on every system the build runs on, Windows or not. */
class WslPathsTest {

    @Test
    fun `a distribution's share is its Linux path, in either spelling of the share`() {
        assertEquals("/home/me/app", WslPaths.toLinux("""\\wsl.localhost\Ubuntu\home\me\app""", "Ubuntu"))
        assertEquals("/home/me/app", WslPaths.toLinux("""\\wsl$\Ubuntu\home\me\app\""", "Ubuntu"))
        assertEquals("/home/me/app", WslPaths.toLinux("//wsl.localhost/Ubuntu/home/me/app", "ubuntu"))
        assertEquals("/", WslPaths.toLinux("""\\wsl.localhost\Ubuntu""", "Ubuntu"))
    }

    @Test
    fun `another distribution's share is out of reach`() {
        assertNull(WslPaths.toLinux("""\\wsl.localhost\Debian\home\me\app""", "Ubuntu"))
    }

    @Test
    fun `a Windows drive is under the mount root`() {
        assertEquals("/mnt/c/Users/me/My App", WslPaths.toLinux("""C:\Users\me\My App""", "Ubuntu"))
        assertEquals("/mnt/d", WslPaths.toLinux("""D:\""", "Ubuntu"))
        assertEquals("/windows/c/x", WslPaths.toLinux("""C:\x""", "Ubuntu", mountRoot = "/windows/"))
        assertNull(WslPaths.toLinux("""\\server\share\x""", "Ubuntu"))
        assertNull(WslPaths.toLinux("relative\\path", "Ubuntu"))
    }

    @Test
    fun `and back`() {
        assertEquals("""\\wsl.localhost\Ubuntu\home\me\app""", WslPaths.toWindows("/home/me/app", "Ubuntu"))
        assertEquals("""\\wsl$\Ubuntu\tmp\pb-emulator.json""", WslPaths.toWindows("/tmp/pb-emulator.json", "Ubuntu", """\\wsl$\"""))
        assertEquals("""C:\Users\me\app""", WslPaths.toWindows("/mnt/c/Users/me/app", "Ubuntu"))
        assertEquals("""C:\""", WslPaths.toWindows("/mnt/c", "Ubuntu"))
        // A directory under /mnt that is no drive is the distribution's own.
        assertEquals("""\\wsl.localhost\Ubuntu\mnt\wslg\runtime""", WslPaths.toWindows("/mnt/wslg/runtime", "Ubuntu"))
        assertNull(WslPaths.toWindows("relative/path", "Ubuntu"))
    }

    @Test
    fun `a share names its distribution and the prefix it was opened by`() {
        assertEquals(WslPaths.Unc("""\\wsl$\""", "Ubuntu-24.04", "/home"), WslPaths.parseUnc("""\\wsl$\Ubuntu-24.04\home"""))
        assertEquals("Ubuntu", WslPaths.distributionOf("//wsl.localhost/Ubuntu/home/me"))
        assertNull(WslPaths.distributionOf("""C:\Users"""))
    }
}
