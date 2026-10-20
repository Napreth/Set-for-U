package com.napreth.set4u.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.napreth.set4u.core.bridge.ExecutionIdentity
import com.napreth.set4u.core.network.*

internal fun LazyListScope.wifiPage(state: AppState, controller: AppController) {
    val networks = WifiNetworks.from(state.wireless.snapshot)
    val fields = state.wireless.snapshot?.fields.orEmpty()
    val t = AppStrings(state.locale)::text
    val canEdit = state.identity == ExecutionIdentity.Root && state.wireless.snapshot != null && !state.wireless.applying
    val enabled = canEdit && fields["enabled"] == "true"
    when (state.wifiScreen) {
        WifiScreen.Overview -> {
            if (fields["enabled"] == "true") {
                item("wifi.scan") {
                    ThemeRow(t("网络")) {
                        ThemeTextAction(t(if (state.wireless.scanning) "正在扫描…" else "扫描"), controller::scanWireless,
                            enabled = enabled && !state.wireless.loading)
                    }
                }
                wifiRows("wifi.networks", networks.visible, enabled, controller, addNetwork = true)
                item("wifi.networks.spacing") { Spacer(Modifier.height(24.dp)) }
            }
            item("wifi.preferences") {
                ThemeGroup {
                    ThemeRow(t("已保存网络"), action = controller::showSavedWifi) { ThemeValue(networks.saved.size.toString(), true) }
                    ThemeDivider()
                    WifiPreference("自动开启 WLAN", "wake", fields, canEdit && (fields["wake"] == "true" || fields["scanAlways"] == "true" && fields["locationEnabled"] == "true"), controller,
                        if (fields["scanAlways"] != "true" || fields["locationEnabled"] != "true") "需要开启位置信息和 WLAN 扫描" else "附近有已保存的网络时自动开启 WLAN")
                    ThemeDivider()
                    WifiPreference("附近有公共网络时发出通知", "notifier", fields, canEdit, controller)
                    ThemeDivider()
                    ThemeRow(t("安装证书"), action = controller::openWifiCertificate) { ThemeValue("", true) }
                    if (fields["directSupported"] == "true") { ThemeDivider(); ThemeRow(t("WLAN 直连"), action = controller::openWifiDirect) { ThemeValue("", true) } }
                }
                ThemeGroup { ThemeRow(t("高级 WLAN 设置"), action = controller::showAdvancedWifi) { ThemeValue("", true) } }
            }
        }
        WifiScreen.Saved -> {
            if (networks.saved.isEmpty() && !state.wireless.loading) item("wifi.saved.empty") {
                ThemeText(t("没有已保存的网络"), Modifier.padding(16.dp), size = 15, color = LocalColors.current.secondary)
            }
            wifiRows("wifi.saved", networks.saved, true, controller, detailsOnly = true)
        }
        WifiScreen.Details -> state.wifiSelection?.let { selection ->
            item("wifi.details") { WifiDetails(networks.updated(selection), fields, canEdit, enabled, controller) }
        }
        WifiScreen.Advanced -> item("wifi.advanced") {
            ThemeGroup {
                WifiPreference("WLAN 扫描", "scanAlways", fields, canEdit, controller, "关闭 WLAN 后仍允许系统扫描网络")
                ThemeDivider()
                WifiPreference("允许连接 WEP 网络", "wep", fields, canEdit && fields["wepSupported"] == "true", controller,
                    if (fields["wepSupported"] == "false") "此设备不支持 WEP" else "WEP 是较旧的安全协议，其安全性较低")
            }
        }
    }
}

@Composable
private fun WifiPreference(title: String, key: String, fields: Map<String, String>, enabled: Boolean, controller: AppController, summary: String = "") {
    val t = LocalStrings.current::text
    val value = fields[key]?.toBooleanStrictOrNull()
    ThemeRow(t(title), action = if (enabled && value != null) ({ controller.wifiPreference(key, value != true) }) else null,
        summary = t(if (value == null && fields.isNotEmpty()) fields["${key}Error"] ?: "此 Android 版本不支持这项设置" else summary)) {
        ThemeSwitch(t(title), value == true, enabled && value != null) { controller.wifiPreference(key, value != true) }
    }
}

private fun LazyListScope.wifiRows(prefix: String, networks: List<WifiNetwork>, enabled: Boolean, controller: AppController, addNetwork: Boolean = false, detailsOnly: Boolean = false) {
    val count = networks.size + if (addNetwork) 1 else 0
    itemsIndexed(networks, key = { _, network -> "$prefix:${network.key}" }) { index, network ->
        WifiCell(index, count) {
            WifiRow(network, if (enabled) ({ if (detailsOnly) controller.showWifiDetails(network) else controller.connectWifiNetwork(network) }) else null, { controller.showWifiDetails(network) })
        }
    }
    if (addNetwork) item("$prefix:add") {
        WifiCell(networks.size, count) {
            ThemeRow(LocalStrings.current.text("添加网络"), action = if (enabled) ({ controller.connectWifi() }) else null) { ThemeValue("", true) }
        }
    }
}

@Composable
private fun WifiCell(index: Int, count: Int, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(topStart = if (index == 0) 20.dp else 0.dp, topEnd = if (index == 0) 20.dp else 0.dp,
        bottomStart = if (index == count - 1) 20.dp else 0.dp, bottomEnd = if (index == count - 1) 20.dp else 0.dp)
    Column(Modifier.clip(shape).background(LocalColors.current.surface)) {
        content()
        if (index < count - 1) ThemeDivider()
    }
}

@Composable
private fun WifiRow(network: WifiNetwork, action: (() -> Unit)?, details: () -> Unit) {
    val t = LocalStrings.current::text
    ThemeRow(network.name, icon = AppIcons.Network, action = action,
        summary = t(if (network.connected) "已连接" else if (network.saved != null) "已保存" else "")) {
        if (network.security !in setOf("", "open", "owe")) Icon(AppIcons.Lock, null, Modifier.size(14.dp), tint = LocalColors.current.secondary)
        IconButton(details, Modifier.size(40.dp)) {
            Icon(AppIcons.Info, "${t("网络详情")}：${network.name}", Modifier.size(23.dp), tint = LocalColors.current.accent)
        }
    }
}

internal fun wifiSecurity(security: String, t: (String) -> String) = when (security) {
    "open" -> t("开放网络"); "owe" -> "Enhanced Open"; "enterprise" -> t("企业网络"); else -> security.uppercase()
}
