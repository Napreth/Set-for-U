package com.napreth.set4u.core.device

import com.napreth.set4u.core.Target
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class DeviceWireTest {
    @Test fun malformedLengthsAndMismatchedIdentitiesAreRejectedBeforeAllocation() {
        val data = ByteArrayOutputStream()
        DataOutputStream(data).use {
            it.writeInt(DeviceWire.MAGIC); it.writeUTF("request"); it.writeByte(0); it.writeInt(Int.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) { DeviceWire.readResponse(DataInputStream(ByteArrayInputStream(data.toByteArray())), "request") }
        assertThrows(IllegalStateException::class.java) { DeviceWire.readResponse(DataInputStream(ByteArrayInputStream(data.toByteArray())), "other") }
    }
    @Test fun explicitTargetAndBinaryPayloadSurviveFraming() {
        val payload = byteArrayOf(0, -1, 10, 13)
        val data = ByteArrayOutputStream()
        DeviceWire.writeRequest(DataOutputStream(data), DeviceWire.Request("task", 2, DeviceTaskContext("request", Target), payload))
        val result = DeviceWire.readRequest(DataInputStream(ByteArrayInputStream(data.toByteArray())))
        assertEquals(Target, result.context.target)
        assertEquals("task", result.taskId)
        assertEquals(2, result.version)
        assertArrayEquals(payload, result.payload)
    }
}
