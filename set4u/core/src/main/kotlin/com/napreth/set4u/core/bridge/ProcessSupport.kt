package com.napreth.set4u.core.bridge

import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

suspend fun executeShell(request: ShellRequest, open: suspend () -> RemoteProcess): ShellResult =
    withTimeoutOrNull(request.timeoutMillis) {
        val process = open()
        collectShellResult(process, request.maxOutputBytes)
    } ?: throw ShellTimeoutException(request.timeoutMillis)

/** Shared stream handling; launching and transporting processes remain platform responsibilities. */
suspend fun collectShellResult(process: RemoteProcess, maxOutputBytes: Int = 2 * 1024 * 1024): ShellResult = coroutineScope {
    require(maxOutputBytes >= 0)
    val guard = closeProcessOnCancellation(process)
    try {
        process.stdin.close()
        val budget = AtomicInteger(maxOutputBytes)
        val out = async(Dispatchers.IO) { interruptibleIo { captureOutput(process.stdout, budget) } }
        val err = async(Dispatchers.IO) { interruptibleIo { captureOutput(process.stderr, budget) } }
        val code = process.awaitExit()
        val stdout = out.await()
        val stderr = err.await()
        ShellResult(code, stdout.text, stderr.text, stdout.truncated, stderr.truncated)
    } finally {
        guard.cancel()
        cleanupWithin { process.close() }
    }
}

data class CapturedOutput(val text: String, val truncated: Boolean)

/** Retains a bounded prefix, then keeps draining so a writer cannot deadlock on a full pipe. */
fun captureOutput(input: InputStream, budget: AtomicInteger): CapturedOutput {
    val output = ByteArrayOutputStream(minOf(budget.get(), 8192))
    val buffer = ByteArray(8192)
    var truncated = false
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        var available: Int
        do { available = budget.get() } while (!budget.compareAndSet(available, maxOf(0, available - count)))
        val kept = available.coerceAtMost(count)
        output.write(buffer, 0, kept)
        if (kept < count) truncated = true
    }
    return CapturedOutput(output.toString(Charsets.UTF_8.name()), truncated)
}

/** Owns the deadline even when a caller uses openProcess without immediately awaiting it. */
class StreamRemoteProcess(
    override val stdin: OutputStream,
    override val stdout: InputStream,
    override val stderr: InputStream,
    private val timeoutMillis: Long,
    waitFor: () -> Int,
    private val terminate: suspend () -> Unit,
    private val onClosed: (RemoteProcess) -> Unit = {},
) : RemoteProcess {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timedOut = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val closeDone = CompletableDeferred<Unit>()
    val isClosed: Boolean get() = closed.get()
    private val exit = scope.async { runInterruptible { waitFor() } }
    private val deadline = scope.launch {
        delay(timeoutMillis)
        timedOut.set(true)
        close()
    }

    init { exit.invokeOnCompletion { deadline.cancel() } }

    override suspend fun awaitExit(): Int {
        try {
            val code = exit.await()
            if (timedOut.get()) throw ShellTimeoutException(timeoutMillis)
            return code
        } catch (e: CancellationException) {
            close()
            if (timedOut.get()) throw ShellTimeoutException(timeoutMillis)
            throw e
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) {
            withContext(NonCancellable) { withTimeoutOrNull(2_000) { closeDone.await() } }
            return
        }
        try {
            onClosed(this)
            scope.cancel()
            cleanupWithin {
                launch { runCatching { terminate() } }
                listOf(stdin, stdout, stderr).forEach { stream -> launch { runCatching { stream.close() } } }
            }
        } finally { closeDone.complete(Unit) }
    }
}

/** A private process group lets both transports stop descendants as well as the shell. */
class ManagedShell(command: ShellCommand) {
    private val marker = "/data/local/tmp/set4u-${UUID.randomUUID()}.pid"
    private fun quote(value: String) = ShellCommand.arguments(value).render()
    val start: String = "setsid -w sh -c " + quote(
        "umask 077; echo \"\$\$ \$(sed 's/.*) //' /proc/\$\$/stat | cut -d ' ' -f 20)\" > ${quote(marker)}; " +
            "exec sh -c ${quote(command.render())}",
    )
    val cleanup: String = "for n in 1 2 3 4 5 6 7 8 9 10; do " +
        "[ -f ${quote(marker)} ] && break; sleep 0.1; done; " +
        "if read p s < ${quote(marker)} 2>/dev/null; then " +
        "case \$p in ''|*[!0-9]*) ;; *) " +
        "if [ \"\$(sed 's/.*) //' /proc/\$p/stat 2>/dev/null | cut -d ' ' -f 20)\" = \"\$s\" ]; then " +
        "kill -KILL -- -\$p 2>/dev/null; fi ;; esac; fi; rm -f ${quote(marker)}"
}
