package com.github.dtretyakov.pebble.build

import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * `pebble publish`, as the IDE's terminal runs it: pebble-tool's own way to the Pebble appstore —
 * the developer account, a build, the listing's details for a new app, the screenshots (captured
 * from the emulators, or files it is given) and the upload. It asks as it goes, so it runs where
 * the user can answer; the plugin's part is the release check before it and the login it needs.
 */
object PebblePublish {

    /**
     * The terminal's command: `pebble publish`, after `pebble login` when there is no login yet.
     * `PYTHONWARNINGS=ignore` for the screenful of libpebble2 warnings Python 3.12 and later print.
     */
    fun command(tool: Path, loggedIn: Boolean): String = command(tool.toString(), loggedIn)

    /** [tool] as the terminal's shell spells it: under WSL, the distribution's path. */
    fun command(tool: String, loggedIn: Boolean): String {
        val pebble = "PYTHONWARNINGS=ignore ${quote(tool)}"
        return if (loggedIn) "$pebble publish" else "$pebble login && $pebble publish"
    }

    /**
     * Whether pebble-tool has a login to publish with: the credentials `pebble login` stores under
     * its data directory. One that has expired is renewed by pebble-tool itself.
     */
    fun loggedIn(dataDir: Path?): Boolean = dataDir?.resolve("oauth_firebase/firebase_oauth_storage.json")?.isRegularFile() == true

    /** [text] as one word to a POSIX shell. */
    internal fun quote(text: String): String =
        if (text.matches(PLAIN)) text else "'" + text.replace("'", "'\\''") + "'"

    private val PLAIN = Regex("""[A-Za-z0-9_./:@%+=-]+""")
}
