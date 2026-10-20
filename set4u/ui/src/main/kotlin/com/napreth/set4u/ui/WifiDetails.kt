package com.napreth.set4u.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.napreth.set4u.core.network.WifiNetwork

@Composable
internal fun WifiDetails(network: WifiNetwork, current: Map<String, String>, canEdit: Boolean, enabled: Boolean, controller: AppController) {
    val t = LocalStrings.current::text
    val p = network.properties
    val saved = canEdit && network.saved != null
    val state = if (network.connected) "已连接" else if (network.available != null) "未连接" else "不在范围内"
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(AppIcons.Network, null, Modifier.size(58.dp), tint = LocalColors.current.accent)
        Spacer(Modifier.height(14.dp))
        ThemeText(network.name, size = 22, bold = true)
        ThemeText(t(state), Modifier.padding(top = 6.dp), size = 14, color = LocalColors.current.secondary)
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            WifiAction(t(if (network.connected) "断开连接" else "连接"), AppIcons.Link,
                if (network.connected) canEdit else enabled && (network.saved != null || network.available != null)) {
                if (network.connected) controller.wirelessAction("disconnect") else controller.connectWifiNetwork(network)
            }
            if (network.saved != null) {
                WifiAction(t("忘记网络"), AppIcons.Delete, saved) { controller.forgetWifi(network) }
                WifiAction(t("分享"), AppIcons.Share, saved && network.security != "enterprise") { controller.shareWifi(network) }
            }
        }
    }
    if (network.saved != null) ThemeGroup {
        WifiNetworkSwitch("自动连接", "autoJoin", network, saved, controller)
        ThemeDivider()
        WifiNetworkSwitch("共享网络", "shared", network, saved, controller)
        if (p.containsKey("allowEdit")) { ThemeDivider(); WifiNetworkSwitch("允许其他用户修改网络", "allowEdit", network, saved, controller) }
    }
    val f = if (network.connected) current else network.available?.properties.orEmpty()
    val facts = buildList {
        val rssi = f["rssi"]?.toIntOrNull()
        if (rssi != null) add("信号强度" to "${t(when { rssi >= -55 -> "极佳"; rssi >= -65 -> "良好"; rssi >= -75 -> "一般"; else -> "较弱" })} ($rssi dBm)")
        f["frequency"]?.toIntOrNull()?.takeIf { it > 0 }?.let { add("频率" to "${when { it >= 5925 -> "6"; it >= 4900 -> "5"; else -> "2.4" }} GHz ($it MHz)") }
        if (network.security.isNotEmpty()) add("安全性" to wifiSecurity(network.security, t))
        f["standard"]?.toIntOrNull()?.let { standard -> mapOf(1 to "Legacy", 4 to "Wi-Fi 4", 5 to "Wi-Fi 5", 6 to "Wi-Fi 6", 7 to "Wi-Fi 7")[standard]?.let { add("WLAN 标准" to it) } }
        if (network.connected) {
            for ((key, label) in listOf("mac" to "设备 MAC 地址", "bssid" to "接入点 MAC 地址", "ip" to "IPv4 地址", "ipv6" to "IPv6 地址", "gateway" to "网关", "dns" to "DNS")) {
                f[key]?.takeIf(String::isNotBlank)?.let { add(label to it) }
            }
            f["prefix"]?.toIntOrNull()?.takeIf { it in 0..32 }?.let { prefix ->
                val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
                add("子网掩码" to (3 downTo 0).joinToString(".") { ((mask ushr (it * 8)) and 255).toString() })
            }
            listOf("txSpeed" to "传输链接速度", "rxSpeed" to "接收链接速度").forEach { (key, label) -> f[key]?.toIntOrNull()?.takeIf { it >= 0 }?.let { add(label to "$it Mbps") } }
        }
    }
    if (facts.isNotEmpty()) ThemeGroup(t("网络详情")) {
        facts.forEachIndexed { index, (label, value) ->
            if (index > 0) ThemeDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                ThemeText(t(label), size = 16)
                Spacer(Modifier.height(3.dp))
                SelectionContainer { ThemeText(value, color = LocalColors.current.secondary, size = 14) }
            }
        }
    }
    if (network.saved != null) ThemeGroup(t("高级选项")) {
        ThemeRow(t("代理"), action = if (saved && "proxyMode" in p) ({ controller.editWifiProxy(network) }) else null) {
            ThemeValue(t(when (p["proxyMode"]) { "none" -> "无"; "manual" -> "手动"; "pac" -> "PAC"; else -> "不可用" }), saved && "proxyMode" in p)
        }
        ThemeDivider()
        ThemeRow(t("IP 设置"), action = if (saved && "ipMode" in p) ({ controller.editWifiIp(network) }) else null) {
            ThemeValue(when (p["ipMode"]) { "dhcp" -> "DHCP"; "static" -> t("静态"); else -> t("不可用") }, saved && "ipMode" in p)
        }
        if (p.containsKey("macMode")) {
            ThemeDivider()
            val codes = p["macModes"].orEmpty().split(',').filter(String::isNotBlank)
            val labels = codes.map { t(when (it) { "0" -> "使用设备 MAC 地址"; "1" -> "使用随机 MAC 地址"; "2" -> "每次连接使用随机 MAC 地址"; else -> "自动" }) }
            if (codes.size <= 1) ThemeRow(t("隐私")) { ThemeValue(labels.firstOrNull().orEmpty()) }
            else ThemeDropdownRow(t("隐私"), labels, codes.indexOf(p["macMode"]).coerceAtLeast(0), enabled = saved) { controller.configureWifi(network, "macMode", codes[it]) }
        }
        if (p.containsKey("metered")) {
            ThemeDivider()
            ThemeDropdownRow(t("按流量计费"), listOf("自动检测", "按流量计费", "不按流量计费").map(t), p["metered"]?.toIntOrNull()?.coerceIn(0, 2) ?: 0, enabled = saved) {
                controller.configureWifi(network, "metered", it.toString())
            }
        }
        ThemeDivider()
        WifiNetworkSwitch("隐藏网络", "hidden", network, saved, controller, summary = "即使未扫描到该WiFi也尝试连接")
        if (network.security in setOf("wpa2", "wpa3", "wep")) {
            ThemeDivider()
            ThemeRow(t("修改密码"), action = if (saved) ({ controller.editWifiPassword(network) }) else null) { ThemeValue("", true) }
        }
    }
}

@Composable
private fun WifiNetworkSwitch(title: String, key: String, network: WifiNetwork, enabled: Boolean, controller: AppController, summary: String = "") {
    val t = LocalStrings.current::text
    val value = network.properties[key]?.toBooleanStrictOrNull()
    ThemeRow(t(title), summary = t(summary), action = if (enabled && value != null) ({ controller.configureWifi(network, key, (value != true).toString()) }) else null) {
        ThemeSwitch(t(title), value == true, enabled && value != null) { controller.configureWifi(network, key, (value != true).toString()) }
    }
}

@Composable
private fun WifiAction(title: String, icon: ImageVector, enabled: Boolean, action: () -> Unit) {
    Column(Modifier.widthIn(min = 80.dp).clickable(enabled = enabled, role = Role.Button, onClick = action).semantics { contentDescription = title }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(26.dp), tint = LocalColors.current.accent.copy(alpha = if (enabled) 1f else .35f))
        }
        ThemeText(title, size = 13, color = if (enabled) LocalColors.current.accent else LocalColors.current.secondary)
    }
}
