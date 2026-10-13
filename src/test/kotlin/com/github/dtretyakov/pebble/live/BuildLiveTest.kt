package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.build.BuildMessage
import com.github.dtretyakov.pebble.build.BuildOutputParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText

class BuildLiveTest {

    @Test
    fun `the C fixture builds into a bundle`() {
        val root = LiveSdk.fixture("fixture-c")
        val output = LiveSdk.run(root, "build")
        assertEquals(0, output.exitCode, output.text)
        assertTrue(Files.exists(root.resolve("build/fixture-c.pbw")), output.text)
    }

    @Test
    fun `a compile error is read with its file, line and platform`() {
        val root = LiveSdk.fixture("fixture-c")
        root.resolve("src/c/fixture-c.c").writeText("#include <pebble.h>\n\nint main(void) {\n  nope();\n}\n")

        val output = LiveSdk.run(root, "build")
        val parser = BuildOutputParser(root)
        val errors = output.text.lines().mapNotNull { parser.parseLine(it) }
            .filter { it.severity == BuildMessage.Severity.ERROR }

        assertTrue(output.exitCode != 0)
        val error = errors.firstOrNull() ?: error("no error read from:\n${output.text}")
        assertEquals(root.resolve("src/c/fixture-c.c"), error.file)
        assertEquals(4, error.line)
        assertTrue(error.platform != null, "no platform for $error")
    }
}
