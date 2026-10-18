package com.github.dtretyakov.pebble.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class PebblePublishTest {

    @Test
    fun `publish, after a login when there is none`() {
        // The command is for a POSIX shell: on Windows pebble-tool lives in WSL, and Publish is not offered.
        assumeTrue(File.separatorChar == '/', "a POSIX path to pebble-tool")
        val tool = Path.of("/Users/me/.local/bin/pebble")
        assertEquals("PYTHONWARNINGS=ignore /Users/me/.local/bin/pebble publish", PebblePublish.command(tool, loggedIn = true))
        assertEquals(
            "PYTHONWARNINGS=ignore /Users/me/.local/bin/pebble login && PYTHONWARNINGS=ignore /Users/me/.local/bin/pebble publish",
            PebblePublish.command(tool, loggedIn = false),
        )
    }

    @Test
    fun `a path the shell would split is quoted`() {
        assertEquals("'/Users/me/Pebble SDK/pebble'", PebblePublish.quote("/Users/me/Pebble SDK/pebble"))
        assertEquals("'it'\\''s'", PebblePublish.quote("it's"))
    }

    @Test
    fun `logged in is pebble login's credentials under the data directory`(@TempDir dataDir: Path) {
        assertFalse(PebblePublish.loggedIn(null))
        assertFalse(PebblePublish.loggedIn(dataDir))
        dataDir.resolve("oauth_firebase").createDirectories().resolve("firebase_oauth_storage.json").writeText("{}")
        assertTrue(PebblePublish.loggedIn(dataDir))
    }
}
