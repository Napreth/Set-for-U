package com.napreth.set4u.core.settings.network

import com.napreth.set4u.core.localization.*
import com.napreth.set4u.core.settings.presentation.*

internal fun text(id: String) = UiText.Key(id)
object NetworkEntries {
    val wifi = SettingEntry(NetworkSettings.wifi, text("wifi.toggle"), keywords = listOf(text("wifi.keywords")))
    val mobileData = SettingEntry(NetworkSettings.mobileData, text("mobile.title"))
    val bluetooth = SettingEntry(NetworkSettings.bluetooth, text("bluetooth.title"))
    val nfc = SettingEntry(NetworkSettings.nfc, text("nfc.title"))
    val airplane = SettingEntry(NetworkSettings.airplane, text("airplane.title"))
    val dnsMode = SettingEntry(NetworkSettings.dnsMode, text("dns.title"), choices = listOf(
        EntryChoice("off", text("dns.off")), EntryChoice("opportunistic", text("dns.auto")), EntryChoice("hostname", text("dns.provider")),
    ), keywords = listOf(text("dns.keywords")))
    val dnsHostname = SettingEntry(NetworkSettings.dnsHostname, text("dns.hostname"), text("dns.hostname.help"))
    val all = listOf(wifi, mobileData, bluetooth, nfc, airplane, dnsMode, dnsHostname)
}
object NetworkTexts {
    val resources = TextResources(mapOf(
        "zh-CN" to mapOf(
            "home.title" to "设置", "network.title" to "网络与通信", "wifi.title" to "WLAN", "wifi.toggle" to "启用 WLAN",
            "wifi.manage" to "连接与管理网络", "wifi.keywords" to "Wi-Fi 无线网络 wireless",
            "mobile.title" to "移动数据", "mobile.sim" to "SIM 卡管理", "mobile.esim" to "eSIM",
            "hotspot.title" to "热点和网络共享", "bluetooth.title" to "蓝牙", "nfc.title" to "NFC", "airplane.title" to "飞行模式",
            "proxy.title" to "代理", "proxy.wifi" to "WLAN 代理", "proxy.apn" to "APN", "vpn.title" to "VPN",
            "dns.title" to "私人 DNS", "dns.off" to "关闭", "dns.auto" to "自动", "dns.provider" to "指定提供商",
            "dns.hostname" to "提供商主机名", "dns.hostname.help" to "填写主机名，例如 dns.google", "dns.keywords" to "加密域名解析 DNS",
            "certificates.title" to "TLS 证书", "certificates.trust" to "受信任的凭据", "certificates.install" to "安装证书",
        ),
        "en" to mapOf(
            "home.title" to "Settings", "network.title" to "Network & communication", "wifi.title" to "WLAN", "wifi.toggle" to "Enable WLAN",
            "wifi.manage" to "Connect and manage networks", "wifi.keywords" to "Wi-Fi wireless network",
            "mobile.title" to "Mobile data", "mobile.sim" to "SIM management", "mobile.esim" to "eSIM",
            "hotspot.title" to "Hotspot & tethering", "bluetooth.title" to "Bluetooth", "nfc.title" to "NFC", "airplane.title" to "Airplane mode",
            "proxy.title" to "Proxy", "proxy.wifi" to "WLAN proxy", "proxy.apn" to "APN", "vpn.title" to "VPN",
            "dns.title" to "Private DNS", "dns.off" to "Off", "dns.auto" to "Automatic", "dns.provider" to "Private provider",
            "dns.hostname" to "Provider hostname", "dns.hostname.help" to "Enter a hostname, for example dns.google", "dns.keywords" to "Encrypted domain name resolution",
            "certificates.title" to "TLS certificates", "certificates.trust" to "Trusted credentials", "certificates.install" to "Install certificate",
        ),
    ))
}
