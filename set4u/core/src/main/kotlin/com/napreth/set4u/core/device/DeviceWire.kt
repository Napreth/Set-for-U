package com.napreth.set4u.core.device

import com.napreth.set4u.core.session.*
import java.io.DataInputStream
import java.io.DataOutputStream

/** One bounded request and response per process; stdout is reserved for these frames. */
object DeviceWire {
    const val MAGIC = 0x53345531
    const val MAX_PAYLOAD = 1_048_576

    data class Request(val taskId: String, val version: Int, val context: DeviceTaskContext, val payload: ByteArray)

    fun writeRequest(output: DataOutputStream, request: Request) {
        require(request.payload.size <= MAX_PAYLOAD)
        output.writeInt(MAGIC)
        output.writeUTF(request.context.requestId)
        output.writeUTF(request.taskId)
        output.writeInt(request.version)
        when (val target = request.context.target) {
            is AppTarget -> { output.writeByte(1); output.writeInt(target.userId); output.writeUTF(target.packageName) }
            is DeviceTarget -> { output.writeByte(0); output.writeInt(target.userId ?: -1) }
        }
        writePayload(output, request.payload)
        output.flush()
    }

    fun readRequest(input: DataInputStream): Request {
        check(input.readInt() == MAGIC) { "Invalid device wire version" }
        val requestId = input.readUTF().also { require(it.length <= 128) }
        val taskId = input.readUTF().also { require(it.length <= 256) }
        val version = input.readInt()
        val target = when (input.readUnsignedByte()) {
            0 -> DeviceTarget(input.readInt().let { if (it == -1) null else it })
            1 -> AppTarget(input.readInt(), input.readUTF())
            else -> error("Invalid target type")
        }
        return Request(taskId, version, DeviceTaskContext(requestId, target), readPayload(input))
    }

    fun writeResponse(output: DataOutputStream, requestId: String, result: DeviceTaskResult) {
        output.writeInt(MAGIC)
        output.writeUTF(requestId)
        when (result) {
            is DeviceTaskResult.Success -> { output.writeByte(0); writePayload(output, result.payload) }
            is DeviceTaskResult.Failure -> {
                output.writeByte(1)
                output.writeUTF(result.error.name)
                output.writeUTF(result.message.take(2048))
            }
        }
        output.flush()
    }

    fun readResponse(input: DataInputStream, requestId: String): DeviceTaskResult {
        check(input.readInt() == MAGIC && input.readUTF() == requestId) { "Invalid device response identity" }
        return when (input.readUnsignedByte()) {
            0 -> DeviceTaskResult.Success(readPayload(input))
            1 -> DeviceTaskResult.Failure(DeviceTaskError.valueOf(input.readUTF()), input.readUTF().also { require(it.length <= 2048) })
            else -> error("Invalid device response status")
        }
    }

    private fun readPayload(input: DataInputStream): ByteArray {
        val length = input.readInt()
        require(length in 0..MAX_PAYLOAD) { "Device frame exceeds payload limit" }
        return ByteArray(length).also(input::readFully)
    }
    private fun writePayload(output: DataOutputStream, bytes: ByteArray) {
        require(bytes.size <= MAX_PAYLOAD)
        output.writeInt(bytes.size)
        output.write(bytes)
    }
}
