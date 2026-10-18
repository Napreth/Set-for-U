package com.napreth.set4u.core.device.tasks

import com.napreth.set4u.core.device.*
import java.io.*

data class RuntimeProbeRequest(val text: String, val delayMillis: Long = 0)
data class RuntimeProbeResult(val sdk: Int, val uid: Int, val target: String, val text: String)

/** Shared protocol and codecs, usable on a desktop with no Android classes. */
class RuntimeProbeSpec : DeviceTaskSpec<RuntimeProbeRequest, RuntimeProbeResult>() {
    override val descriptor = DeviceTaskDescriptor("set4u.runtime.probe", object : DeviceCodec<RuntimeProbeRequest> {
        override fun encode(value: RuntimeProbeRequest) = encodeData { writeUTF(value.text); writeLong(value.delayMillis) }
        override fun decode(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes)).use {
            RuntimeProbeRequest(it.readUTF(), it.readLong().also { delay -> require(delay in 0..120_000) })
        }
    }, object : DeviceCodec<RuntimeProbeResult> {
        override fun encode(value: RuntimeProbeResult) = encodeData { writeInt(value.sdk); writeInt(value.uid); writeUTF(value.target); writeUTF(value.text) }
        override fun decode(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes)).use {
            RuntimeProbeResult(it.readInt(), it.readInt(), it.readUTF(), it.readUTF())
        }
    })
}

private fun encodeData(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
    DataOutputStream(it).use(block)
}.toByteArray()
