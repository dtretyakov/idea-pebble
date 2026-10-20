package com.github.dtretyakov.pebble.sdk

/**
 * Windows paths and the Linux paths a WSL distribution sees them as, both ways, as text.
 *
 * - `\\wsl.localhost\Ubuntu\home\me\app` (or the older `\\wsl$\Ubuntu\…`) is `/home/me/app` in
 *   Ubuntu, and nothing at all in another distribution.
 * - `C:\Users\me\app` is `/mnt/c/Users/me/app` in every distribution, under its automount root.
 *
 * The same rules as `wslpath`, without starting a process for each path: the build's output alone
 * names hundreds.
 */
object WslPaths {

    /** The prefix Windows 11 and current WSL give a distribution's files. */
    const val DEFAULT_PREFIX = """\\wsl.localhost\"""

    /** Where WSL mounts Windows drives unless `/etc/wsl.conf` says otherwise. */
    const val DEFAULT_MOUNT_ROOT = "/mnt/"

    /** A path in a distribution's share: its prefix (`\\wsl.localhost\`), the distribution, and the Linux path. */
    data class Unc(val prefix: String, val distribution: String, val linuxPath: String)

    private val UNC = Regex("""^[\\/]{2}(wsl\$|wsl\.localhost)[\\/]([^\\/]+)([\\/].*)?$""", RegexOption.IGNORE_CASE)
    private val DRIVE = Regex("""^([A-Za-z]):(?:[\\/](.*))?$""")

    fun parseUnc(path: String): Unc? {
        val match = UNC.matchEntire(path) ?: return null
        val rest = match.groupValues[3].replace('\\', '/').trimEnd('/').ifEmpty { "/" }
        return Unc("""\\${match.groupValues[1]}\""", match.groupValues[2], rest)
    }

    /** The distribution a Windows path is in, when it is in one. */
    fun distributionOf(path: String): String? = parseUnc(path)?.distribution

    /** [windows] as [distribution] sees it, or null when it cannot. */
    fun toLinux(windows: String, distribution: String, mountRoot: String = DEFAULT_MOUNT_ROOT): String? {
        parseUnc(windows)?.let { unc -> return unc.linuxPath.takeIf { unc.distribution.equals(distribution, ignoreCase = true) } }
        val drive = DRIVE.matchEntire(windows) ?: return null
        val rest = drive.groupValues[2].replace('\\', '/').trimEnd('/')
        val root = mountRoot.trimEnd('/') + "/" + drive.groupValues[1].lowercase()
        return if (rest.isEmpty()) root else "$root/$rest"
    }

    /** A Linux path of [distribution], as Windows reaches it; null for anything that is not absolute. */
    fun toWindows(linux: String, distribution: String, prefix: String = DEFAULT_PREFIX, mountRoot: String = DEFAULT_MOUNT_ROOT): String? {
        if (!linux.startsWith("/")) return null
        val mount = mountRoot.trimEnd('/') + "/"
        if (linux.startsWith(mount)) {
            val rest = linux.removePrefix(mount)
            val letter = rest.substringBefore('/')
            if (letter.length == 1 && letter[0].isLetter()) {
                val inside = rest.substringAfter('/', "").trimEnd('/').replace('/', '\\')
                return "${letter.uppercase()}:\\$inside"
            }
        }
        val inside = linux.trimEnd('/').removePrefix("/").replace('/', '\\')
        return prefix.trimEnd('\\') + "\\" + distribution + "\\" + inside
    }
}
