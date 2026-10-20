package com.github.dtretyakov.pebble.sdk

import java.net.InetSocketAddress
import java.net.Socket

/** This machine's loopback, on both families. */
object Loopback {

    /**
     * Whether something listens on [port] at `127.0.0.1` or `::1`. Both: a program in WSL that
     * listens on IPv6's any address — pypkjs — is forwarded to Windows' `::1` alone, and a local
     * one may have taken either.
     */
    fun listening(port: Int, timeoutMillis: Int): Boolean = listOf("127.0.0.1", "::1").any { host ->
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMillis) } }.isSuccess
    }
}
