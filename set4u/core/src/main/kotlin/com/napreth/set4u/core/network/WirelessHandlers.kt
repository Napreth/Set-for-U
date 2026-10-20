package com.napreth.set4u.core.network

import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.compatibility.*
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.state.*

class WirelessQuery : Handler {
    suspend fun read(context: HandlerContext, kind: WirelessKind, scan: Boolean = false, parameters: Map<String, String> = emptyMap()): WirelessSnapshot {
        val result = context.executeTask(WirelessSpec().descriptor, WirelessRequest(kind, if (scan) "scan" else "query", parameters), if (scan) 22_000 else 12_000)
        if (kind != WirelessKind.Wifi || result.fields["notifierLegacy"] != "true") return result
        val legacy = context.execute(ShellCommand.arguments("settings", "get", "global", "wifi_networks_available_notification_on")).requireSuccess().stdout.trim()
        return result.copy(fields = result.fields + ("notifier" to (legacy != "0").toString()))
    }
    suspend fun share(context: HandlerContext, id: String, ssid: String): String = context.executeTask(WifiSpec().descriptor,
        WirelessRequest(WirelessKind.Wifi, "share", mapOf("id" to id, "ssid" to ssid)), 12_000).fields.getValue("qr")
}

object WirelessCapabilities {
    val queries = HandlerDefinition("network.wireless.query", WirelessQuery::class, mapOf("default" to ::WirelessQuery), HandlerEffect.ReadOnly)
    val actions = WirelessKind.entries.associateWith { kind -> SettingDefinition("network.${kind.name.lowercase()}.manage", ValueType.Action,
        binding = { WirelessActionHandler(kind) }, actionParameters = mapOf(
            "operation" to ActionParameter(maxLength = 32), "id" to ActionParameter(false, 16), "ssid" to ActionParameter(false, 128),
            "security" to ActionParameter(false, 20), "password" to ActionParameter(false, 256, sensitive = true),
            "address" to ActionParameter(false, 32), "name" to ActionParameter(false, 248), "type" to ActionParameter(false, 1),
            "band" to ActionParameter(false, 1), "hidden" to ActionParameter(false, 5),
            "option" to ActionParameter(false, 20), "value" to ActionParameter(false, 20), "host" to ActionParameter(false, 253),
            "port" to ActionParameter(false, 5), "exclusions" to ActionParameter(false, 512), "pac" to ActionParameter(false, 2048),
            "ip" to ActionParameter(false, 64), "prefix" to ActionParameter(false, 2), "gateway" to ActionParameter(false, 64), "dns" to ActionParameter(false, 256),
        )) }
    val policies = (listOf(queries.id) + actions.values.map { it.id }).associateWith {
        CapabilityPolicy(identities = setOf(ExecutionIdentity.Root))
    }
}

internal class WirelessActionHandler(private val kind: WirelessKind) : SettingHandler {
    private var request: WirelessRequest? = null
    private var latest = WirelessSnapshot()
    private var connectedBefore = false
    override val verificationPolicy = VerificationPolicy(10, 350)
    override suspend fun inspect(context: SettingContext): SettingSnapshot {
        latest = WirelessQuery().read(context, kind, parameters = request?.parameters.orEmpty())
        return SettingSnapshot(SettingValue.TextValue("ready"))
    }
    override suspend fun prepare(context: SettingContext, desiredValue: SettingValue) {
        val p = (desiredValue as SettingValue.ActionValue).parameters
        val op = requireNotNull(p["operation"])
        val allowed = when (kind) {
            WirelessKind.Wifi -> setOf("connect", "connectSaved", "disconnect", "forget", "configure", "preference")
            WirelessKind.Bluetooth -> setOf("pair", "unpair", "connect", "disconnect", "rename")
            WirelessKind.Hotspot -> setOf("start", "stop", "configure")
        }
        require(op in allowed)
        if (kind == WirelessKind.Wifi && op in setOf("connect", "connectSaved")) check(latest.fields["enabled"] == "true") { "请先开启 WLAN" }
        if (kind == WirelessKind.Wifi && op == "connect") {
            val ssid = requireNotNull(p["ssid"]); require(ssid.toByteArray(Charsets.UTF_8).size in 1..32) { "网络名称应为 1–32 字节" }
            require(p["security"] in setOf("open", "owe", "wpa2", "wpa3", "wep")) { "此安全类型请使用系统设置" }
            if (p["security"] in setOf("wpa2", "wpa3", "wep")) WifiConfigurationValues.password(p.getValue("security"), p["password"].orEmpty())
            if (p["security"] == "wep") check(latest.fields["wepSupported"] != "false" && latest.fields["wep"] != "false") { "请先在高级 WLAN 设置中允许 WEP 网络" }
        }
        if (kind == WirelessKind.Wifi && op == "configure") {
            val saved = latest.items.firstOrNull { it.category == "saved" && it.id == p["id"] && it.name == p["ssid"] } ?: error("已保存网络已变化，请重新选择")
            WifiConfigurationValues.validate(p)
            if (p["option"] == "macMode") check(p["value"] in saved.properties["macModes"].orEmpty().split(',')) { "此 Android 版本不支持这项设置" }
            if (p["option"] == "password") WifiConfigurationValues.password(saved.security, p["password"].orEmpty())
            connectedBefore = latest.fields["networkId"] == p["id"]
        }
        if (kind == WirelessKind.Wifi && op == "preference") {
            require(p["option"] in setOf("wake", "notifier", "wep", "scanAlways") && p["value"] in setOf("true", "false"))
            check(latest.fields[p["option"]] in setOf("true", "false")) { "此 Android 版本不支持这项设置" }
            if (p["option"] == "wep") check(latest.fields["wepSupported"] == "true") { "此设备不支持 WEP" }
        }
        if (kind == WirelessKind.Bluetooth && op == "rename") require(p["name"].orEmpty().toByteArray(Charsets.UTF_8).size in 1..248)
        if (kind == WirelessKind.Hotspot && op == "configure") {
            require(p["ssid"].orEmpty().toByteArray(Charsets.UTF_8).size in 1..32)
            require(p["password"].orEmpty().length in 8..63) { "密码应为 8–63 个字符" }
            require(latest.fields["wifi"] == "false") { "请先关闭热点再修改配置" }
        }
        request = WirelessRequest(kind, op, p - "operation")
    }
    override suspend fun apply(context: SettingContext, desiredValue: SettingValue) {
        val request = checkNotNull(request)
        if (kind == WirelessKind.Wifi && request.operation == "connect") {
            val p = request.parameters
            val hex = p.getValue("ssid").toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 255) }
            val args = mutableListOf("cmd", "wifi", "connect-network", hex, p.getValue("security"))
            if (p["security"] !in setOf("open", "owe")) args += p["password"].orEmpty()
            args += "-x"
            if (p["hidden"] == "true") args += "-h"
            val result = context.execute(ShellRequest(ShellCommand.Arguments(args), 10_000, 4096, evidenceLabel = "wifi.connect")).requireSuccess()
            check(!result.stdout.contains("Connection failed", true)) { "系统拒绝连接请求" }
        } else if (kind == WirelessKind.Wifi && request.operation == "preference" && request.parameters["option"] == "notifier" && latest.fields["notifierLegacy"] == "true") {
            context.execute(ShellCommand.arguments("settings", "put", "global", "wifi_networks_available_notification_on", if (request.parameters["value"] == "true") "1" else "0")).requireSuccess()
        } else context.executeTask(WirelessSpec().descriptor, request, 20_000)
    }
    override fun verify(before: SettingSnapshot, after: SettingSnapshot, desiredValue: SettingValue): Verification {
        if (after.value == null) return Verification.Unknown(FailureReason(after.message ?: "无法读取结果"))
        val r = checkNotNull(request); val p = r.parameters; val f = latest.fields
        val matches = when (kind) {
            WirelessKind.Wifi -> when (r.operation) {
                "connect", "connectSaved" -> f["ssid"] == p["ssid"] && (r.operation != "connectSaved" || f["networkId"] == p["id"])
                "disconnect" -> f["ssid"].isNullOrEmpty()
                "forget" -> latest.items.none { it.category == "saved" && it.id == p["id"] }
                "preference" -> f[p["option"]] == p["value"]
                "configure" -> {
                    val saved = latest.items.firstOrNull { it.category == "saved" && it.id == p["id"] && it.name == p["ssid"] }
                    val values = saved?.properties.orEmpty()
                    when (p["option"]) {
                        "proxy" -> values["proxyMode"] == p["value"] && when (p["value"]) {
                            "manual" -> values["host"] == p["host"] && values["port"] == p["port"] && values["exclusions"].orEmpty().replace(" ", "") == p["exclusions"].orEmpty().replace(" ", "")
                            "pac" -> values["pac"] == p["pac"]
                            else -> true
                        }
                        "ip" -> values["ipMode"] == p["value"] && (p["value"] != "static" || listOf("ip", "prefix", "gateway").all { values[it] == p[it] } && normalizedDns(values["dns"]) == normalizedDns(p["dns"])) &&
                            (!connectedBefore || f["networkId"] == p["id"] && (p["value"] != "static" || f["ip"] == p["ip"]))
                        "password" -> f["passwordMatches"] == "true" && (!connectedBefore || f["networkId"] == p["id"])
                        else -> values[p["option"]] == p["value"] && (p["option"] != "macMode" || !connectedBefore || f["networkId"] == p["id"])
                    }
                }
                else -> false
            }
            WirelessKind.Bluetooth -> when (r.operation) {
                "pair", "unpair" -> f["bonded"] == (r.operation == "pair").toString()
                "connect", "disconnect" -> f["connected"] == (r.operation == "connect").toString()
                "rename" -> f["name"] == p["name"]
                else -> false
            }
            WirelessKind.Hotspot -> if (r.operation == "configure") f["ssid"] == p["ssid"] && f["band"] == p["band"] && f["passwordMatches"] == "true"
                else f[listOf("wifi", "usb", "bluetooth")[(p["type"] ?: "0").toInt()]] == (r.operation == "start").toString()
        }
        return if (matches) Verification.Satisfied else Verification.NotSatisfied(FailureReason("系统尚未确认操作结果；请检查密码、配对确认或设备支持情况"))
    }
    private fun normalizedDns(value: String?) = value.orEmpty().split(',').map { try { java.net.InetAddress.getByName(it.trim()).hostAddress } catch (_: Exception) { it.trim() } }
}
