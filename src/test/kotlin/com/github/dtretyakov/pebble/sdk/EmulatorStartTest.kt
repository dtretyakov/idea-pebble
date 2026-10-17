package com.github.dtretyakov.pebble.sdk

import com.github.dtretyakov.pebble.sdk.EmulatorStart.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EmulatorStartTest {

    @Test
    fun `a version is a start`() {
        assertEquals(Verdict.Starts, EmulatorStart.read(0, "QEMU emulator version 2.5.0 (pebble)\nCopyright (c) 2003-2008 Fabrice Bellard"))
    }

    @Test
    fun `Linux names the first library it could not load`() {
        val output = "qemu-pebble: error while loading shared libraries: libSDL2-2.0.so.0: cannot open shared object file: No such file or directory"
        assertEquals(Verdict.MissingLibrary("libSDL2-2.0.so.0", null), EmulatorStart.read(127, output))
    }

    @Test
    fun `an old glibc is its own verdict, not a missing library`() {
        val output = "qemu-pebble: /lib/x86_64-linux-gnu/libc.so.6: version `GLIBC_2.38' not found (required by qemu-pebble)"
        assertEquals(Verdict.OldGlibc("2.38"), EmulatorStart.read(1, output))
    }

    @Test
    fun `macOS names the library, and the Homebrew formula from its path`() {
        val output = """
            dyld[4242]: Library not loaded: /opt/homebrew/opt/libpng/lib/libpng16.16.dylib
              Referenced from: <UUID> /Users/me/Library/Application Support/Pebble SDK/SDKs/4.33.1/toolchain/bin/qemu-pebble
              Reason: tried: '/opt/homebrew/opt/libpng/lib/libpng16.16.dylib' (no such file)
        """.trimIndent()
        assertEquals(
            Verdict.MissingLibrary("libpng16.16.dylib", "libpng", "/opt/homebrew/opt/libpng/lib/libpng16.16.dylib"),
            EmulatorStart.read(134, output),
        )
    }

    @Test
    fun `anything else is a failure, in its own last words`() {
        assertEquals(Verdict.Fails("Killed: 9"), EmulatorStart.read(137, "something\nKilled: 9\n"))
    }
}
