package com.github.dtretyakov.pebble.uitest

/**
 * A UI test of a watch app: steps on the emulator, and the screens they should lead to.
 *
 * ```
 * # tests/ui/menu.pbtest
 * platforms emery basalt     # the project's target platforms when left out
 * install
 * time 10:10
 * press select
 * wait 500
 * expect menu                # tests/ui/screenshots/<platform>/menu.png
 * ```
 *
 * Each step is a pebble-tool command — `install`, `emu-button`, `emu-set-time`, `emu-battery`,
 * `emu-bt-connection`, `emu-accel`, `emu-tap`, `emu-set-timeline-quick-view` — except `tap`, a
 * touch through the emulator's screen, and `expect`, a screenshot compared with a reference.
 */
data class Scenario(val platforms: List<String>?, val steps: List<Step>, val problems: List<Problem>) {

    data class Problem(val line: Int, val message: String)

    sealed interface Step {
        /** One-based. */
        val line: Int

        data class Install(override val line: Int) : Step
        data class Wait(override val line: Int, val millis: Long) : Step
        data class Tap(override val line: Int, val x: Int, val y: Int) : Step
        data class Expect(override val line: Int, val name: String) : Step

        /** A `pebble` command against the emulator; the connection arguments are added when it runs. */
        data class Pebble(override val line: Int, val arguments: List<String>) : Step
    }

    val expectations: List<Step.Expect> get() = steps.filterIsInstance<Step.Expect>()
}

object ScenarioParser {

    private val BUTTONS = setOf("back", "up", "select", "down")
    private val MOTIONS = setOf("tilt-left", "tilt-right", "tilt-forward", "tilt-back", "gravity+x", "gravity-x", "gravity+y", "gravity-y", "gravity+z", "gravity-z")
    private val DIRECTIONS = setOf("x+", "x-", "y+", "y-", "z+", "z-")
    private val NAME = Regex("""[A-Za-z0-9_.\-]+""")
    private val TIME = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?""")

    /** A scenario; [steps] alone, as the agents' emulator tool takes them, need not expect a screen. */
    fun parse(text: String, steps: Boolean = false): Scenario {
        var platforms: List<String>? = null
        val onlySteps = steps
        val steps = mutableListOf<Scenario.Step>()
        val problems = mutableListOf<Scenario.Problem>()
        text.lines().forEachIndexed { index, raw ->
            val line = index + 1
            val words = raw.substringBefore('#').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return@forEachIndexed
            fun problem(message: String) {
                problems += Scenario.Problem(line, message)
            }
            val arguments = words.drop(1)
            when (words[0]) {
                "platforms" -> when {
                    steps.isNotEmpty() -> problem("'platforms' comes before the steps")
                    platforms != null -> problem("'platforms' is said once")
                    arguments.isEmpty() -> problem("'platforms' names at least one platform")
                    else -> platforms = arguments
                }
                "install" -> if (arguments.isEmpty()) steps += Scenario.Step.Install(line) else problem("'install' takes nothing")
                "wait" -> arguments.singleOrNull()?.toLongOrNull()?.takeIf { it >= 0 }
                    ?.let { steps += Scenario.Step.Wait(line, it) } ?: problem("'wait' takes milliseconds: wait 500")
                "press" -> {
                    val button = arguments.firstOrNull()
                    val millis = arguments.getOrNull(1)?.toIntOrNull() ?: 100
                    when {
                        button !in BUTTONS -> problem("'press' takes a button: back, up, select or down")
                        arguments.size > 2 || (arguments.size == 2 && arguments[1].toIntOrNull() == null) -> problem("'press' takes a button and, optionally, how long in milliseconds")
                        else -> steps += Scenario.Step.Pebble(line, listOf("emu-button", "click", button!!, "--duration", millis.toString()))
                    }
                }
                "tap" -> {
                    val x = arguments.getOrNull(0)?.toIntOrNull()
                    val y = arguments.getOrNull(1)?.toIntOrNull()
                    if (arguments.size == 2 && x != null && y != null) steps += Scenario.Step.Tap(line, x, y)
                    else problem("'tap' takes a point on the screen: tap 100 120")
                }
                "time" -> {
                    val match = arguments.singleOrNull()?.let(TIME::matchEntire)
                    if (match == null) {
                        problem("'time' takes a time of day: time 10:10")
                    } else {
                        val (hours, minutes, seconds) = match.destructured
                        steps += Scenario.Step.Pebble(line, listOf("emu-set-time", "%02d:%s:%s".format(hours.toInt(), minutes, seconds.ifEmpty { "00" })))
                    }
                }
                "battery" -> {
                    val percent = arguments.firstOrNull()?.toIntOrNull()?.takeIf { it in 0..100 }
                    val charging = arguments.getOrNull(1)
                    when {
                        percent == null -> problem("'battery' takes a percentage: battery 20")
                        charging != null && charging != "charging" || arguments.size > 2 -> problem("'battery' takes a percentage and, optionally, 'charging'")
                        else -> steps += Scenario.Step.Pebble(line, listOfNotNull("emu-battery", "--percent", percent.toString(), "--charging".takeIf { charging != null }))
                    }
                }
                "bluetooth" -> when (arguments.singleOrNull()) {
                    "on" -> steps += Scenario.Step.Pebble(line, listOf("emu-bt-connection", "--connected", "yes"))
                    "off" -> steps += Scenario.Step.Pebble(line, listOf("emu-bt-connection", "--connected", "no"))
                    else -> problem("'bluetooth' takes on or off")
                }
                "quick-view" -> when (arguments.singleOrNull()) {
                    "on", "off" -> steps += Scenario.Step.Pebble(line, listOf("emu-set-timeline-quick-view", arguments[0]))
                    else -> problem("'quick-view' takes on or off")
                }
                "motion" -> arguments.singleOrNull()?.takeIf { it in MOTIONS }
                    ?.let { steps += Scenario.Step.Pebble(line, listOf("emu-accel", it)) }
                    ?: problem("'motion' takes one of ${MOTIONS.joinToString()}")
                "shake" -> {
                    val direction = arguments.firstOrNull() ?: "x+"
                    if (arguments.size <= 1 && direction in DIRECTIONS) steps += Scenario.Step.Pebble(line, listOf("emu-tap", "--direction", direction))
                    else problem("'shake' takes, optionally, a direction: ${DIRECTIONS.joinToString()}")
                }
                "expect" -> arguments.singleOrNull()?.takeIf(NAME::matches)
                    ?.let { steps += Scenario.Step.Expect(line, it) }
                    ?: problem("'expect' takes the name of a screenshot: expect menu")
                else -> problem("Unknown step '${words[0]}'. Steps: platforms, install, wait, press, tap, time, battery, bluetooth, quick-view, motion, shake, expect")
            }
        }
        if (!onlySteps && steps.none { it is Scenario.Step.Expect } && problems.isEmpty()) problems += Scenario.Problem(1, "A scenario expects at least one screen")
        return Scenario(platforms, steps, problems)
    }
}
