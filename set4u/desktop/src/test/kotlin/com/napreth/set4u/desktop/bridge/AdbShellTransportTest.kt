package com.napreth.set4u.desktop.bridge

import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AdbShellTransportTest {
    @Test fun preservesBinaryStreamsAndHandlesFragmentedPackets() = withServer({ socket ->
        handshake(socket)
        val received = ByteArrayOutputStream()
        val input = DataInputStream(socket.getInputStream())
        while (true) {
            val id = input.readUnsignedByte()
            var size = 0
            repeat(4) { size = size or (input.readUnsignedByte() shl (8 * it)) }
            val bytes = ByteArray(size).also(input::readFully)
            if (id == 4) break
            assertEquals(0, id)
            received.write(bytes)
        }
        assertArrayEquals(ByteArray(256) { it.toByte() }, received.toByteArray())
        packet(socket, 1, received.toByteArray())
        packet(socket, 2, byteArrayOf(0, 13, 10, -1))
        packet(socket, 3, byteArrayOf(42))
    }) { port ->
        AdbShellTransport("127.0.0.1", port, "fake", "command") {}.use { process ->
            process.stdin.write(ByteArray(256) { it.toByte() })
            process.stdin.close()
            assertArrayEquals(ByteArray(256) { it.toByte() }, process.stdout.readBytes())
            assertArrayEquals(byteArrayOf(0, 13, 10, -1), process.stderr.readBytes())
            assertEquals(42, process.awaitExit())
        }
    }

    @Test fun reportsDisconnectWithoutInventingAnExitCode() = withServer({ handshake(it) }) { port ->
        val disconnected = AtomicBoolean(false)
        AdbShellTransport("127.0.0.1", port, "fake", "command") { disconnected.set(true) }.use { process ->
            try { process.awaitExit(); fail("Expected disconnect") } catch (_: EOFException) { }
            assertTrue(disconnected.get())
        }
    }

    @Test fun reportsServerRejectionAndClosesSocket() = withServer({ socket ->
        assertEquals("host:transport:fake", request(socket))
        socket.getOutputStream().apply { write("FAIL0013device unauthorized".toByteArray()); flush() }
        assertEquals(-1, socket.getInputStream().read())
    }) { port ->
        try {
            AdbShellTransport("127.0.0.1", port, "fake", "command") {}
            fail("Expected rejection")
        } catch (e: IOException) { assertEquals("device unauthorized", e.message) }
    }

    private fun request(socket: Socket): String {
        val input = DataInputStream(socket.getInputStream())
        val size = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII).toInt(16)
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }
    private fun handshake(socket: Socket) {
        assertEquals("host:transport:fake", request(socket))
        socket.getOutputStream().apply { write("OKAY".toByteArray()); flush() }
        assertEquals("shell,v2,raw:command", request(socket))
        socket.getOutputStream().apply { write("OKAY".toByteArray()); flush() }
    }
    private fun packet(socket: Socket, id: Int, bytes: ByteArray) {
        val header = ByteArray(5)
        header[0] = id.toByte()
        repeat(4) { header[it + 1] = (bytes.size ushr (8 * it)).toByte() }
        (header + bytes).toList().chunked(3).forEach {
            socket.getOutputStream().apply { write(it.toByteArray()); flush() }
        }
    }
    private fun withServer(server: (Socket) -> Unit, client: (Int) -> Unit) {
        ServerSocket(0).use { listener ->
            listener.soTimeout = 5_000
            val failure = AtomicReference<Throwable>()
            val thread = Thread {
                try { listener.accept().use { it.soTimeout = 5_000; server(it) } }
                catch (e: Throwable) { failure.set(e) }
            }.apply { isDaemon = true; start() }
            try { client(listener.localPort) } finally { thread.join(6_000) }
            assertFalse("Fake ADB server did not stop", thread.isAlive)
            failure.get()?.let { throw AssertionError("Fake ADB server failed", it) }
        }
    }
}