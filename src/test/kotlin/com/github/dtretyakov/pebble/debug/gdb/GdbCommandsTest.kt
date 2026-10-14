package com.github.dtretyakov.pebble.debug.gdb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GdbCommandsTest {

    @Test
    fun `a line breakpoint names its source by path, quoted, with a condition if it has one`() {
        assertEquals(
            "-break-insert --source \"/work/my app/src/c/main.c\" --line 12",
            GdbCommands.breakInsert("/work/my app/src/c/main.c", 12),
        )
        assertEquals(
            "-break-insert -c \"s->label == \\\"x\\\"\" --source \"/w/main.c\" --line 3",
            GdbCommands.breakInsert("/w/main.c", 3, " s->label == \"x\" "),
        )
        assertEquals("-break-insert --source \"/w/main.c\" --line 3", GdbCommands.breakInsert("/w/main.c", 3, "  "))
    }

    @Test
    fun `frames are named by thread and level`() {
        assertEquals("-var-create --thread 1 --frame 2 - * \"clicks->where\"", GdbCommands.varCreate("1", 2, "clicks->where"))
        assertEquals("-stack-list-variables --thread 1 --frame 0 --no-values", GdbCommands.variables("1", 0))
        assertEquals("-exec-finish --thread 1 --frame 4", GdbCommands.finish("1", 4))
        assertEquals("-var-list-children --all-values \"var1.where\" 0 100", GdbCommands.varChildren("var1.where", 100))
        assertEquals("-interpreter-exec console \"add-symbol-file \\\"/w/app.elf\\\" x+0xa8\"", GdbCommands.console("add-symbol-file \"/w/app.elf\" x+0xa8"))
    }

    private fun frame(level: Int, function: String, app: Boolean) =
        GdbFrame(level, function, "../src/x.c", if (app) "/work/app/src/c/x.c" else "./build/../src/fw/x.c", 1, null)

    private val isApp: (GdbFrame) -> Boolean = { it.fullname!!.startsWith("/work/app") }

    @Test
    fun `a step that stays in the app's sources stops there`() {
        assertEquals(GdbLanding.Next.Stay, GdbLanding.after(listOf(frame(0, "twice", true), frame(1, "select_clicked", true)), isApp))
        assertEquals(GdbLanding.Next.Stay, GdbLanding.after(emptyList(), isApp))
    }

    @Test
    fun `a step off the end of a handler goes on to the app's next frame below the firmware`() {
        val stack = listOf(
            frame(0, "prv_dispatch_event", false),
            frame(1, "do_handle", false),
            frame(2, "app_event_loop_common", false),
            frame(3, "main", true),
            frame(4, "prv_app_task_main", false),
        )
        assertEquals(GdbLanding.Next.Finish(2), GdbLanding.after(stack, isApp))
        assertEquals(GdbLanding.Next.Continue, GdbLanding.after(listOf(frame(0, "prvIdleTask", false)), isApp))
    }
}
