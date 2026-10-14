package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.agent.PebbleApi
import com.github.dtretyakov.pebble.sdk.PebbleDataDir
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `pebble_api` against the installed SDK's own headers and typings. */
class ApiLiveTest {

    private val sdk by lazy {
        LiveSdk.tool()
        PebbleSdks.scan(PebbleDataDir.locate()).current!!
    }
    private val all by lazy { sdk.platforms() }

    @Test
    fun `a function on every platform, with the SDK's documentation`() {
        val found = PebbleApi.lookup(sdk, "text_layer_set_text", all)
        val declaration = found.declarations.single()
        assertEquals("void text_layer_set_text(TextLayer *text_layer, const char *text);", declaration.declaration)
        assertTrue(declaration.documentation!!.startsWith("Sets the pointer to the string"), declaration.documentation)
        assertEquals(all.sorted(), declaration.platforms.sorted())
        assertTrue(found.missingOn.isEmpty() && found.stubbedOn.isEmpty())
    }

    @Test
    fun `a function some platforms only stub out`() {
        val found = PebbleApi.lookup(sdk, "speaker_play_notes", all)
        assertTrue(found.declarations.single().declaration.startsWith("bool speaker_play_notes("), found.toString())
        val stubbed = found.stubbedOn.map { it.platform }
        assertTrue("aplite" in stubbed && "emery" !in stubbed, found.toString())
        assertEquals("(0)", found.stubbedOn.first().expansion)
    }

    @Test
    fun `types, enum values and macros`() {
        val alignment = PebbleApi.lookup(sdk, "GTextAlignment", listOf("emery")).declarations.single().declaration
        assertTrue(alignment.contains("GTextAlignmentCenter") && alignment.trimEnd().endsWith("} GTextAlignment;"), alignment)
        val center = PebbleApi.lookup(sdk, "GTextAlignmentCenter", listOf("emery")).declarations.single()
        assertEquals("GTextAlignmentCenter,", center.declaration.trim())
        assertEquals("Aligns the text centered inside the drawing box", center.documentation)
        val round = PebbleApi.lookup(sdk, "PBL_IF_ROUND_ELSE", listOf("chalk", "basalt")).declarations
        assertTrue(round.isNotEmpty() && round.all { it.declaration.startsWith("#define PBL_IF_ROUND_ELSE") }, round.toString())
    }

    @Test
    fun `a name the SDK does not have gets the nearest real ones`() {
        val found = PebbleApi.lookup(sdk, "text_layer_set_txt", all)
        assertTrue(found.declarations.isEmpty())
        assertTrue("text_layer_set_text" in found.suggestions, found.suggestions.toString())
    }

    @Test
    fun `Alloy names come from the SDK's typings, with their module`() {
        val found = PebbleApi.lookup(sdk, "Poco", listOf("emery"))
        val alloy = found.declarations.filter { it.language == "alloy" }
        val poco = alloy.single { it.declaration == "const Poco: PocoConstructor" }
        assertEquals("commodetto/Poco", poco.module)
        assertEquals("import Poco from \"commodetto/Poco\"", poco.import)
        assertTrue(alloy.none { it.declaration.startsWith("declare module") }, alloy.toString())
    }
}
