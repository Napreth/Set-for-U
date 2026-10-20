package com.napreth.set4u.core.network

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

/** Shared input checks run before a device-side change is admitted. */
object WifiConfigurationValues {
    fun validate(p: Map<String, String>) {
        val value = p["value"].orEmpty()
        when (p["option"]) {
            "autoJoin", "shared", "allowEdit", "hidden" -> require(value in setOf("true", "false")) { "请选择有效选项" }
            "metered" -> require(value in setOf("0", "1", "2"))
            "macMode" -> require(value in setOf("0", "1", "2", "3"))
            "password" -> require(p["password"].orEmpty().isNotEmpty()) { "请输入密码" }
            "proxy" -> when (value) {
                "none" -> Unit
                "manual" -> {
                    require(p["host"].orEmpty().matches(Regex("[A-Za-z0-9_.:\\[\\]-]{1,253}"))) { "请输入有效的代理主机" }
                    require(p["port"]?.toIntOrNull() in 1..65535) { "端口应为 1–65535" }
                    require(p["exclusions"].orEmpty().split(',').all { it.trim().matches(Regex("[A-Za-z0-9_.*:-]*")) }) { "排除列表格式无效" }
                }
                "pac" -> {
                    val uri = try { URI(p["pac"].orEmpty()) } catch (_: Exception) { null }
                    require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty()) { "请输入有效的 PAC 地址" }
                }
                else -> error("请选择有效选项")
            }
            "ip" -> when (value) {
                "dhcp" -> Unit
                "static" -> {
                    require(ipv4(p["ip"].orEmpty())) { "请输入有效的 IPv4 地址" }
                    require(p["prefix"]?.toIntOrNull() in 1..32) { "网络前缀长度应为 1–32" }
                    require(ipv4(p["gateway"].orEmpty())) { "请输入有效的网关" }
                    require(p["dns"].orEmpty().split(',').let { it.isNotEmpty() && it.size <= 4 && it.all { ip -> address(ip.trim()) } }) { "请输入有效的 DNS 地址" }
                }
                else -> error("请选择有效选项")
            }
            else -> error("不支持的网络配置")
        }
    }
    fun password(security: String, password: String) {
        if (security == "wep") require(password.matches(Regex("[0-9a-fA-F]{10}|[0-9a-fA-F]{26}|[0-9a-fA-F]{58}")) || password.toByteArray(Charsets.UTF_8).size in setOf(5, 13, 29)) { "WEP 密码长度或格式无效" }
        else require(password.length in 8..63 || security == "wpa2" && password.matches(Regex("[0-9a-fA-F]{64}"))) { "密码应为 8–63 个字符" }
    }
    private fun ipv4(s: String) = s.split('.').let { parts -> parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 } }
    private fun address(s: String): Boolean = if (':' !in s) ipv4(s) else s.matches(Regex("[0-9a-fA-F:.]+")) && try { InetAddress.getByName(s) !is Inet4Address } catch (_: Exception) { false }
}

object WifiSharing {
    fun payload(ssid: String, security: String, password: String, hidden: Boolean): String {
        require(security in setOf("open", "owe", "wpa2", "wpa3", "wep")) { "企业网络不支持二维码分享" }
        fun escape(s: String) = buildString { s.forEach { if (it in "\\;,:\"") append('\\'); append(it) } }
        val type = when (security) { "open", "owe" -> "nopass"; "wep" -> "WEP"; "wpa3" -> "SAE"; else -> "WPA" }
        return "WIFI:T:$type;S:${escape(ssid)};P:${escape(password)};H:$hidden;;"
    }
}
