package com.napreth.set4u.core.network.form

import com.napreth.set4u.core.form.*
import com.napreth.set4u.core.network.*

/** Business forms build requests only. The page chooses where to show them and how to submit them. */
object WirelessForms {
    fun connect(item: WirelessItem? = null): FormDefinition<WirelessRequest> {
        val ssid = TextField("ssid", "网络名称", item?.name.orEmpty(), validate = { violation { WirelessInputValues.networkName(it) } })
        val security = ChoiceField("security", "安全性", item?.security?.takeIf { it in personalSecurity } ?: "wpa2",
            listOf(FormOption("open", "无"), FormOption("owe", "增强型开放网络"), FormOption("wpa2", "WPA2-Personal"), FormOption("wpa3", "WPA3-Personal"), FormOption("wep", "WEP")))
        val password = PasswordField("password", "密码", visibleWhen = { it[security] !in setOf("open", "owe") })
        val hidden = ToggleField("hidden", "隐藏网络", summary = "即使未扫描到该WiFi也尝试连接")
        fun parameters(v: FormValues) = buildMap {
            put("ssid", v[ssid]); put("security", v[security]); put("hidden", v[hidden].toString())
            if (v.contains(password)) put("password", v[password])
        }
        return FormDefinition("wifi.connect", "连接网络", listOf(ssid, security, password, hidden), validator = FormValidator { v ->
            violation { WirelessInputValues.connect(parameters(v)) }?.let { listOf(FormError(if (v.contains(password)) password.key else null, it)) }.orEmpty()
        }) { WirelessRequest(WirelessKind.Wifi, "connect", parameters(it)) }
    }

    fun proxy(network: WifiNetwork): FormDefinition<WirelessRequest> {
        val p = network.properties
        val mode = ChoiceField("value", "代理", p["proxyMode"]?.takeIf { it in setOf("none", "manual", "pac") } ?: "none",
            listOf(FormOption("none", "无"), FormOption("manual", "手动"), FormOption("pac", "PAC")))
        val host = TextField("host", "代理主机", p["host"].orEmpty(), visibleWhen = { it[mode] == "manual" })
        val port = TextField("port", "代理端口", p["port"]?.takeUnless { it == "-1" }.orEmpty(), integer = true, visibleWhen = { it[mode] == "manual" })
        val exclusions = TextField("exclusions", "不使用代理的地址", p["exclusions"].orEmpty(), visibleWhen = { it[mode] == "manual" })
        val pac = TextField("pac", "PAC 地址", p["pac"].orEmpty(), visibleWhen = { it[mode] == "pac" })
        fun parameters(v: FormValues) = buildMap {
            put("option", "proxy"); put("value", v[mode])
            if (v.contains(host)) { put("host", v[host].trim()); put("port", v[port].trim()); put("exclusions", v[exclusions].trim()) }
            if (v.contains(pac)) put("pac", v[pac].trim())
        }
        return configuration("proxy", "代理", network, listOf(mode, host, port, exclusions, pac), ::parameters)
    }

    fun ip(network: WifiNetwork, current: Map<String, String> = emptyMap()): FormDefinition<WirelessRequest> {
        val p = network.properties
        val mode = ChoiceField("value", "IP 设置", if (p["ipMode"] == "static") "static" else "dhcp", listOf(FormOption("dhcp", "DHCP"), FormOption("static", "静态")))
        val addresses = listOf("ip" to "IPv4 地址", "prefix" to "网络前缀长度", "gateway" to "网关", "dns" to "DNS").map { (key, title) ->
            TextField(key, title, p[key]?.takeIf(String::isNotBlank) ?: current[key].orEmpty(), integer = key == "prefix", visibleWhen = { it[mode] == "static" })
        }
        return configuration("ip", "IP 设置", network, listOf(mode) + addresses) { v -> buildMap {
            put("option", "ip"); put("value", v[mode])
            if (v[mode] == "static") addresses.forEach { put(it.key, v[it].trim()) }
        } }
    }

    fun password(network: WifiNetwork): FormDefinition<WirelessRequest> {
        val password = PasswordField("password", "新密码", validate = { violation { WifiConfigurationValues.password(network.security, it) } })
        return configuration("password", "修改密码", network, listOf(password)) { mapOf("option" to "password", "value" to "", "password" to it[password]) }
    }

    private fun configuration(id: String, title: String, network: WifiNetwork, fields: List<FormField<*>>, parameters: (FormValues) -> Map<String, String>): FormDefinition<WirelessRequest> =
        FormDefinition("wifi.$id", title, fields, validator = checked { WifiConfigurationValues.validate(parameters(it)) }) { v ->
            val saved = checkNotNull(network.saved) { "Saved network required" }
            WirelessRequest(WirelessKind.Wifi, "configure", parameters(v) + mapOf("id" to saved.id, "ssid" to saved.name))
        }

    fun bluetoothName(initial: String): FormDefinition<WirelessRequest> {
        val name = TextField("name", "设备名称", initial, validate = { violation { WirelessInputValues.bluetoothName(it) } })
        return FormDefinition("bluetooth.name", "设备名称", listOf(name)) { WirelessRequest(WirelessKind.Bluetooth, "rename", mapOf("name" to it[name])) }
    }

    fun hotspot(current: Map<String, String>): FormDefinition<WirelessRequest> {
        val ssid = TextField("ssid", "网络名称", current["ssid"].orEmpty())
        val password = PasswordField("password", "新密码")
        val band = ChoiceField("band", "频段", current["band"]?.takeIf { it in setOf("1", "2", "3") } ?: "3",
            listOf(FormOption("1", "2.4 GHz"), FormOption("2", "5 GHz"), FormOption("3", "自动")))
        fun parameters(v: FormValues) = mapOf("ssid" to v[ssid], "password" to v[password], "band" to v[band])
        return FormDefinition("hotspot.configure", "配置热点", listOf(ssid, password, band), validator = checked { WirelessInputValues.hotspot(parameters(it)) }) {
            WirelessRequest(WirelessKind.Hotspot, "configure", parameters(it))
        }
    }

    private val personalSecurity = listOf("open", "owe", "wpa2", "wpa3", "wep")
    private fun violation(check: () -> Unit): String? = try { check(); null } catch (e: IllegalArgumentException) { e.message ?: "请选择有效选项" }
    private fun checked(check: (FormValues) -> Unit) = FormValidator { v -> violation { check(v) }?.let { listOf(FormError(message = it)) }.orEmpty() }
}
