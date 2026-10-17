package com.github.dtretyakov.pebble.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** When a run's emulator can take an install as it is, so that nothing is started or waited for. */
class EmulatorWarmUpTest {

    private val emery = RunningEmulator(
        "emery", "4.33.1", 4227, vnc = true, gdbPort = 58439, monitorPort = 34721, pypkjsPid = 4233, pypkjsPort = 49225,
    )

    private fun ready(
        emulator: RunningEmulator = emery,
        platform: String = "emery",
        sdk: String? = "4.33.1",
        listening: Boolean = true,
        pypkjsAlive: Boolean = true,
    ) = EmulatorWarmUp.ready(listOf(emulator), platform, sdk, { listening }, { pypkjsAlive })

    @Test
    fun `an emulator on screen with pypkjs listening is ready`() {
        assertEquals(emery, ready())
        assertEquals(emery, ready(sdk = null))
    }

    @Test
    fun `QEMU up with pypkjs not yet listening is the cold start install loses to`() {
        assertNull(ready(listening = false))
        assertNull(ready(pypkjsAlive = false))
        assertNull(ready(emery.copy(pypkjsPid = null, pypkjsPort = null)))
    }

    @Test
    fun `another platform, another SDK, or one without the screen is not the run's emulator`() {
        assertNull(ready(platform = "gabbro"))
        assertNull(ready(sdk = "4.34.0"))
        // pebble-tool restarts an emulator whose VNC setting differs from the command's.
        assertNull(ready(emery.copy(vnc = false)))
    }
}
