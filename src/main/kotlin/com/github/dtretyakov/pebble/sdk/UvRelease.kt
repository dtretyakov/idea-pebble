package com.github.dtretyakov.pebble.sdk

/**
 * The uv the plugin downloads when the machine has none.
 *
 * Pinned rather than "latest", with checksums from this file rather than from the server the
 * archive comes from: that proves the bytes are the ones that were tried, not merely that they
 * arrived intact. Moving to a new release is these lines — the version, and each archive's
 * `.sha256` from its release page.
 *
 * Only for setting up pebble-tool: a uv the user installed themselves is always preferred.
 */
object UvRelease {

    const val VERSION = "0.13.1"

    data class Asset(val file: String, val sha256: String) {
        val url: String get() = "https://github.com/astral-sh/uv/releases/download/$VERSION/$file"
    }

    private val MAC_ARM = Asset("uv-aarch64-apple-darwin.tar.gz", "cfe454573412ff7380d15fc7dae5997ab53ffce72fce0a78e91ddbc84001acd2")
    private val MAC_INTEL = Asset("uv-x86_64-apple-darwin.tar.gz", "bb7b805bdcf3d0bc8c74ceb94569e6b3e8aa35bd492acdbb9d46bf46d9d4ff6b")
    private val LINUX_ARM = Asset("uv-aarch64-unknown-linux-gnu.tar.gz", "9041c73c99fdc5892cf128140beb354eca9231d502068cc36e6cb53a8e59ad14")
    private val LINUX_INTEL = Asset("uv-x86_64-unknown-linux-gnu.tar.gz", "eadd9d27c134ab5ac1ffd4473b97a741407f3ea238288f03121fc4deddf90220")

    /**
     * The archive for this system, or null where the SDK does not run: Windows, where it needs
     * WSL, and processors the SDK has no toolchain for.
     */
    fun forPlatform(os: HostOs = HostOs.current(), arch: String = System.getProperty("os.arch")): Asset? {
        val arm = arch == "aarch64" || arch == "arm64"
        val intel = arch == "amd64" || arch == "x86_64"
        return when {
            os == HostOs.MAC && arm -> MAC_ARM
            os == HostOs.MAC && intel -> MAC_INTEL
            os == HostOs.LINUX && arm -> LINUX_ARM
            os == HostOs.LINUX && intel -> LINUX_INTEL
            else -> null
        }
    }
}
