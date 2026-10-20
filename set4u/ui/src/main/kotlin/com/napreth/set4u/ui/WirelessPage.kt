package com.napreth.set4u.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.napreth.set4u.core.bridge.ExecutionIdentity
import com.napreth.set4u.core.network.*

internal fun LazyListScope.wirelessPage(state: AppState, controller: AppController) {
    val kind = when (state.page) { "network.wifi" -> WirelessKind.Wifi; "network.bluetooth" -> WirelessKind.Bluetooth; "network.hotspot" -> WirelessKind.Hotspot; else -> return }
    val s = state.wireless
    val snapshot = s.snapshot
    val f = snapshot?.fields.orEmpty()
    val devices = snapshot?.items.orEmpty()
    val t = AppStrings(state.locale)::text
    val root = state.identity == ExecutionIdentity.Root
    val canEdit = root && snapshot != null && !s.applying
    if (!root) item("wireless.permission") {
        ThemeGroup { ThemeRow(t("此页面的无线管理功能需要 root 权限"), action = controller::openWirelessSystem) { ThemeValue(t("系统设置"), true) } }
    } else if (s.error != null) item("wireless.error") {
        ThemeGroup { ThemeRow(t("无线操作未完成"), action = { controller.message("无线操作未完成", s.error.orEmpty()) }) { ThemeValue(t("查看详情"), true) } }
    }
    if (kind == WirelessKind.Wifi) { wifiPage(state, controller); return }
    if (kind == WirelessKind.Hotspot) {
        item("hotspot.options") {
            ThemeGroup {
                listOf("WLAN 热点", "USB 网络共享", "蓝牙网络共享").forEachIndexed { index, title ->
                    if (index > 0) ThemeDivider()
                    val enabled = f[listOf("wifi", "usb", "bluetooth")[index]] == "true"
                    ThemeRow(t(title)) { ThemeSwitch(t(title), enabled, canEdit) { controller.wirelessAction(if (enabled) "stop" else "start", mapOf("type" to index.toString())) } }
                }
            }
            ThemeGroup {
                ThemeRow(t("网络名称")) { ThemeValue(f["ssid"].orEmpty()) }
                ThemeDivider()
                ThemeRow(t("配置热点"), action = if (canEdit && f["wifi"] == "false") controller::configureHotspot else null,
                    summary = if (f["wifi"] == "true") t("关闭热点后可修改配置") else "") { ThemeValue("", true) }
            }
        }
        return
    }
    if (kind == WirelessKind.Bluetooth) item("bluetooth.name") {
        ThemeGroup { ThemeRow(t("设备名称"), action = if (canEdit) controller::renameBluetooth else null) { ThemeValue(f["name"].orEmpty(), true) } }
    }
    val known = devices.filter { it.category == "paired" }
    if (known.isNotEmpty()) {
        item("wireless.saved.heading") { ThemeText(t("已配对设备"), Modifier.padding(start = 16.dp, bottom = 8.dp), size = 13, color = LocalColors.current.secondary) }
        wirelessItems("known", known, canEdit, controller, t)
        item("wireless.saved.spacing") { Spacer(Modifier.height(24.dp)) }
    }
    item("wireless.scan") {
        ThemeRow(t("附近设备")) {
            ThemeTextAction(t(if (s.scanning) "正在扫描…" else "扫描"), controller::scanWireless, enabled = root && !s.loading && !s.applying && f["enabled"] == "true")
        }
    }
    val found = devices.filter { it.category == "available" }
    if (found.isEmpty() && !s.loading && root) item("wireless.empty") {
        ThemeText(t("未扫描到蓝牙设备"), Modifier.padding(16.dp), size = 15, color = LocalColors.current.secondary)
    }
    wirelessItems("found", found, canEdit, controller, t)
    item("wireless.system") {
        Spacer(Modifier.height(24.dp))
        ThemeRow(t("系统设置"), action = controller::openWirelessSystem) { ThemeValue("", true) }
    }
}

private fun LazyListScope.wirelessItems(prefix: String, items: List<WirelessItem>, enabled: Boolean, controller: AppController, t: (String) -> String) {
    itemsIndexed(items, key = { _, item -> "$prefix:${item.id}:${item.security}" }) { index, item ->
        val shape = RoundedCornerShape(topStart = if (index == 0) 20.dp else 0.dp, topEnd = if (index == 0) 20.dp else 0.dp,
            bottomStart = if (index == items.lastIndex) 20.dp else 0.dp, bottomEnd = if (index == items.lastIndex) 20.dp else 0.dp)
        Column(Modifier.clip(shape).background(LocalColors.current.surface)) {
            ThemeRow(item.name, summary = item.detail, action = if (enabled) ({ controller.selectWirelessItem(item) }) else null) {
                ThemeValue(if (item.connected) t("已连接") else if (item.security.isNotEmpty()) item.security.uppercase() else "", true)
            }
            if (index != items.lastIndex) ThemeDivider()
        }
    }
}
