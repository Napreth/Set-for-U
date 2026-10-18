package com.napreth.set4u.core.bridge

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

fun fileSha256(file: File): String = file.inputStream().use(::streamSha256)

private fun streamSha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Same-directory staging keeps the previous destination intact on cancellation or copy failure. */
suspend fun IAdbBridge.atomicTransfer(
    request: TransferRequest,
    moveLocal: (File, File) -> Unit = { source, destination -> check(source.renameTo(destination)) { "Atomic rename failed" } },
) {
    if (request.direction == TransferDirection.FromDevice) {
        val target = File(request.destination).absoluteFile
        val temporary = File(target.parentFile, ".${target.name}.set4u-${UUID.randomUUID()}.partial")
        try {
            transfer(request.copy(destination = temporary.path, atomic = false))
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) { moveLocal(temporary, target) }
        } finally { temporary.delete() }
        return
    }
    val expected = withContext(Dispatchers.IO) { fileSha256(File(request.source)) }
    val temporary = request.destination + ".set4u-${UUID.randomUUID()}.partial"
    try {
        transfer(request.copy(destination = temporary, atomic = false))
        val process = openProcess(ShellRequest(ShellCommand.arguments("cat", temporary), 60_000))
        try {
            coroutineScope {
                val guard = closeProcessOnCancellation(process)
                try {
                    val error = async(Dispatchers.IO) { interruptibleIo { captureOutput(process.stderr, AtomicInteger(4096)) } }
                    process.stdin.close()
                    val actual = withContext(Dispatchers.IO) { interruptibleIo { streamSha256(process.stdout) } }
                    check(process.awaitExit() == 0) { "Cannot verify uploaded file" }
                    error.await()
                    check(expected == actual) { "Uploaded file checksum mismatch" }
                } finally { guard.cancel() }
            }
        } finally { cleanupWithin { process.close() } }
        currentCoroutineContext().ensureActive()
        execute(ShellRequest(ShellCommand.arguments("mv", "-f", temporary, request.destination))).requireSuccess()
    } finally {
        cleanupWithin { execute(ShellRequest(ShellCommand.arguments("rm", "-f", temporary), 1_000)) }
    }
}
