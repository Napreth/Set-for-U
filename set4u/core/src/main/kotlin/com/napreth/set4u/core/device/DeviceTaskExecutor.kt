package com.napreth.set4u.core.device

import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.session.SettingTarget
import java.io.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

class DeviceTaskException(val error: DeviceTaskError, message: String) : Exception(message)
class DeviceTaskTimeoutException(timeoutMillis: Long) : Exception("Device task timed out after ${timeoutMillis}ms")

internal class DeviceTaskExecutor(private val bridge: IAdbBridge) {
    private val cache = DeviceBundleCache(bridge)
    fun invalidate() = cache.invalidate()
    suspend fun close() = cache.close()
    suspend fun <Request : Any, Response : Any> execute(
        target: SettingTarget,
        descriptor: DeviceTaskDescriptor<Request, Response>,
        request: Request,
        timeoutMillis: Long = 15_000,
    ): Response {
        require(timeoutMillis > 0)
        val payload = descriptor.requestCodec.encode(request)
        require(payload.size <= DeviceWire.MAX_PAYLOAD)
        val token = UUID.randomUUID().toString()
        return withTimeoutOrNull(timeoutMillis) {
                val remote = cache.artifact()
                val process = bridge.openProcess(ShellRequest(ShellCommand.Script(
                    "CLASSPATH=${ShellCommand.arguments(remote).render()} app_process /system/bin --nice-name=set4u-task-$token com.napreth.set4u.core.device.DeviceTaskMain"
                ), timeoutMillis))
                try {
                    coroutineScope {
                        val guard = closeProcessOnCancellation(process)
                        try {
                            val error = async(Dispatchers.IO) { interruptibleIo { captureOutput(process.stderr, AtomicInteger(8192)) } }
                            val response = async(Dispatchers.IO) { interruptibleIo {
                                val input = DataInputStream(process.stdout)
                                DeviceWire.readResponse(input, token).also { check(input.read() == -1) { "Trailing device response data" } }
                            } }
                            withContext(Dispatchers.IO) { interruptibleIo {
                                DataOutputStream(process.stdin).use { DeviceWire.writeRequest(it, DeviceWire.Request(
                                    descriptor.id, descriptor.protocolVersion, DeviceTaskContext(token, target), payload)) }
                            } }
                            check(process.awaitExit() == 0) { "Device task process failed" }
                            error.await()
                            when (val result = response.await()) {
                                is DeviceTaskResult.Success -> descriptor.responseCodec.decode(result.payload)
                                is DeviceTaskResult.Failure -> throw DeviceTaskException(result.error, result.message)
                            }
                        } finally { guard.cancel() }
                    }
                } finally { cleanupWithin { process.close() } }
        } ?: throw DeviceTaskTimeoutException(timeoutMillis)
    }
}
