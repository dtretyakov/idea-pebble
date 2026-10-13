package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.sdk.PebbleTool
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.copyToRecursively

/**
 * The real pebble-tool, for the tests that check the plugin's reading of it against what it does.
 *
 * Opt-in with `./gradlew test -PliveTests`: on a machine without pebble-tool these would be
 * reporting the machine, not the code.
 */
object LiveSdk {

    fun tool(): Path {
        assumeTrue(System.getProperty("pebble.liveTests") == "true", "live tests are off; run with -PliveTests")
        val tool = PebbleTool.locate("", System.getenv("PATH"))
        assumeTrue(tool != null, "pebble-tool is not installed")
        return tool!!
    }

    /** A fresh copy of a fixture project, so a build never writes into the repository. */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun fixture(name: String): Path {
        val source = Path.of(LiveSdk::class.java.getResource("/$name/package.json")!!.toURI()).parent
        val copy = Files.createTempDirectory("pebble-live").resolve(name)
        source.copyToRecursively(copy, followLinks = false, overwrite = false)
        return copy
    }

    class Output(val exitCode: Int, val text: String)

    fun run(directory: Path, vararg arguments: String): Output {
        val process = ProcessBuilder(listOf(tool().toString()) + arguments)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .apply { environment()["PYTHONWARNINGS"] = "ignore" }
            .start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.MINUTES)) { "pebble ${arguments.joinToString(" ")} did not finish" }
        return Output(process.exitValue(), text)
    }
}
