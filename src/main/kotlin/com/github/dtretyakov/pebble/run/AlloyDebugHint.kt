package com.github.dtretyakov.pebble.run

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes

/**
 * What to expect when an Alloy debug session ends, said as it ends.
 *
 * A debug build waits for xsbug, so once the session is gone the app stops answering and the watch
 * puts up "… is not responding" — a dialog that stays over whatever runs next, the ordinary build
 * included, and takes its button presses and touches. Back dismisses it. Restarting the emulator
 * would not: the watch remembers the app, and starts it again.
 */
object AlloyDebugHint {

    const val TEXT = "\nThe debug build keeps waiting for xsbug now that the session has ended, and the watch will " +
        "say it is not responding. Press Back to dismiss that, and Run — rather than Debug — to put the ordinary " +
        "build back.\n"

    fun attach(handler: ProcessHandler) {
        handler.addProcessListener(
            object : ProcessListener {
                override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                    handler.notifyTextAvailable(TEXT, ProcessOutputTypes.SYSTEM)
                }
            },
        )
    }
}
