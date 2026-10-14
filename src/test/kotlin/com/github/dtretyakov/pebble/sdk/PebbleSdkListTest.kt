package com.github.dtretyakov.pebble.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PebbleSdkListTest {

    @Test
    fun `available versions are read newest first`() {
        val output = "Installed SDKs:\n4.33.1 (active)\n\nAvailable SDKs:\n4.4\n4.5\n4.9.127\n4.9.148\n4.9.169\n4.17\n"
        assertEquals(listOf("4.17", "4.9.169", "4.9.148", "4.9.127", "4.5", "4.4"), PebbleSdkList.available(output))
    }

    @Test
    fun `a channel suffix and heading are understood`() {
        val output = "No SDKs installed yet.\nAvailable SDKs (beta channel):\n4.34-beta1 (beta)\n"
        assertEquals(listOf("4.34-beta1"), PebbleSdkList.available(output))
    }

    @Test
    fun `an unreachable server lists nothing`() {
        assertEquals(emptyList<String>(), PebbleSdkList.available("Available SDKs:\nCould not fetch list of available SDKs.\n"))
        assertEquals(emptyList<String>(), PebbleSdkList.available("Traceback (most recent call last):"))
    }
}
