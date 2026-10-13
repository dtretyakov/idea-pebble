package com.github.dtretyakov.pebble.build

import com.github.dtretyakov.pebble.build.BuildMessage.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** Against output captured from `pebble build` with pebble-tool 5.0.40 and SDK 4.33.1. */
class BuildOutputParserTest {

    private val root = Path.of("/work/app")

    @Test
    fun `gcc diagnostics resolve against build and take the platform waf last named`() {
        val parser = BuildOutputParser(root)
        val messages = """
            [ 4/16] Creating gabbro | resource_ball:  -> build/gabbro/system_resources.resball
            [13/16] Compiling gabbro | c: src/c/fixture-c.c -> build/src/c/fixture-c.c.51.o
            ../src/c/fixture-c.c: In function 'main':
            ../src/c/fixture-c.c:6:3: error: implicit declaration of function 'undefined_call' [-Wimplicit-function-declaration]
                6 |   undefined_call();
                  |   ^~~~~~~~~~~~~~
            ../src/c/fixture-c.c:7:7: warning: unused variable 'x' [-Wunused-variable]
            ../src/c/fixture-c.c:3:12: note: declared here
            Build failed
             -> task in 'gabbro/pebble-app.elf' failed with exit status 1 (run with -v to display more information)
        """.trimIndent().lines().mapNotNull { parser.parseLine(it) }

        assertEquals(2, messages.size)
        assertEquals(
            BuildMessage(
                Severity.ERROR, root.resolve("src/c/fixture-c.c"), 6, 3,
                "implicit declaration of function 'undefined_call' [-Wimplicit-function-declaration]", "gabbro",
            ),
            messages[0],
        )
        assertEquals(Severity.WARNING, messages[1].severity)
        assertEquals(13 to 16, parser.progress)
    }

    @Test
    fun `steps shared by every platform keep the last platform`() {
        val parser = BuildOutputParser(root)
        parser.parseLine("[ 9/110] Compiling basalt | c: src/c/a.c -> build/src/c/a.c.1.o")
        parser.parseLine("[1/3] Creating message_key_json:  -> build/js/message_keys.json")
        assertEquals("basalt", parser.parseLine("../src/c/a.c:1:1: error: boom")?.platform)
    }

    @Test
    fun `fatal errors and linker errors are errors`() {
        val parser = BuildOutputParser(root)
        assertEquals(
            Severity.ERROR,
            parser.parseLine("../src/c/a.c:1:10: fatal error: missing.h: No such file or directory")?.severity,
        )
        val linker = parser.parseLine("../src/c/a.c:6:(.text.main+0x4): undefined reference to `foo'")
        assertEquals(root.resolve("src/c/a.c"), linker?.file)
        assertEquals(6, linker?.line)
        assertNull(linker?.column)
    }

    @Test
    fun `everything else is not a diagnostic`() {
        val parser = BuildOutputParser(root)
        listOf(
            "'configure' finished successfully (0.146s)",
            "Waf: Entering directory `/work/app/build'",
            "Checking for program 'gcc, cc'           : arm-none-eabi-gcc",
            "    6 |   undefined_call();",
            "[22:15:49] main.c:3> err 1",
        ).forEach { assertNull(parser.parseLine(it), it) }
    }
}
