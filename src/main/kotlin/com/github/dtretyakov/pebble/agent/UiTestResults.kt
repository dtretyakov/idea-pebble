package com.github.dtretyakov.pebble.agent

import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

@Serializable
data class UiTestOutcome(
    val passed: Int,
    val failed: Int,
    /** Screens with no reference yet, recorded now: look at them, and commit them if they are right. */
    val recorded: Int,
    val tests: List<UiTestResult>,
    /** The run's last words, when it did not get as far as a test. */
    val output: List<String> = emptyList(),
)

@Serializable
data class UiTestResult(
    val scenario: String,
    val platform: String?,
    val screen: String,
    /** `passed`, `failed` or `recorded`. */
    val status: String,
    val message: String? = null,
    val reference: String? = null,
    val actual: String? = null,
    /** The pixels that differ, in red. */
    val diff: String? = null,
    /** To look at: a failure's reference, screen and difference side by side, or a recorded screen, enlarged. */
    val view: String? = null,
)

/**
 * The UI test run's results, read back from what it told the IDE's test runner — the TeamCity
 * service messages `ScenarioProcessHandler` prints: a suite per scenario, one per platform inside
 * it, a test per expected screen.
 */
class UiTestResults(private val root: Path) {

    private val results = mutableListOf<UiTestResult>()
    private val suites = ArrayDeque<String>()
    private var scenario: String? = null
    private var current: String? = null
    private var recordedNow = false
    private var failure: UiTestResult? = null
    private val stray = mutableListOf<String>()
    private val pending = StringBuilder()

    fun feed(text: String) {
        pending.append(text)
        var newline = pending.indexOf("\n")
        while (newline >= 0) {
            line(pending.substring(0, newline).trimEnd('\r'))
            pending.delete(0, newline + 1)
            newline = pending.indexOf("\n")
        }
    }

    fun outcome(): UiTestOutcome {
        if (pending.isNotEmpty()) line(pending.toString()).also { pending.setLength(0) }
        return UiTestOutcome(
            passed = results.count { it.status == "passed" },
            failed = results.count { it.status == "failed" },
            recorded = results.count { it.status == "recorded" },
            tests = results.toList(),
            output = if (results.isEmpty()) stray.takeLast(20) else emptyList(),
        )
    }

    private fun line(line: String) {
        val message = runCatching { ServiceMessage.parse(line) }.getOrNull()
        if (message == null) {
            if (line.isBlank()) return
            if (current != null && line.startsWith("Recorded ") && line.contains(" as the reference")) recordedNow = true
            stray += line
            return
        }
        val name = message.attributes["name"].orEmpty()
        when (message.messageName) {
            "testSuiteStarted" -> {
                if (suites.isEmpty()) scenario = message.attributes["locationHint"]?.substringAfter("://")?.substringBeforeLast(':')?.let(::relative) ?: name
                suites.addLast(name)
            }
            "testSuiteFinished" -> suites.removeLastOrNull()
            "testStarted" -> {
                current = name
                recordedNow = false
                failure = null
            }
            "testFailed" -> {
                val platform = platform()
                val actual = message.attributes["actualFile"]
                val diff = actual?.let { Path.of(it).resolveSibling("$name.diff.png") }?.takeIf { Files.exists(it) }
                failure = UiTestResult(
                    scenario.orEmpty(), platform, name, "failed",
                    message.attributes["message"],
                    message.attributes["expectedFile"]?.let(::relative),
                    actual?.let(::relative),
                    diff?.toString()?.let(::relative),
                )
            }
            "testFinished" -> {
                results += failure ?: UiTestResult(scenario.orEmpty(), platform(), name, if (recordedNow) "recorded" else "passed")
                current = null
                failure = null
            }
        }
    }

    /** The platform suite inside the scenario's, when the test is in one. */
    private fun platform(): String? = suites.getOrNull(1)

    private fun relative(path: String): String {
        val file = Path.of(path)
        return if (file.startsWith(root)) root.relativize(file).invariantSeparatorsPathString else path
    }
}
