package com.github.dtretyakov.pebble.testing

import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThrowableRunnable

/**
 * A platform test that does not fail on the IDE's own startup noise.
 *
 * Opening a project in a test runs every post-startup activity in the distribution, and some of
 * the bundled ones cannot be constructed outside a real IDE. A platform test turns any logged
 * error into a failure; errors from this plugin still do.
 */
abstract class IdeTestCase : BasePlatformTestCase() {

    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        LoggedErrorProcessor.executeWith<Throwable>(
            object : LoggedErrorProcessor() {
                override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> =
                    if (message.contains("[Plugin: com.intellij.") && !message.contains("dtretyakov")) Action.NONE else Action.ALL
            },
        ) {
            super.runBare(testRunnable)
        }
    }
}
