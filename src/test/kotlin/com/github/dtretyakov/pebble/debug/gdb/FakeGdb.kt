package com.github.dtretyakov.pebble.debug.gdb

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * gdb's side of the pipe, for tests: hears the debugger's commands and answers each with the
 * lines a rule gives — lines from a recorded session, where `{t}` stands for the command's token.
 * Lines the target would send by itself, such as a stop, are sent with [emit].
 */
class FakeGdb {

    private val toGdb = Pipe()
    private val fromGdb = Pipe()

    /** What the debugger reads, and where it writes. */
    val input: InputStream get() = fromGdb.input
    val output: OutputStream get() = toGdb.output

    val commands = LinkedBlockingQueue<String>()
    private val heard = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val rules = java.util.concurrent.CopyOnWriteArrayList<Pair<Regex, (String) -> List<String>>>()

    init {
        thread(name = "fake gdb", isDaemon = true) {
            val reader = toGdb.input.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                val token = line.takeWhile { it.isDigit() }
                val command = line.substring(token.length)
                heard += command
                val answer = rules.lastOrNull { it.first.matches(command) }?.second?.invoke(command) ?: listOf("{t}^done")
                commands += command
                answer.forEach { emit(it.replace("{t}", token)) }
            }
        }
    }

    /** Answers commands matching [pattern] with [lines]; a later rule wins over an earlier one. */
    fun answer(pattern: String, vararg lines: String) {
        rules += Regex(pattern) to { _ -> lines.toList() }
    }

    fun emit(line: String) = synchronized(fromGdb) {
        fromGdb.output.write("$line\n".toByteArray(Charsets.UTF_8))
        fromGdb.output.flush()
    }

    /** The next command heard that matches, skipping the rest; fails after 10 seconds. */
    fun next(pattern: String, pump: () -> Unit = {}): String {
        val regex = Regex(pattern)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val command = commands.poll(50, TimeUnit.MILLISECONDS)
            if (command != null && regex.matches(command)) return command
            pump()
        }
        throw AssertionError("no command like $pattern; heard:\n${heard.joinToString("\n")}")
    }

    fun heard(): List<String> = synchronized(heard) { heard.toList() }

    fun exit() {
        fromGdb.output.close()
    }

    /** A pipe that any thread may write to: java.io's piped streams want their writer alive. */
    private class Pipe {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        private val end = ByteArray(0)

        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len > 0) chunks += b.copyOfRange(off, off + len)
            }
            override fun close() {
                chunks += end
            }
        }

        val input = object : InputStream() {
            private var current = ByteArray(0)
            private var position = 0
            private var ended = false

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (ended) return -1
                if (position >= current.size) {
                    val next = chunks.take()
                    if (next === end) {
                        ended = true
                        return -1
                    }
                    current = next
                    position = 0
                }
                val count = minOf(len, current.size - position)
                System.arraycopy(current, position, b, off, count)
                position += count
                return count
            }
        }
    }
}
