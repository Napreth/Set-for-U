package com.napreth.set4u.core.bridge

import kotlinx.coroutines.*
import java.io.IOException

suspend fun <T> interruptibleIo(block: () -> T): T = try {
    runInterruptible(block = block)
} catch (exception: IOException) {
    // Some streams report interruption as InterruptedIOException rather than InterruptedException.
    currentCoroutineContext().ensureActive()
    throw exception
}

/** Cleanup is best effort. An uncooperative IPC/close cannot extend the caller's deadline. */
suspend fun cleanupWithin(timeoutMillis: Long = 2_000, block: suspend CoroutineScope.() -> Unit): Boolean {
    val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val task = worker.async(block = block)
    return try {
        withContext(NonCancellable) {
            withTimeoutOrNull(timeoutMillis) { task.await(); true } ?: false
        }
    } catch (_: Exception) {
        false
    } finally {
        worker.cancel()
    }
}

/** Starts closing streams as soon as the parent is cancelled, before waiting for reader children. */
fun CoroutineScope.closeProcessOnCancellation(process: RemoteProcess): Job = launch(start = CoroutineStart.UNDISPATCHED) {
    try { awaitCancellation() }
    finally { cleanupWithin { process.close() } }
}
