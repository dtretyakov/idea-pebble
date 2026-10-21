package com.github.dtretyakov.pebble.testing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThrowableRunnable
import com.intellij.util.ui.UIUtil
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * A platform test that does not fail on the IDE's own startup noise.
 *
 * Opening a project in a test runs every post-startup activity in the distribution, and some of
 * the bundled ones cannot be constructed outside a real IDE. A platform test turns any logged
 * error into a failure; errors from this plugin still do.
 */
abstract class IdeTestCase : BasePlatformTestCase() {

    /**
     * The sandbox's plugins, which a test IDE does not let VFS read by default — and LSP4IJ ships
     * JSON schemas the JSON plugin reads the moment any JSON file is opened.
     */
    override fun setUp() {
        super.setUp()
        VfsRootAccess.allowRootAccess(testRootDisposable, PathManager.getPluginsPath())
    }

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

    /**
     * [block] on a pooled thread, as an agent or a background task would run it, the UI thread
     * dispatching its events meanwhile. A test runs on the UI thread, and a read action waits out
     * any write action pending there — a VFS refresh the file watcher asked for, say — so a test that
     * held the UI thread while it waited would wait out its whole timeout, or with none, forever.
     */
    protected fun <T> offUiThread(timeoutSeconds: Long = 150, block: () -> T): T {
        val future = ApplicationManager.getApplication().executeOnPooledThread<T>(block)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (!future.isDone && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        return try {
            future.get(1, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
