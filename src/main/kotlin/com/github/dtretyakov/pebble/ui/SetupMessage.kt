package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.PebbleEnvironment
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Concern
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Fix
import com.github.dtretyakov.pebble.project.PebbleSdkSetup

/**
 * What the banner and the balloon say about a problem: what the user cannot do yet, and what the
 * button will do about it.
 *
 * Named after the goal rather than the missing part. "pebble-tool missing" over a button that
 * installed uv asked the user to know how the two are related; "Pebble SDK isn't set up" over
 * "Set Up Pebble SDK" asks nothing.
 */
internal object SetupMessage {

    fun title(item: PebbleEnvironment.Item): String = when {
        item.fix == Fix.SET_UP -> "Pebble SDK isn't set up"
        item.concern == Concern.HOST -> "Pebble development on Windows needs WSL"
        item.concern == Concern.TOOL -> "pebble-tool is not where its setting says"
        item.concern == Concern.SDK -> "No Pebble SDK is active"
        item.concern == Concern.TOOLCHAIN -> "The Pebble SDK is incomplete"
        item.concern == Concern.EMULATOR -> "The Pebble emulator cannot start"
        item.concern == Concern.NODE -> "Node.js is not installed"
        else -> "C has no code insight"
    }

    /** HTML. */
    fun body(item: PebbleEnvironment.Item): String {
        if (item.concern == Concern.EMULATOR) {
            // The title already says it cannot start; the body says why, and what still works.
            val why = EnvironmentFixes.text(item).removePrefix("The emulator cannot start: ").replaceFirstChar(Char::uppercaseChar)
            return "$why Building and installing on a watch work."
        }
        if (item.fix != Fix.SET_UP) return EnvironmentFixes.text(item)
        val downloads = PebbleSdkSetup.remaining().ifEmpty { listOf("what is missing") }
        return "Building and running need pebble-tool and an SDK. ${PebbleSdkSetup.LABEL} downloads " +
            "${list(downloads)} — a few hundred megabytes — and needs nothing else from you."
    }

    private fun list(parts: List<String>): String =
        if (parts.size < 2) parts.joinToString() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
}
