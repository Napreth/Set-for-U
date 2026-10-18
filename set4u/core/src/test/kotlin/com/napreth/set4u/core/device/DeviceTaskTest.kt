package com.napreth.set4u.core.device

import com.napreth.set4u.core.Target
import com.napreth.set4u.core.expectFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeviceTaskTest {
    private val codec = object : DeviceCodec<String> {
        override fun encode(value: String) = value.toByteArray(Charsets.UTF_8)
        override fun decode(bytes: ByteArray) = bytes.toString(Charsets.UTF_8).also { require(it.isNotEmpty()) }
    }
    private val descriptor = DeviceTaskDescriptor("test.echo", codec, codec)
    private val context = DeviceTaskContext("request-1", Target)
    private fun registry(limit: Int = 1024) = DeviceTaskRegistry(listOf(DeviceTaskRegistration(descriptor, EchoTask::class.java)), limit)

    @Test fun dispatcherCreatesAnEntryPerRequestAndPassesTheExplicitTarget() = runTest {
        val registry = registry()
        repeat(2) {
            val result = registry.execute(descriptor.id, 1, context, codec.encode("中文")) as DeviceTaskResult.Success
            assertEquals("${Target}:request-1:中文:1", codec.decode(result.payload))
            val bytes = result.payload
            bytes[0] = 0
            assertNotEquals(0.toByte(), result.payload[0])
        }
    }

    @Test fun unknownTaskProtocolAndInvalidPayloadAreRejectedBeforeExecution() = runTest {
        val registry = registry()
        assertEquals(DeviceTaskError.UnknownTask, (registry.execute("missing", 1, context, byteArrayOf()) as DeviceTaskResult.Failure).error)
        assertEquals(DeviceTaskError.ProtocolMismatch, (registry.execute(descriptor.id, 2, context, codec.encode("value")) as DeviceTaskResult.Failure).error)
        assertEquals(DeviceTaskError.InvalidRequest, (registry.execute(descriptor.id, 1, context, byteArrayOf()) as DeviceTaskResult.Failure).error)
        assertEquals(DeviceTaskError.InvalidRequest, (registry(1).execute(descriptor.id, 1, context, codec.encode("too big")) as DeviceTaskResult.Failure).error)
    }

    @Test fun cancellationPropagatesAndTaskErrorsRemainExplicit() = runTest {
        val registry = registry()
        expectFailure<CancellationException> { registry.execute(descriptor.id, 1, context, codec.encode("cancel")) }
        val failure = registry.execute(descriptor.id, 1, context, codec.encode("fail")) as DeviceTaskResult.Failure
        assertEquals(DeviceTaskError.ExecutionFailed, failure.error)
        assertEquals("Device operation failed", failure.message)
        assertEquals(DeviceTaskError.ExecutionFailed, (registry(1).execute(descriptor.id, 1, context, codec.encode("a")) as DeviceTaskResult.Failure).error)
    }

    @Test fun registrationValidatesStaticIdentityAndRejectsDuplicateEntries() = runTest {
        expectFailure<IllegalArgumentException> { DeviceTaskRegistration(DeviceTaskDescriptor("wrong.id", codec, codec), EchoTask::class.java) }
        expectFailure<IllegalArgumentException> { DeviceTaskRegistration(DeviceTaskDescriptor("test.echo", codec, codec, 2), EchoTask::class.java) }
        expectFailure<IllegalArgumentException> { DeviceTaskRegistration(descriptor, UnmarkedTask::class.java) }
        val registration = DeviceTaskRegistration(descriptor, EchoTask::class.java)
        expectFailure<IllegalArgumentException> { DeviceTaskRegistry(listOf(registration, registration)) }
    }

    @DeviceEntry("test.echo")
    class EchoTask : DeviceTask<String, String>() {
        private var executions = 0
        override suspend fun execute(context: DeviceTaskContext, request: String): String {
            if (request == "cancel") throw CancellationException("Cancelled")
            if (request == "fail") error("Device operation failed")
            return "${context.target}:${context.requestId}:$request:${++executions}"
        }
    }

    class UnmarkedTask : DeviceTask<String, String>() {
        override suspend fun execute(context: DeviceTaskContext, request: String) = request
    }
}
