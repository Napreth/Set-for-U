package com.napreth.set4u.bridge

import com.napreth.set4u.core.bridge.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/** App-UID transport for RootBridge and read-only local metadata. Never treated as privileged. */
class LocalProcessBridge : IAdbBridge {
    private val state = MutableStateFlow(BridgeState.Ready)
    override val connectionState = state.asStateFlow()
    override val transport = BridgeTransport.Local
    private val processes = ConcurrentHashMap.newKeySet<RemoteProcess>()
    override suspend fun execute(request: ShellRequest) = executeShell(request) { openProcess(request) }
    override suspend fun openProcess(request: ShellRequest): RemoteProcess {
        check(state.value == BridgeState.Ready)
        val native = withContext(NonCancellable + Dispatchers.IO) { ProcessBuilder("sh", "-c", request.command.render()).start() }
        val process = StreamRemoteProcess(native.outputStream, native.inputStream, native.errorStream, request.timeoutMillis,
            waitFor = { native.waitFor() }, terminate = { native.destroy(); Unit }, onClosed = { processes.remove(it) })
        processes.add(process)
        if (!currentCoroutineContext().isActive || state.value == BridgeState.Closed) process.close()
        currentCoroutineContext().ensureActive()
        if (state.value != BridgeState.Ready) throw BridgeUnavailableException(state.value)
        return process
    }
    override suspend fun close() {
        if (state.getAndUpdate { BridgeState.Closed } == BridgeState.Closed) return
        cleanupWithin { processes.toList().forEach { launch { it.close() } } }
    }
}
