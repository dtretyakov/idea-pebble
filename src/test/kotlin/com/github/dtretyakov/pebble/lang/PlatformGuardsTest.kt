package com.github.dtretyakov.pebble.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlatformGuardsTest {

    private val names = setOf("speaker_play_tone", "light_set_color")

    private fun calls(text: String) = PlatformGuards.unguardedCalls(text.trimIndent(), names).map { it.name }

    @Test
    fun `an unguarded call is found, with its range`() {
        val text = "void f(void) {\n  speaker_play_tone(440, 100, 50);\n}"
        val call = PlatformGuards.unguardedCalls(text, names).single()
        assertEquals("speaker_play_tone", text.substring(call.start, call.end))
    }

    @Test
    fun `a PBL_ region guards every branch, and only up to its endif`() {
        val text = """
            #if defined(PBL_SPEAKER)
              speaker_play_tone(1, 2, 3);
            #else
              light_set_color(GColorRed);
            #endif
            #ifdef DEBUG
              light_set_color(GColorRed);
            #elif PBL_PLATFORM_EMERY
              speaker_play_tone(1, 2, 3);
            #endif
            speaker_play_tone(1, 2, 3);
        """
        assertEquals(listOf("light_set_color", "speaker_play_tone"), calls(text))
    }

    @Test
    fun `a region that does not name a platform guards nothing, nested or not`() {
        val text = """
            #if PBL_COLOR
            #  if DEBUG
                 light_set_color(GColorRed);
            #  endif
            #endif
            #ifndef NDEBUG
              speaker_play_tone(1, 2, 3);
            #endif
        """
        assertEquals(listOf("speaker_play_tone"), calls(text))
    }

    @Test
    fun `the arguments of a choice macro are guarded`() {
        val text = """
            int ok = PBL_IF_COLOR_ELSE(light_set_color(GColorRed), 0);
            int no = MIN(light_set_color(GColorRed), 0);
        """
        assertEquals(listOf("light_set_color"), calls(text))
    }

    @Test
    fun `comments, strings, directives and members are not calls`() {
        val text = """
            // speaker_play_tone(1, 2, 3);
            /* light_set_color(x);
               speaker_play_tone(1, 2, 3); */
            const char *s = "speaker_play_tone(1, 2, 3)";
            #define BEEP() speaker_play_tone(1, 2, \
                light_set_color(x))
            driver->speaker_play_tone(1, 2, 3);
            driver.light_set_color(x);
            int speaker_play_tone_count = 0;
            void (*f)(void) = speaker_play_tone;
        """
        assertEquals(emptyList<String>(), calls(text))
    }

    @Test
    fun `a call split from its parenthesis is still a call`() {
        assertEquals(listOf("light_set_color"), calls("light_set_color\n  (GColorRed);"))
    }
}
