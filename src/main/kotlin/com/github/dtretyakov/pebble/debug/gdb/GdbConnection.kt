package com.github.dtretyakov.pebble.debug.gdb

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * gdb's MI, over its standard input and output.
 *
 * Commands are numbered, and a result record (`12^done,…`) answers the command with its number.
 * Everything else — the target stopping or running, gdb's own notices, console text — goes to
 * the [listener], in the order gdb printed it, on the reading thread. [running] follows the
 * target: set by `^running` before the command that ran it gets its answer, so whoever sent it
 * sees it set.
 */
class GdbConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val listener: Listener,
    private val onClose: () -> Unit = {},
) : Closeable {

    interface Listener {
        fun record(record: MiRecord)
        fun closed()
    }

    class GdbError(message: String) : Exception(message)

    private val tokens = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<MiRecord>>()

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var isClosed: Boolean = false
        private set

    fun start(): GdbConnection {
        thread(name = "gdb reader", isDaemon = true) { read() }
        return this
    }

    /** Sends [command]; the future completes with gdb's answer, an error answer included. */
    fun request(command: String): CompletableFuture<MiRecord> {
        val token = tokens.incrementAndGet()
        val future = CompletableFuture<MiRecord>()
        pending[token] = future
        try {
            synchronized(output) {
                output.write("$token$command\n".toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (e: IOException) {
            pending.remove(token)
            future.completeExceptionally(e)
        }
        if (isClosed) pending.remove(token)?.completeExceptionally(IOException("gdb has exited"))
        return future
    }

    /** Sends [command] and waits for the answer; an error answer is thrown, with gdb's message. */
    fun call(command: String, timeoutSeconds: Long = TIMEOUT_SECONDS): MiRecord {
        val record = try {
            request(command).get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw GdbError(e.cause?.message ?: "gdb did not answer")
        } catch (_: TimeoutException) {
            throw GdbError("gdb did not answer $command in $timeoutSeconds seconds")
        }
        if (record.isError) throw GdbError(record.text("msg") ?: "gdb could not do it")
        return record
    }

    private fun read() {
        try {
            input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val record = MiParser.parse(line) ?: return@forEachLine
                when (record.kind) {
                    MiRecord.Kind.PROMPT -> Unit
                    MiRecord.Kind.RESULT -> {
                        if (record.type == "running") running = true
                        val future = record.token?.let { pending.remove(it) }
                        if (future != null) future.complete(record) else listener.record(record)
                    }
                    else -> {
                        if (record.kind == MiRecord.Kind.EXEC) {
                            if (record.type == "running") running = true
                            if (record.type == "stopped") running = false
                        }
                        listener.record(record)
                    }
                }
            }
        } catch (_: IOException) {
            // gdb went; the same as the end of its output.
        } finally {
            isClosed = true
            pending.values.forEach { it.completeExceptionally(IOException("gdb has exited")) }
            pending.clear()
            listener.closed()
        }
    }

    override fun close() {
        isClosed = true
        runCatching { output.close() }
        onClose()
    }

    companion object {
        const val TIMEOUT_SECONDS = 15L

        /** gdb started from [setup]'s command line, with the connection reading it. */
        fun launch(setup: GdbSetup, listener: Listener): GdbConnection {
            val process = setup.commandLine().withRedirectErrorStream(true).createProcess()
            return GdbConnection(process.inputStream, process.outputStream, listener) {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }.start()
        }
    }
}
