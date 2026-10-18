package com.napreth.set4u.core.bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Authorizes root over a transport; an optional, explicit shell mode executes as UID 2000. */
class RootBridge(private val delegate: IAdbBridge, private val emulateShell: Boolean = false, private val moveLocal: (File, File) -> Unit = { source, target -> check(source.renameTo(target)) { "Atomic rename failed" } }) : IAdbBridge {
    private enum class Launcher { AlreadyRoot, Su, AospSu }
    private enum class ShellLauncher { Su, AospSu }
    private var launcher: Launcher? = null
    private var shellLauncher: ShellLauncher? = null
    private val expectedUid get() = if (emulateShell) 2000 else 0
    private val state = MutableStateFlow(BridgeState.Disconnected)
    override val connectionState = state.asStateFlow()
    override val transport get() = delegate.transport
    private val processes = ConcurrentHashMap.newKeySet<RemoteProcess>()
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init { scope.launch { delegate.connectionState.collect { if (it != BridgeState.Ready && !closed.get()) state.value = it } } }

    suspend fun connect() {
        check(!closed.get())
        check(delegate.connectionState.value == BridgeState.Ready)
        val candidates = if (delegate.environment().uid == 0) listOf(Launcher.AlreadyRoot) else listOf(Launcher.Su, Launcher.AospSu)
        for (candidate in candidates) {
            val result = delegate.execute(ShellRequest(wrapRoot(ShellCommand.arguments("id", "-u"), candidate), 5_000, 1024))
            currentCoroutineContext().ensureActive()
            check(!closed.get()) { "Root bridge closed during authorization" }
            if (result.exitCode == 0 && !result.outputTruncated && result.stdout.trim() == "0") {
                launcher = candidate
                if (emulateShell) selectShellLauncher()
                state.update { if (closed.get()) BridgeState.Closed else BridgeState.Ready }
                check(!closed.get()) { "Root bridge closed during authorization" }
                return
            }
        }
        state.update { if (closed.get()) BridgeState.Closed else BridgeState.Unauthorized }
        throw BridgeUnavailableException(BridgeState.Unauthorized)
    }
    private suspend fun selectShellLauncher() {
        for (candidate in ShellLauncher.entries) {
            val probe = wrapShell(ShellCommand.arguments("id", "-u"), candidate)
            val result = delegate.execute(ShellRequest(probe, 5_000, 1024))
            currentCoroutineContext().ensureActive()
            check(!closed.get()) { "Root bridge closed during shell identity verification" }
            if (result.exitCode == 0 && !result.outputTruncated && result.stdout.trim() == "2000") {
                shellLauncher = candidate
                return
            }
        }
        launcher = null; shellLauncher = null
        state.update { if (closed.get()) BridgeState.Closed else BridgeState.Disconnected }
        throw ShellIdentityUnavailableException()
    }
    private fun wrapShell(command: ShellCommand, candidate: ShellLauncher = checkNotNull(shellLauncher)): ShellCommand {
        val script = "[ \"\$(id -u)\" = 2000 ] || exit 126\nexec " + ShellCommand.arguments("sh", "-c", command.render()).render()
        return when (candidate) {
            ShellLauncher.Su -> ShellCommand.arguments("su", "2000", "-c", script)
            ShellLauncher.AospSu -> ShellCommand.arguments("su", "2000", "sh", "-c", script)
        }
    }
    // Root has already been authorized. Ask that provider for UID 2000 directly to avoid
    // multiplying shell quoting (and exceeding ADB's service-request limit) through nested su.
    private fun wrap(command: ShellCommand) = if (emulateShell) wrapShell(command) else wrapRoot(command, checkNotNull(launcher))
    private fun wrapRoot(command: ShellCommand, candidate: Launcher): ShellCommand {
        // Even after authorization changes, an invocation must either run as UID 0 or fail.
        val script = "[ \"\$(id -u)\" = 0 ] || exit 126\nexec " + ShellCommand.arguments("sh", "-c", command.render()).render()
        return when (candidate) {
            Launcher.AlreadyRoot -> ShellCommand.arguments("sh", "-c", script)
            Launcher.Su -> ShellCommand.arguments("su", "-c", script)
            Launcher.AospSu -> ShellCommand.arguments("su", "0", "sh", "-c", script)
        }
    }
    override suspend fun execute(request: ShellRequest) = executeShell(request) { openProcess(request) }
    override suspend fun environment(): ExecutionEnvironment = try {
        super<IAdbBridge>.environment().also { check(it.uid == expectedUid) { "Execution identity was lost" } }
    } catch (exception: CancellationException) { throw exception }
    catch (exception: Exception) {
        if (!closed.get()) state.value = if (delegate.connectionState.value == BridgeState.Ready) BridgeState.Unauthorized else delegate.connectionState.value
        throw exception
    }
    override suspend fun openProcess(request: ShellRequest): RemoteProcess {
        if (closed.get() || state.value != BridgeState.Ready || delegate.connectionState.value != BridgeState.Ready) throw BridgeUnavailableException(state.value)
        val command = ManagedShell(request.command)
        val raw = delegate.openProcess(request.copy(command = wrap(ShellCommand.Script(command.start))))
        val remote = object : RemoteProcess {
            private val ended = AtomicBoolean(false)
            override val stdin get() = raw.stdin
            override val stdout get() = raw.stdout
            override val stderr get() = raw.stderr
            override suspend fun awaitExit() = raw.awaitExit()
            override suspend fun close() {
                if (!ended.compareAndSet(false, true)) return
                processes.remove(this)
                try { cleanupWithin(1500) { delegate.execute(ShellRequest(wrap(ShellCommand.Script(command.cleanup)), 1000, 1024)) } }
                finally { raw.close() }
            }
        }
        processes.add(remote)
        if (closed.get() || !currentCoroutineContext().isActive) remote.close()
        currentCoroutineContext().ensureActive()
        if (closed.get() || state.value != BridgeState.Ready) throw BridgeUnavailableException(state.value)
        return remote
    }
    override suspend fun transfer(request: TransferRequest) {
        if (request.atomic) { atomicTransfer(request, moveLocal); return }
        val path = if (request.direction == TransferDirection.ToDevice) request.destination else request.source
        require(path.startsWith("/"))
        val command = if (request.direction == TransferDirection.ToDevice) ShellCommand.Script("umask 077; cat > " + ShellCommand.arguments(path).render()) else ShellCommand.arguments("cat", path)
        val process = openProcess(ShellRequest(command, 60_000, 4096))
        try {
            withTimeout(60_000) { coroutineScope {
                val guard = closeProcessOnCancellation(process)
                try {
                    val error = async(Dispatchers.IO) { interruptibleIo { captureOutput(process.stderr, AtomicInteger(4096)) } }
                    if (request.direction == TransferDirection.ToDevice) {
                        val out = async(Dispatchers.IO) { interruptibleIo { captureOutput(process.stdout, AtomicInteger(4096)) } }
                        withContext(Dispatchers.IO) { interruptibleIo { File(request.source).inputStream().use { input -> process.stdin.use { input.copyTo(it) } } } }
                        check(process.awaitExit() == 0) { "Root file upload failed" }; out.await()
                    } else {
                        process.stdin.close()
                        withContext(Dispatchers.IO) { interruptibleIo { File(request.destination).outputStream().use { process.stdout.copyTo(it) } } }
                        check(process.awaitExit() == 0) { "Root file download failed" }
                    }
                    error.await()
                } finally { guard.cancel() }
            } }
        } finally { cleanupWithin { process.close() } }
    }
    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        state.value = BridgeState.Closed
        scope.cancel()
        try { cleanupWithin { processes.toList().forEach { launch { it.close() } } } }
        finally { delegate.close() }
    }
}

class ShellIdentityUnavailableException : Exception("The root provider cannot execute as UID 2000")
