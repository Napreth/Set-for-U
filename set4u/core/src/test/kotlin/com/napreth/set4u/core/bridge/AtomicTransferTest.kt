package com.napreth.set4u.core.bridge

import com.napreth.set4u.core.expectFailure
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class AtomicTransferTest {
    private class FilesBridge : IAdbBridge {
        override val connectionState = MutableStateFlow(BridgeState.Ready)
        val files = ConcurrentHashMap<String, ByteArray>()
        var interrupted = false
        var corrupt = false
        override suspend fun transfer(request: TransferRequest) {
            check(!request.atomic)
            files[request.destination] = if (corrupt || interrupted) byteArrayOf(1) else File(request.source).readBytes()
            if (interrupted) throw CancellationException("Interrupted copy")
        }
        override suspend fun execute(request: ShellRequest): ShellResult {
            val args = (request.command as ShellCommand.Arguments).values
            when (args[0]) {
                "rm" -> files.remove(args.last())
                "mv" -> files[args.last()] = checkNotNull(files.remove(args[2]))
                else -> error("Unexpected command")
            }
            return ShellResult(0)
        }
        override suspend fun openProcess(request: ShellRequest): RemoteProcess {
            val path = (request.command as ShellCommand.Arguments).values.last()
            return object : RemoteProcess {
                override val stdin = ByteArrayOutputStream()
                override val stdout = ByteArrayInputStream(checkNotNull(files[path]))
                override val stderr = ByteArrayInputStream(byteArrayOf())
                override suspend fun awaitExit() = 0
                override suspend fun close() {}
            }
        }
        override suspend fun close() {}
    }

    @Test fun interruptedOrCorruptUploadLeavesPreviousFileAndRemovesStagingFile() = runBlocking {
        val local = File.createTempFile("set4u-transfer-test", ".bin").apply { writeBytes(byteArrayOf(2, 3, 4)) }
        try {
            for (cancel in listOf(true, false)) {
                val bridge = FilesBridge().apply { files["/target"] = byteArrayOf(9); interrupted = cancel; corrupt = !cancel }
                expectFailure<Exception> { bridge.atomicTransfer(TransferRequest(local.path, "/target")) }
                assertEquals(setOf("/target"), bridge.files.keys)
                assertArrayEquals(byteArrayOf(9), bridge.files["/target"])
            }
        } finally { local.delete() }
    }

    @Test fun uploadCommitsOnlyAfterReadingBackTheMatchingChecksum() = runBlocking {
        val local = File.createTempFile("set4u-transfer-test", ".bin").apply { writeBytes(byteArrayOf(2, 3, 4)) }
        try {
            val bridge = FilesBridge().apply { files["/target"] = byteArrayOf(9) }
            bridge.atomicTransfer(TransferRequest(local.path, "/target"))
            assertEquals(setOf("/target"), bridge.files.keys)
            assertArrayEquals(local.readBytes(), bridge.files["/target"])
        } finally { local.delete() }
    }
}
