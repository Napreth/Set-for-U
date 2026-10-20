package com.napreth.set4u.core.network

import com.napreth.set4u.core.device.*
import java.io.*

enum class WirelessKind { Wifi, Bluetooth, Hotspot }
class WirelessRequest(val kind: WirelessKind, val operation: String = "query", parameters: Map<String, String> = emptyMap()) {
    val parameters = parameters.toMap()
    init { require(operation.length in 1..40 && parameters.size <= 16 && parameters.all { it.key.length <= 40 && it.value.length <= 4096 }) }
    override fun toString() = "WirelessRequest($kind, $operation, keys=${parameters.keys})"
}
data class WirelessItem(val id: String, val name: String, val category: String, val security: String = "", val detail: String = "", val connected: Boolean = false,
    val properties: Map<String, String> = emptyMap())
data class WirelessSnapshot(val fields: Map<String, String> = emptyMap(), val items: List<WirelessItem> = emptyList())

class WirelessSpec : WirelessTaskSpec("network.wireless")
class WifiSpec : WirelessTaskSpec("network.wifi")
abstract class WirelessTaskSpec(id: String) : DeviceTaskSpec<WirelessRequest, WirelessSnapshot>() {
    override val descriptor = DeviceTaskDescriptor(id, object : DeviceCodec<WirelessRequest> {
        override fun encode(value: WirelessRequest) = output { writeInt(value.kind.ordinal); writeUTF(value.operation); map(value.parameters) }
        override fun decode(bytes: ByteArray) = input(bytes) { WirelessRequest(WirelessKind.entries[readInt()], readUTF(), map()) }
    }, object : DeviceCodec<WirelessSnapshot> {
        override fun encode(value: WirelessSnapshot) = output {
            map(value.fields); require(value.items.size <= 512); writeInt(value.items.size)
            value.items.forEach { writeUTF(it.id); writeUTF(it.name); writeUTF(it.category); writeUTF(it.security); writeUTF(it.detail); writeBoolean(it.connected); map(it.properties) }
        }
        override fun decode(bytes: ByteArray) = input(bytes) {
            WirelessSnapshot(map(), List(readInt().also { require(it in 0..512) }) { WirelessItem(readUTF(), readUTF(), readUTF(), readUTF(), readUTF(), readBoolean(), map()) })
        }
    }, protocolVersion = 2)
}
private fun output(block: DataOutputStream.() -> Unit) = ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()
private fun <T> input(bytes: ByteArray, block: DataInputStream.() -> T): T = DataInputStream(ByteArrayInputStream(bytes)).use { it.block().also { _ -> check(it.read() == -1) } }
private fun DataOutputStream.map(value: Map<String, String>) { require(value.size <= 64); writeInt(value.size); value.forEach { (k, v) -> writeUTF(k); writeUTF(v) } }
private fun DataInputStream.map() = buildMap { repeat(readInt().also { require(it in 0..64) }) { put(readUTF().also { require(it.length <= 128) }, readUTF().also { require(it.length <= 4096) }) } }
