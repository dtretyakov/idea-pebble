package com.github.dtretyakov.pebble.agent

import com.github.dtretyakov.pebble.sdk.PebbleSdk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** On an excerpt of SDK 4.33.1's own headers. */
class PebbleApiTest {

    private val header = javaClass.getResource("/agent/pebble-excerpt.h")!!.readText()

    @Test
    fun `a function, with the comment above it as its documentation`() {
        val (declaration, documentation) = PebbleApi.declaration(header, "text_layer_set_text")!!
        assertEquals("void text_layer_set_text(TextLayer *text_layer, const char *text);", declaration)
        assertTrue(documentation!!.startsWith("Sets the pointer to the string where the TextLayer"))
        assertTrue(documentation.endsWith("@see text_layer_get_text"))
    }

    @Test
    fun `a typedef'd enum whole, and one of its values`() {
        val (declaration, documentation) = PebbleApi.declaration(header, "GTextAlignment")!!
        assertTrue(declaration.startsWith("typedef enum {") && declaration.endsWith("} GTextAlignment;"), declaration)
        assertTrue(documentation!!.startsWith("Text aligment controls"))
        assertEquals("  GTextAlignmentCenter," to "Aligns the text centered inside the drawing box", PebbleApi.declaration(header, "GTextAlignmentCenter"))
    }

    @Test
    fun `macros and opaque types`() {
        assertEquals("#define PBL_IF_ROUND_ELSE(if_true, if_false) (if_true)", PebbleApi.declaration(header, "PBL_IF_ROUND_ELSE")!!.first)
        assertEquals("typedef struct TextLayer TextLayer;", PebbleApi.declaration(header, "TextLayer")!!.first)
        assertNull(PebbleApi.declaration(header, "text_layer_get_text"), "only mentioned in a comment")
    }

    @Test
    fun `near names for one the SDK does not have`() {
        assertEquals("text_layer_set_text", PebbleApi.suggest("text_layer_set_txt", header).first())
        assertTrue("GTextAlignmentCenter" in PebbleApi.suggest("GTextAlignCenter", header))
    }

    @Test
    fun `per platform, declared, only a stub, or missing`(@TempDir temp: Path) {
        val sdk = PebbleSdk(temp, "4.33.1", null)
        fun header(platform: String, text: String) = sdk.includeDir(platform).createDirectories().resolve("pebble.h").writeText(text)
        header("emery", "//! Plays notes.\nbool speaker_play_notes(const SpeakerNote *notes, uint32_t num_notes, uint8_t volume);\n")
        header("aplite", "#define speaker_play_notes(...) (0)\n")
        header("basalt", "void something_else(void);\n")

        val found = PebbleApi.lookup(sdk, "speaker_play_notes()", listOf("aplite", "basalt", "emery"))
        assertEquals("speaker_play_notes", found.symbol)
        assertEquals(listOf("emery"), found.declarations.single().platforms)
        assertEquals("Plays notes.", found.declarations.single().documentation)
        assertEquals(listOf(ApiStub("aplite", "(0)")), found.stubbedOn)
        assertEquals(listOf("basalt"), found.missingOn)
        assertTrue(found.suggestions.isEmpty())
    }
}
