package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.sdk.PebbleSdk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class AlloyGlobalsTest {

    @TempDir
    lateinit var dir: Path

    private val sdk get() = PebbleSdk(dir.resolve("SDKs/4.33.1"), "4.33.1", "release")
    private val moddable get() = sdk.toolchain.resolve("moddable")

    private fun typing(relative: String): Path =
        moddable.resolve("$relative.d.ts").also { it.parent.createDirectories(); it.writeText("") }

    @Test
    fun `the build's types are read, each as its d ts, and those not on disk left out`() {
        val global = typing("typings/pebble/global")
        val root = dir.resolve("app")
        root.resolve("build/mods/emery/mcrun/tmp/pebble/debug/embeddedjs/files").createDirectories()
            .resolve("tsconfig-js.json")
            .writeText("""{"compilerOptions": {"types": ["${moddable.resolve("typings/pebble/global")}", "${moddable.resolve("typings/gone")}"]}}""")
        assertEquals(listOf(global), AlloyGlobals.files(root, "emery", sdk))
    }

    @Test
    fun `before a first build, the SDK's own list`() {
        val global = typing("typings/pebble/global")
        val xs = typing("xs/includes/xs")
        assertEquals(listOf(global, xs), AlloyGlobals.files(dir.resolve("never-built"), "emery", sdk))
        assertEquals(emptyList<Path>(), AlloyGlobals.files(dir.resolve("never-built"), "emery", null))
    }

    @Test
    fun `a tsconfig without types has none`() {
        assertNull(AlloyGlobals.typesIn("""{"compilerOptions": {"strict": true}}"""))
        assertEquals(listOf("a", "b"), AlloyGlobals.typesIn("""{"compilerOptions": {"types": ["a", "b"]}}"""))
    }
}
