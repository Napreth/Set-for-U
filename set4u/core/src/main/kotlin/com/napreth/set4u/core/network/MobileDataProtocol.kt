package com.napreth.set4u.core.network

import com.napreth.set4u.core.device.*
import java.io.*

data class MobileDataRequest(val desired: Boolean? = null, val expectedSubscriptionId: Int? = null) {
    init { require(expectedSubscriptionId == null || expectedSubscriptionId >= 0) }
}

enum class MobileDataStatus { Ready, NoActiveSubscription, DefaultSubscriptionChanged }
data class MobileDataState(val status: MobileDataStatus, val subscriptionId: Int = -1, val enabled: Boolean? = null) {
    init {
        require(if (status == MobileDataStatus.Ready) subscriptionId >= 0 && enabled != null
            else subscriptionId == -1 && enabled == null)
    }
}

class MobileDataSpec : DeviceTaskSpec<MobileDataRequest, MobileDataState>() {
    override val descriptor = DeviceTaskDescriptor("network.mobile_data", object : DeviceCodec<MobileDataRequest> {
        override fun encode(value: MobileDataRequest) = encode { writeByte(when (value.desired) { null -> -1; false -> 0; true -> 1 }); writeInt(value.expectedSubscriptionId ?: -1) }
        override fun decode(bytes: ByteArray) = decode(bytes) {
            val desired = when (readByte().toInt()) { -1 -> null; 0 -> false; 1 -> true; else -> error("Invalid data state") }
            val expected = readInt().also { require(it >= -1) }
            MobileDataRequest(desired, expected.takeIf { it >= 0 })
        }
    }, object : DeviceCodec<MobileDataState> {
        override fun encode(value: MobileDataState) = encode {
            writeByte(value.status.ordinal); writeInt(value.subscriptionId)
            writeByte(when (value.enabled) { null -> -1; false -> 0; true -> 1 })
        }
        override fun decode(bytes: ByteArray) = decode(bytes) {
            val status = MobileDataStatus.entries.getOrNull(readUnsignedByte()) ?: error("Invalid data result")
            val subscriptionId = readInt()
            val enabled = when (readByte().toInt()) { -1 -> null; 0 -> false; 1 -> true; else -> error("Invalid data result") }
            MobileDataState(status, subscriptionId, enabled)
        }
    })
}

private fun encode(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()
private fun <T> decode(bytes: ByteArray, block: DataInputStream.() -> T): T = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
    input.block().also { check(input.read() == -1) { "Trailing data" } }
}
