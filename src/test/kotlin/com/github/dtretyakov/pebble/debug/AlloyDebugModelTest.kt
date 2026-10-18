package com.github.dtretyakov.pebble.debug

import com.github.dtretyakov.pebble.debug.XsbugTree.Lookup
import com.github.dtretyakov.pebble.debug.XsbugTree.Step
import com.github.dtretyakov.pebble.debug.XsbugValues.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile

/** Finding values in the engine's views, showing them, and knowing the app's sources. */
class AlloyDebugModelTest {

    private val messages = XsbugParser().feed(javaClass.getResource("/xsbug/session.bin")!!.readBytes())
    private val stop = messages.first { it.breakAt != null }
    private val expandedView = messages.filter { it.isView }[2]

    @Test
    fun `a value is found again by the names and objects that led to it`() {
        val function = stop.local!!.properties.first { it.name == "(function)" }
        val path = listOf(Step("(function)", function.objectId!!))
        assertEquals(Lookup.Collapsed(function.objectId!!), XsbugTree.find(stop.local!!.properties, path), "not expanded yet: toggle it")

        val found = XsbugTree.find(expandedView.local!!.properties, path)
        assertTrue(found is Lookup.Found && found.property.children.isNotEmpty(), found.toString())

        assertEquals(Lookup.Gone, XsbugTree.find(expandedView.local!!.properties, listOf(Step("(function)", "@1"))))
    }

    @Test
    fun `a nested value needs its parents expanded first`() {
        val inner = XsbugProperty("inner", "+cew_", "@B", null, emptyList())
        val collapsed = listOf(XsbugProperty("outer", "+cew_", "@A", null, emptyList()))
        val expanded = listOf(XsbugProperty("outer", "-cew_", "@A", null, listOf(inner)))
        val path = listOf(Step("outer", "@A"), Step("inner", "@B"))
        assertEquals(Lookup.Collapsed("@A"), XsbugTree.find(collapsed, path))
        assertEquals(Lookup.Collapsed("@B"), XsbugTree.find(expanded, path))
    }

    @Test
    fun `values read as the engine formatted them`() {
        fun shown(value: String?, flags: String = " cew_", line: Int? = null) = XsbugValues.of(XsbugProperty("x", flags, value, line, emptyList()))
        assertEquals(XsbugValues.Shown(Kind.STRING, "11:54:09"), shown("'11:54:09'"))
        assertEquals(Kind.NUMBER, shown("255").kind)
        assertEquals(Kind.NUMBER, shown("-1.5e3").kind)
        assertEquals(Kind.KEYWORD, shown("undefined").kind)
        // An object is its address alone, shown as one to open; the frame's function, as a function.
        assertEquals(XsbugValues.Shown(Kind.OBJECT, "{…}"), shown("@20028504", flags = "+cEw_"))
        assertEquals(Kind.FUNCTION, XsbugValues.of(XsbugProperty("(function)", "+cew_", "@20028184", null, emptyList())).kind)
        assertEquals(Kind.FUNCTION, shown("/work/w/src/embeddedjs/main.js", line = 11).kind)
        assertEquals(Kind.FUNCTION, shown("(host function)").kind)
        assertEquals(Kind.UNINITIALIZED, shown(null).kind)
        assertEquals(XsbugValues.Shown(Kind.OTHER, "3 items"), shown("3 items"))
    }

    @Test
    fun `a break on a thrown exception is told from breakpoints and steps`() {
        assertFalse(XsbugBreak("/a.js", 1, "# Break: breakpoint!\n").isException)
        assertFalse(XsbugBreak("/a.js", 1, "# Break: step!\n").isException)
        assertFalse(XsbugBreak("/a.js", 1, "# Break: debugger!\n").isException)
        val thrown = XsbugBreak("/a.js", 1, "# Break: TypeError: no function!\n")
        assertTrue(thrown.isException)
        assertEquals("TypeError: no function", thrown.reason)
    }

    @Test
    fun `the app's sources are its embeddedjs`() {
        val root = Files.createTempDirectory("alloy")
        val main = root.resolve("src/embeddedjs").createDirectories().resolve("main.js").createFile()
        val typed = root.resolve("src/embeddedjs/lib").createDirectories().resolve("util.ts").createFile()
        val phone = root.resolve("src/pkjs").createDirectories().resolve("index.js").createFile()
        val manifest = root.resolve("src/embeddedjs/manifest.json").createFile()
        assertTrue(AlloySources.isAlloySource(root, main))
        assertTrue(AlloySources.isAlloySource(root, typed))
        assertFalse(AlloySources.isAlloySource(root, phone))
        assertFalse(AlloySources.isAlloySource(root, manifest))
        assertEquals(main.toRealPath(), AlloySources.enginePath(root.resolve("src/../src/embeddedjs/main.js")))
    }

    @Test
    fun `without the JavaScript debugger, the Alloy type offers itself on Alloy sources only`() {
        assertTrue(AlloyLineBreakpointType.offersOwnType(javascriptPresent = false, alloySource = true))
        assertFalse(AlloyLineBreakpointType.offersOwnType(javascriptPresent = true, alloySource = true))
        assertFalse(AlloyLineBreakpointType.offersOwnType(javascriptPresent = false, alloySource = false))
    }
}
