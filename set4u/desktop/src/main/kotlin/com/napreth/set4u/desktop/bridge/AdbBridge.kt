package com.napreth.set4u.desktop.bridge

import com.napreth.set4u.core.bridge.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One explicit serial per bridge. Closing it never stops the shared ADB server. */
class AdbBridge(
    val serial: String, private val adbExecutable: String = "adb",
    private val serverHost: String = "127.0.0.1", private val serverPort: Int = 5037,
) : IAdbBridge {
    override val transport = BridgeTransport.Adb
    init {
        require(serial.isNotBlank() && '\u0000' !in serial)
        require(serverHost.isNotBlank() && serverPort in 1..65535)
    }
    private val mutableState = MutableStateFlow(BridgeState.Disconnected)
    override val connectionState = mutableState.asStateFlow()
    private val lifecycle = Mutex()
    private val processes = ConcurrentHashMap.newKeySet<RemoteProcess>()
    private val transfers = ConcurrentHashMap.newKeySet<Job>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitor: Job? = null

    suspend fun connect() = lifecycle.withLock {
        check(mutableState.value != BridgeState.Closed) { "Bridge is closed" }
        val result = control("get-state")
        val observed = when {
            result.exitCode == 0 && result.stdout.trim() == "device" -> BridgeState.Ready
            "unauthorized" in result.stderr -> BridgeState.Unauthorized
            else -> BridgeState.Disconnected
        }
        mutableState.update { if (it == BridgeState.Closed) it else observed }
        if (mutableState.value != BridgeState.Ready) throw BridgeUnavailableException(mutableState.value)
        if (monitor == null) monitor = scope.launch { monitorConnection() }
    }

    override suspend fun execute(request: ShellRequest): ShellResult =
        executeShell(request) { openProcess(request) }

    override suspend fun openProcess(request: ShellRequest): RemoteProcess = lifecycle.withLock {
        requireReady()
        val shell = ManagedShell(request.command)
        val native = withContext(NonCancellable + Dispatchers.IO) {
            try {
                AdbShellTransport(serverHost, serverPort, serial, shell.start) {
                    mutableState.compareAndSet(BridgeState.Ready, BridgeState.Disconnected)
                }
            } catch (e: java.io.IOException) {
                mutableState.compareAndSet(BridgeState.Ready, if ("unauthorized" in e.message.orEmpty()) BridgeState.Unauthorized else BridgeState.Disconnected)
                throw e
            }
        }
        val remote = StreamRemoteProcess(
            native.stdin, native.stdout, native.stderr, request.timeoutMillis,
            waitFor = { native.awaitExit() },
            terminate = {
                try { control("shell", "-T", shell.cleanup) }
                finally { native.close() }
            },
            onClosed = { processes.remove(it) },
        )
        processes.add(remote)
        if (remote.isClosed) processes.remove(remote)
        if (!currentCoroutineContext().isActive || mutableState.value == BridgeState.Closed) remote.close()
        currentCoroutineContext().ensureActive()
        requireReady()
        remote
    }

    override suspend fun transfer(request: TransferRequest) = coroutineScope {
        if (request.atomic) {
            atomicTransfer(request) { source, destination ->
                java.nio.file.Files.move(source.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            return@coroutineScope
        }
        val job = currentCoroutineContext().job
        lifecycle.withLock { requireReady(); transfers.add(job) }
        try {
            val remotePath = if (request.direction == TransferDirection.ToDevice) request.destination else request.source
            require(remotePath.startsWith("/")) { "The device path must be absolute" }
            val result = when (request.direction) {
                TransferDirection.ToDevice -> control("push", File(request.source).absolutePath, request.destination, timeout = 60_000)
                TransferDirection.FromDevice -> control("pull", request.source, File(request.destination).absolutePath, timeout = 60_000)
            }
            result.requireSuccess()
            Unit
        } finally { transfers.remove(job) }
    }
    override suspend fun close() {
        if (mutableState.getAndUpdate { BridgeState.Closed } == BridgeState.Closed) return
        scope.cancel()
        transfers.toList().forEach { it.cancel() }
        cleanupWithin {
            launch { monitor?.join() }
            processes.toList().forEach { launch { runCatching { it.close() } } }
        }
    }

    private suspend fun monitorConnection() {
        while (currentCoroutineContext().isActive) {
            delay(1_000)
            if (mutableState.value == BridgeState.Ready) {
                val result = try { control("get-state") }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { ShellResult(1) }
                if (result.exitCode != 0 || result.stdout.trim() != "device") {
                    val state = if ("unauthorized" in result.stderr) BridgeState.Unauthorized else BridgeState.Disconnected
                    mutableState.compareAndSet(BridgeState.Ready, state)
                }
            }
        }
    }

    private fun requireReady() {
        if (mutableState.value != BridgeState.Ready) throw BridgeUnavailableException(mutableState.value)
        if (monitor == null) monitor = scope.launch { monitorConnection() }
    }

    private suspend fun control(vararg args: String, timeout: Long = 5_000): ShellResult {
        val native = withContext(NonCancellable + Dispatchers.IO) {
            if (args.firstOrNull() == "shell") {
                ProcessBuilder(adbExecutable, "-H", serverHost, "-P", serverPort.toString(), "-s", serial, "shell", "-T", "sh").start().also {
                    it.outputStream.write((args.last() + "\n").toByteArray(Charsets.UTF_8))
                    it.outputStream.flush()
                }
            } else ProcessBuilder(listOf(adbExecutable, "-H", serverHost, "-P", serverPort.toString(), "-s", serial) + args).start()
        }
        val remote = StreamRemoteProcess(
            native.outputStream, native.inputStream, native.errorStream, timeout,
            waitFor = { native.waitFor() }, terminate = { native.destroyForcibly(); Unit },
        )
        return try {
            withTimeoutOrNull(timeout) { collectShellResult(remote) } ?: throw ShellTimeoutException(timeout)
        } finally { withContext(NonCancellable) { remote.close() } }
    }
}
