package com.napreth.set4u.core.execution

import com.napreth.set4u.core.bridge.BridgeState
import com.napreth.set4u.core.bridge.BridgeUnavailableException
import com.napreth.set4u.core.bridge.IAdbBridge
import com.napreth.set4u.core.bridge.ShellRequest
import com.napreth.set4u.core.bridge.ShellResult
import com.napreth.set4u.core.bridge.ShellTimeoutException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal class ShellExecutor(private val bridge: IAdbBridge) {
    private val tasks = com.napreth.set4u.core.device.DeviceTaskExecutor(bridge)
    suspend fun <Request : Any, Response : Any> executeTask(
        target: com.napreth.set4u.core.session.SettingTarget,
        descriptor: com.napreth.set4u.core.device.DeviceTaskDescriptor<Request, Response>,
        request: Request,
        timeoutMillis: Long,
    ): Response {
        currentCoroutineContext().ensureActive()
        requireReady()
        return tasks.execute(target, descriptor, request, timeoutMillis)
    }
    fun invalidateTasks() = tasks.invalidate()
    suspend fun close() = tasks.close()

    fun requireReady() {
        val state = bridge.connectionState.value
        if (state != BridgeState.Ready) throw BridgeUnavailableException(state)
    }

    suspend fun execute(request: ShellRequest): ShellResult {
        currentCoroutineContext().ensureActive()
        requireReady()
        return withTimeoutOrNull(request.timeoutMillis) {
            bridge.execute(request)
        } ?: throw ShellTimeoutException(request.timeoutMillis)
    }
}
