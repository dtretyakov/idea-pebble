package com.github.dtretyakov.pebble.run

/**
 * What to expect when an Alloy debug session could not log out — the app was not answering, or
 * the bridge did not hang up in time.
 *
 * A debug build left waiting for its debugger stops answering, and the watch puts up "… is not
 * responding" — a dialog that stays over whatever runs next, the ordinary build included, and
 * takes its button presses and touches. Back dismisses it. A session that logs out leaves none of
 * this, so the hint is said only when logging out failed.
 */
object AlloyDebugHint {

    const val TEXT = "\nThe debug build may keep waiting for the debugger now that the session has ended, and the watch " +
        "would say it is not responding. Press Back to dismiss that, and Run — rather than Debug — to put the ordinary " +
        "build back.\n"
}
