package com.napreth.set4u.desktop.bridge

import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** ADB shell-v2 over the local server socket preserves bytes, stderr and the remote exit code. */
internal class AdbShellTransport(host: String, port: Int, serial: String, command: String, private val onDisconnect: () -> Unit) : Closeable {
    private val socket = Socket()
    private val input: InputStream
    private val output: OutputStream
    private val outPipe = PipedOutputStream()
    private val errPipe = PipedOutputStream()
    val stdout = PipedInputStream(outPipe, 65536)
    val stderr = PipedInputStream(errPipe, 65536)
    private val exit = CompletableFuture<Int>()
    private val closed = AtomicBoolean(false)
    private val stdinClosed = AtomicBoolean(false)
    private val writeLock = Any()

    init {
        try {
            socket.connect(InetSocketAddress(host, port), 5_000)
            socket.soTimeout = 5_000
            input = socket.getInputStream()
            output = socket.getOutputStream()
            service("host:transport:$serial")
            service("shell,v2,raw:$command")
            socket.soTimeout = 0
            Thread({ readFrames() }, "adb-shell-$serial").apply { isDaemon = true; start() }
        } catch (e: Throwable) { socket.close(); throw e }
    }

    val stdin: OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!stdinClosed.get()) { "Process stdin is closed" }
            var position = offset
            while (position < offset + length) {
                val size = minOf(65536, offset + length - position)
                frame(0, bytes, position, size)
                position += size
            }
        }
        override fun close() {
            if (stdinClosed.compareAndSet(false, true) && !closed.get()) runCatching { frame(4, byteArrayOf(), 0, 0) }
        }
    }

    private fun service(name: String) {
        val bytes = name.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65535) { "ADB service request is too large" }
        output.write("%04x".format(bytes.size).toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
        when (readExactly(4).toString(Charsets.US_ASCII)) {
            "OKAY" -> Unit
            "FAIL" -> {
                val length = readExactly(4).toString(Charsets.US_ASCII).toInt(16)
                throw IOException(readExactly(length).toString(Charsets.UTF_8))
            }
            else -> throw IOException("Invalid ADB handshake")
        }
    }

    private fun frame(id: Int, bytes: ByteArray, offset: Int, length: Int) = synchronized(writeLock) {
        output.write(id)
        repeat(4) { output.write((length ushr (8 * it)) and 255) }
        output.write(bytes, offset, length)
        output.flush()
    }

    private fun readFrames() {
        try {
            while (true) {
                val header = readExactly(5)
                val size = (0..3).fold(0) { n, i -> n or ((header[i + 1].toInt() and 255) shl (8 * i)) }
                if (size !in 0..16_777_216) throw IOException("Invalid ADB shell packet size: $size")
                val bytes = readExactly(size)
                when (header[0].toInt()) {
                    1 -> outPipe.write(bytes)
                    2 -> errPipe.write(bytes)
                    3 -> {
                        if (bytes.isEmpty()) throw IOException("Missing ADB shell exit code")
                        exit.complete(bytes[0].toInt() and 255)
                        return
                    }
                    else -> throw IOException("Unexpected ADB shell packet: ${header[0]}")
                }
            }
        } catch (e: Throwable) {
            if (!closed.get() && (e is EOFException || e is java.net.SocketException)) onDisconnect()
            exit.completeExceptionally(e)
        }
        finally { runCatching { outPipe.close() }; runCatching { errPipe.close() } }
    }

    private fun readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(bytes, read, size - read)
            if (count < 0) throw EOFException("ADB connection closed")
            read += count
        }
        return bytes
    }

    fun awaitExit(): Int = try { exit.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            socket.close()
            stdout.close()
            stderr.close()
            exit.completeExceptionally(IOException("ADB process closed"))
        }
    }
}