package com.napreth.set4u.core.network

/** UI forms and execution handlers use the same business constraints. */
object WirelessInputValues {
    fun connect(parameters: Map<String, String>) {
        networkName(parameters["ssid"].orEmpty())
        val security = parameters["security"]
        require(security in setOf("open", "owe", "wpa2", "wpa3", "wep")) { "此安全类型请使用系统设置" }
        if (security in setOf("wpa2", "wpa3", "wep")) WifiConfigurationValues.password(checkNotNull(security), parameters["password"].orEmpty())
    }
    fun networkName(value: String) { require(value.toByteArray(Charsets.UTF_8).size in 1..32) { "网络名称应为 1–32 字节" } }
    fun bluetoothName(value: String) { require(value.toByteArray(Charsets.UTF_8).size in 1..248) { "请输入有效的设备名称" } }
    fun hotspot(parameters: Map<String, String>) {
        networkName(parameters["ssid"].orEmpty())
        require(parameters["password"].orEmpty().length in 8..63) { "密码应为 8–63 个字符" }
        require(parameters["band"] in setOf("1", "2", "3")) { "请选择有效选项" }
    }
}
