package com.github.dtretyakov.pebble.sdk

/**
 * Whether the SDK's emulator can start on this machine, read from what `qemu-pebble --version`
 * says.
 *
 * The emulator is the one part of the SDK that links against system libraries — SDL, GLib and
 * pixman on Linux, Homebrew's libpng on a Mac — so it is the part that installs cleanly and then
 * fails at the first Run, with the loader's message in a console. Asking it for its version is
 * enough to make the loader say the same thing up front, and needs no `ldd` or `otool`: `otool`
 * is missing on a Mac without the command line tools, and asking for it opens Apple's installer.
 */
object EmulatorStart {

    sealed interface Verdict {
        data object Starts : Verdict

        /**
         * A library the loader could not find; on a Mac, with the Homebrew formula that has it and
         * the full path the emulator looks in.
         */
        data class MissingLibrary(val library: String, val homebrewFormula: String?, val path: String? = null) : Verdict

        /** The C library is older than the emulator was built for. */
        data class OldGlibc(val needed: String) : Verdict

        /** It did not start, for a reason not recognised; its last words. */
        data class Fails(val message: String) : Verdict
    }

    fun read(exitCode: Int, output: String): Verdict {
        Regex("""version `GLIBC_([\d.]+)' not found""").find(output)?.let { return Verdict.OldGlibc(it.groupValues[1]) }
        Regex("""error while loading shared libraries: ([^:\s]+)""").find(output)?.let {
            return Verdict.MissingLibrary(it.groupValues[1], null)
        }
        Regex("""Library not loaded: (\S+)""").find(output)?.let {
            val path = it.groupValues[1]
            val formula = Regex("""^/(?:opt/homebrew|usr/local)/opt/([^/]+)/""").find(path)?.groupValues?.get(1)
            return Verdict.MissingLibrary(path.substringAfterLast('/'), formula, path)
        }
        if (exitCode == 0) return Verdict.Starts
        return Verdict.Fails(output.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty())
    }
}
