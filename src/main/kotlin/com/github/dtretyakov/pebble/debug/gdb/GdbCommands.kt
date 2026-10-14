package com.github.dtretyakov.pebble.debug.gdb

import com.github.dtretyakov.pebble.debug.gdb.MiParser.quote

/**
 * The MI commands the debugger sends, without their tokens ([GdbConnection] numbers them).
 *
 * Every parameter that comes from the user or the disk is quoted: a path with a space in it, or
 * an expression with a comma, is one parameter. Frames are named by thread and level, so nothing
 * depends on which frame gdb has selected.
 */
object GdbCommands {

    const val CONTINUE = "-exec-continue"
    const val NEXT = "-exec-next"
    const val STEP = "-exec-step"
    const val INTERRUPT = "-exec-interrupt"
    const val DETACH = "-target-detach"
    const val EXIT = "-gdb-exit"

    /** A line breakpoint by the source's absolute path, as gdb finds it from the app's debug info. */
    fun breakInsert(path: String, line: Int, condition: String? = null): String =
        "-break-insert" + (condition?.trim()?.takeIf { it.isNotEmpty() }?.let { " -c ${quote(it)}" } ?: "") +
            " --source ${quote(path)} --line $line"

    fun breakDelete(number: String): String = "-break-delete $number"

    /** Runs until [frame] returns to its caller. */
    fun finish(thread: String, frame: Int): String = "-exec-finish --thread $thread --frame $frame"

    fun frames(thread: String): String = "-stack-list-frames --thread $thread 0 $MAX_FRAMES"

    fun variables(thread: String, frame: Int): String = "-stack-list-variables --thread $thread --frame $frame --no-values"

    /** A variable object for [expression] in a frame: its value, type and number of children. */
    fun varCreate(thread: String, frame: Int, expression: String): String =
        "-var-create --thread $thread --frame $frame - * ${quote(expression)}"

    fun varChildren(name: String, count: Int): String = "-var-list-children --all-values ${quote(name)} 0 $count"

    fun varAssign(name: String, expression: String): String = "-var-assign ${quote(name)} ${quote(expression)}"

    fun varDelete(name: String): String = "-var-delete ${quote(name)}"

    fun evaluate(thread: String, frame: Int, expression: String): String =
        "-data-evaluate-expression --thread $thread --frame $frame ${quote(expression)}"

    /** A CLI command, for the few that have no MI form (`add-symbol-file`). */
    fun console(command: String): String = "-interpreter-exec console ${quote(command)}"

    const val MAX_FRAMES = 99
}

/** A frame as gdb lists it; [fullname] is absolute only for sources it found on this disk. */
data class GdbFrame(val level: Int, val function: String, val file: String?, val fullname: String?, val line: Int?, val address: String?) {
    companion object {
        fun of(tuple: MiValue.Tuple): GdbFrame = GdbFrame(
            level = tuple.text("level")?.toIntOrNull() ?: 0,
            function = tuple.text("func") ?: tuple.text("addr") ?: "??",
            file = tuple.text("file"),
            fullname = tuple.text("fullname"),
            line = tuple.text("line")?.toIntOrNull(),
            address = tuple.text("addr"),
        )

        fun list(record: MiRecord): List<GdbFrame> =
            record.list("stack").filterIsInstance<MiValue.Tuple>().map(::of)
    }
}

/**
 * Where a step that left the app's sources goes next.
 *
 * The SDK's functions are called through stubs without line information, so gdb steps over them
 * by itself. Stepping off the end of a handler, though, lands in the firmware that called it —
 * frames whose sources are not on this disk. There, the step runs on until the firmware returns
 * to the app's own next frame below, as if the firmware were not there. For a handler that frame
 * is `main`, waiting in the event loop for the app to exit, so the app runs on to its next
 * breakpoint; with no frame of the app's at all, it simply continues.
 */
object GdbLanding {

    sealed interface Next {
        data object Stay : Next
        data class Finish(val frame: Int) : Next
        data object Continue : Next
    }

    fun after(frames: List<GdbFrame>, isAppSource: (GdbFrame) -> Boolean): Next {
        if (frames.isEmpty() || isAppSource(frames[0])) return Next.Stay
        val app = frames.indexOfFirst(isAppSource)
        return if (app < 0) Next.Continue else Next.Finish(frames[app - 1].level)
    }
}
