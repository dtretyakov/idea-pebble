package com.github.dtretyakov.pebble.sdk

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/**
 * How this machine installs system software, for the steps the plugin cannot take itself.
 *
 * The SDK's own prerequisites — Node.js, the emulator's libraries, clangd — come from Homebrew on
 * a Mac and from the distribution's package manager on Linux. The second needs `sudo`, and the
 * first a decision about what goes on the machine; both are the user's to run. What the plugin can
 * do is name the exact command, so that running it is one click into a terminal rather than a
 * search for which package holds `libpixman-1.so.0` on this distribution.
 */
class HostPackages(
    val os: HostOs,
    /** `brew`, when there is one. */
    val brew: Path?,
    /** The distribution's family, from `/etc/os-release`. */
    val distro: Distro?,
    /** On a Mac: Apple silicon, where Homebrew lives in `/opt/homebrew` rather than `/usr/local`. */
    val appleSilicon: Boolean = true,
    /** A WSL distribution, whose fresh installs lack the compilers the SDK's guide installs there first. */
    val wsl: Boolean = false,
) {

    /**
     * Everything the SDK needs from the system, in one command, as its installation guide gives it:
     * Node.js and npm — without which `pebble sdk install` itself fails, since it runs npm — and the
     * emulator's libraries, which nothing can check before there is an emulator to ask. One
     * password, then Set Up needs nothing more; where there is no package manager to name, the
     * Node.js command alone, or null.
     */
    fun sdkPrerequisites(withClangd: Boolean = false): String? = when {
        // A fresh WSL distribution has had no `apt update`, and finds none of these without one;
        // sudo asks for the password once for both.
        os == HostOs.LINUX && distro == Distro.DEBIAN ->
            (if (wsl) "sudo apt update && " else "") +
                "sudo apt install ${if (wsl) "build-essential " else ""}nodejs npm libsdl2-2.0-0 libglib2.0-0 libpixman-1-0 zlib1g libsndio7.0" +
                if (withClangd) " clangd" else ""
        os == HostOs.LINUX && distro == Distro.FEDORA ->
            "sudo dnf install nodejs npm SDL2 glib2 pixman zlib sndio" + if (withClangd) " clang-tools-extra" else ""
        else -> node()
    }

    /**
     * Whether `brew install` here would put a library where [path] says the emulator looks for it.
     *
     * The SDK's emulator for macOS loads libpng from `/opt/homebrew/opt/libpng/…`, a path fixed
     * when it was built: Homebrew's prefix on Apple silicon. Homebrew on an Intel Mac installs into
     * `/usr/local`, and the emulator would not find it there — so offering `brew install libpng`
     * would be offering a command that succeeds and changes nothing.
     */
    fun homebrewServes(path: String): Boolean {
        val prefix = brew?.parent?.parent?.invariantSeparatorsPathString ?: if (appleSilicon) "/opt/homebrew" else "/usr/local"
        return path.startsWith("$prefix/")
    }

    enum class Distro { DEBIAN, FEDORA }

    /** What installs Node.js and npm here, or null when there is no command to give. */
    fun node(): String? = when {
        os == HostOs.MAC && brew != null -> "${brew.invariantSeparatorsPathString} install node"
        os == HostOs.LINUX && distro == Distro.DEBIAN -> "sudo apt install nodejs npm"
        os == HostOs.LINUX && distro == Distro.FEDORA -> "sudo dnf install nodejs"
        else -> null
    }

    /** What installs clangd here. Homebrew's is in its `llvm` formula, which [Clangd] looks in. */
    fun clangd(): String? = when {
        os == HostOs.MAC && brew != null -> "${brew.invariantSeparatorsPathString} install llvm"
        os == HostOs.LINUX && distro == Distro.DEBIAN -> "sudo apt install clangd"
        os == HostOs.LINUX && distro == Distro.FEDORA -> "sudo dnf install clang-tools-extra"
        else -> null
    }

    /**
     * What installs the libraries the emulator needs. On Linux it is the SDK's whole list rather
     * than the one library the loader complained about: the loader stops at the first, and the
     * package managers skip what is already there.
     */
    fun emulatorLibraries(homebrewFormula: String?): String? = when {
        os == HostOs.MAC && homebrewFormula != null && brew != null -> "${brew.invariantSeparatorsPathString} install $homebrewFormula"
        os == HostOs.MAC && homebrewFormula != null ->
            // Homebrew itself, then the formula: its installer asks for the password, in the
            // terminal, and puts brew where a new shell would not yet find it.
            "/bin/bash -c \"\$(curl -fsSL $HOMEBREW_INSTALLER)\" && " +
                "eval \"\$(/opt/homebrew/bin/brew shellenv 2>/dev/null || /usr/local/bin/brew shellenv)\" && " +
                "brew install $homebrewFormula"
        os == HostOs.LINUX && distro == Distro.DEBIAN ->
            "sudo apt install libsdl2-2.0-0 libglib2.0-0 libpixman-1-0 zlib1g libsndio7.0"
        os == HostOs.LINUX && distro == Distro.FEDORA -> "sudo dnf install SDL2 glib2 pixman zlib sndio"
        else -> null
    }

    companion object {
        const val HOMEBREW_INSTALLER = "https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh"

        /** Nothing known: no command for anything. */
        val NONE = HostPackages(HostOs.LINUX, null, null)

        /**
         * Where the tools run: this machine, or a WSL distribution, which is a Linux like any other
         * here. Homebrew is looked for each time — it is the thing a user installs while the IDE
         * runs — and the distribution read once by the host: the checklist asks on every editor
         * notification and run configuration check, on the UI thread.
         */
        fun current(host: ToolHost?): HostPackages {
            host ?: return NONE
            val os = host.os
            return HostPackages(
                os,
                brew = if (os == HostOs.MAC) locateBrew(host.env("PATH")) else null,
                distro = if (os == HostOs.LINUX) host.osRelease()?.let(::distro) else null,
                appleSilicon = host.arch.let { it == "aarch64" || it == "arm64" },
                wsl = host is WslToolHost,
            )
        }

        /** Homebrew's prefix is off the PATH of an IDE started from the Dock, so it is looked in too. */
        fun locateBrew(path: String?, prefixes: List<Path> = listOf(Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"))): Path? =
            (path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map(Path::of) + prefixes)
                .map { it.resolve("brew") }
                .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

        /** The family from `ID` and `ID_LIKE`: Ubuntu and Mint are Debian, RHEL and Rocky are Fedora. */
        fun distro(osRelease: String): Distro? {
            val ids = osRelease.lines()
                .filter { it.startsWith("ID=") || it.startsWith("ID_LIKE=") }
                .flatMap { it.substringAfter('=').trim('"', '\'').split(' ') }
            return when {
                ids.any { it == "debian" || it == "ubuntu" } -> Distro.DEBIAN
                ids.any { it == "fedora" || it == "rhel" } -> Distro.FEDORA
                else -> null
            }
        }
    }
}
