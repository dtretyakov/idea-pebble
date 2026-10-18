package com.github.dtretyakov.pebble.debug

import com.github.dtretyakov.pebble.sdk.PebbleDataDir
import com.github.dtretyakov.pebble.sdk.PebbleSdk
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/** The runtime's frames, found by the engine's names for them in the SDK's copy of the runtime. */
class AlloyRuntimeTest {

    @TempDir
    lateinit var dir: Path

    /** Shaped as SDK 4.33.1's `modules/global/global.js`. */
    private val global = """
        function health() { return native("xs_global_health").call(this); };

        export class Pebble {
        	#events = new Map;

        	addEventListener(event, callback) {
        		switch (index) {
        			case 4:
        				break;
        		}
        	}
        	do(event, arg) {
        		try {
        			this.#events.get(event)?.forEach(cb => cb(arg));
        		}
        		catch (e) {
        			trace(`Event ("${'$'}{event}") exception`);
        		}
        	}
        	static create() {
        		return new Pebble;
        	}
        }
        globalThis.watch = new Pebble;
    """.trimIndent()

    @Test
    fun `methods, static ones and functions are named as the engine names their frames`() {
        val found = AlloyRuntime.definitions(global.lines())
        assertEquals(setOf("health", "Pebble.prototype.addEventListener", "Pebble.prototype.do", "Pebble.create"), found.keys)
        assertEquals(11..18, found.getValue("Pebble.prototype.do"))
    }

    @Test
    fun `a frame is placed on the line calling the next one in, or else on its declaration`() {
        val sdk = PebbleSdk(dir.resolve("SDKs/4.33.1"), "4.33.1", "release")
        val modules = sdk.toolchain.resolve("moddable/build/devices/pebble/modules/global").createDirectories()
        val file = modules.resolve("global.js").also { it.writeText(global) }

        assertEquals(AlloyRuntime.Location(file, 13), AlloyRuntime.locate(sdk, "Pebble.prototype.do", callee = "forEach"))
        assertEquals(AlloyRuntime.Location(file, 11), AlloyRuntime.locate(sdk, "Pebble.prototype.do", callee = "(anonymous-0)"))
        assertNull(AlloyRuntime.locate(sdk, "forEach", callee = "draw"), "the engine's own")
        assertNull(AlloyRuntime.locate(null, "Pebble.prototype.do", callee = "forEach"))
    }

    @Test
    fun `the installed SDK's runtime has Pebble's dispatch where a stop in a handler passes through`() {
        val sdk = PebbleSdks.scan(PebbleDataDir.locate()).current
        assumeTrue(sdk != null, "no SDK installed")
        val location = AlloyRuntime.locate(sdk, "Pebble.prototype.do", callee = "forEach")
        assumeTrue(location != null, "this SDK's runtime has no Pebble.prototype.do")
        assertEquals(true, location!!.file.readLines()[location.line].contains("forEach("))
    }
}
