package com.github.dtretyakov.pebble.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class TypeScriptConfigTest {

    private fun generated(root: Path, platform: String, variant: String, time: Long): Path =
        root.resolve("build/mods/$platform/mcrun/tmp/pebble/$variant/embeddedjs/files").createDirectories().resolve("tsconfig-js.json")
            .also { it.writeText("{}"); Files.setLastModifiedTime(it, FileTime.fromMillis(time)) }

    @Test
    fun `it extends the active platform's settings from the latest build`(@TempDir root: Path) {
        assertNull(TypeScriptConfig.generated(root, "emery"))
        assertFalse(TypeScriptConfig.update(root, "emery"), "nothing built yet")
        generated(root, "emery", "debug", 1000)
        generated(root, "emery", "release", 2000)
        generated(root, "gabbro", "release", 3000)
        assertTrue(TypeScriptConfig.update(root, "emery"))
        val text = root.resolve("tsconfig.json").readText()
        assertTrue(text.contains("\"extends\": \"./build/mods/emery/mcrun/tmp/pebble/release/embeddedjs/files/tsconfig-js.json\""), text)
        assertTrue(text.contains("\"include\": [\"src/embeddedjs/**/*\"]"))
        assertTrue(TypeScriptConfig.isOurs(root.resolve("tsconfig.json")))
    }

    @Test
    fun `a tsconfig json written before the plugin had its name is still its own`(@TempDir root: Path) {
        generated(root, "emery", "release", 1000)
        root.resolve("tsconfig.json").writeText("""{"//": "Written by the Pebble plugin after each build.", "extends": "./old.json"}""")
        assertTrue(TypeScriptConfig.update(root, "emery"))
        assertTrue(root.resolve("tsconfig.json").readText().contains("Written by Develop for Pebble"))
    }

    @Test
    fun `a tsconfig json of the user's own is left alone`(@TempDir root: Path) {
        generated(root, "emery", "release", 1000)
        val mine = """{"compilerOptions": {"strict": true}}"""
        root.resolve("tsconfig.json").writeText(mine)
        assertFalse(TypeScriptConfig.update(root, "emery"))
        assertEquals(mine, root.resolve("tsconfig.json").readText())
    }
}
