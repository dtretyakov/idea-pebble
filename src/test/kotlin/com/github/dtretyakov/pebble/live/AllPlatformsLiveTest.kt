package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.emulator.PlatformScreenshots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** What Capture Every Platform runs, and what the Platforms tab then reads: one screenshot per platform. */
class AllPlatformsLiveTest {

    @Test
    fun `every targeted platform is captured at its own size`() {
        val root = LiveSdk.fixture("fixture-c")
        val packageJson = root.resolve("package.json")
        packageJson.writeText(
            packageJson.readText().replace(Regex(""""targetPlatforms": \[[^]]*]""", RegexOption.DOT_MATCHES_ALL), """"targetPlatforms": ["basalt", "emery"]"""),
        )
        check(LiveSdk.run(root, "build").exitCode == 0)

        val output = LiveSdk.run(root, "screenshot", "--all-platforms", "--no-open")
        assertEquals(0, output.exitCode, output.text)

        val shots = PlatformScreenshots.latest(root.resolve(PlatformScreenshots.DIRECTORY), listOf("basalt", "emery"))
        assertEquals(listOf("basalt", "emery"), shots.map { it.platform }, output.text)
        val sizes = shots.map { ImageIO.read(it.file.toFile()).let { image -> image.width to image.height } }
        assertEquals(listOf(144 to 168, 200 to 228), sizes)
        assertEquals(2, Files.list(root.resolve(PlatformScreenshots.DIRECTORY)).use { it.count() }.toInt())
    }
}
