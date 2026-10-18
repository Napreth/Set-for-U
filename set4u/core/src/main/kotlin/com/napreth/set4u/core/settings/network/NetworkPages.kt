package com.napreth.set4u.core.settings.network

import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.presentation.*

object ApplicationSettings {
    fun catalog() = SettingsCatalog(HomePage(), NetworkPage(), WlanPage(), MobileDataPage(), ProxyPage(), PrivateDnsPage(), CertificatesPage(), texts = NetworkTexts.resources)
}
private fun PageRows.page(id: String, key: String, page: String, value: SettingEntry? = null, icon: String? = null) = navigation(id, text(key), SettingsDestination(page), value, icon)
private fun PageRows.system(id: String, key: String, screen: SystemSettingsScreen) = navigation(id, text(key), SettingsDestination(systemScreen = screen))

class HomePage : SettingsPage("home", text("home.title")) {
    override val entries = emptyList<SettingEntry>()
    override fun layout(rows: PageRows) = with(rows) { listOf(RowContainer("home.network", listOf(page("open.network", "network.title", "network", icon = "network")))) }
}
class NetworkPage : SettingsPage("network", text("network.title")) {
    override val entries = listOf(NetworkEntries.wifi, NetworkEntries.mobileData, NetworkEntries.airplane, NetworkEntries.bluetooth, NetworkEntries.nfc)
    override fun layout(rows: PageRows) = with(rows) { listOf(
        RowContainer("network.connections", listOf(
            page("open.wifi", "wifi.title", "network.wifi", NetworkEntries.wifi),
            page("open.mobile", "mobile.title", "network.mobile", NetworkEntries.mobileData),
            system("open.hotspot", "hotspot.title", SystemSettingsScreen.Hotspot),
        ) + rows(NetworkEntries.airplane)),
        RowContainer("network.nearby", rows(NetworkEntries.bluetooth, unsupported = UnsupportedVisibility.Hide) + rows(NetworkEntries.nfc, unsupported = UnsupportedVisibility.Hide)),
        RowContainer("network.advanced", listOf(
            page("open.proxy", "proxy.title", "network.proxy"), system("open.vpn", "vpn.title", SystemSettingsScreen.Vpn),
            page("open.dns", "dns.title", "network.dns"), page("open.certificates", "certificates.title", "network.certificates"),
        )),
    ) }
}
class WlanPage : SettingsPage("network.wifi", text("wifi.title")) {
    override val entries = listOf(NetworkEntries.wifi)
    override fun layout(rows: PageRows) = with(rows) { listOf(
        RowContainer("wifi.toggle", rows(NetworkEntries.wifi)),
        RowContainer("wifi.networks", listOf(system("wifi.select", "wifi.manage", SystemSettingsScreen.Wifi))),
    ) }
}
class MobileDataPage : SettingsPage("network.mobile", text("mobile.title")) {
    override val entries = listOf(NetworkEntries.mobileData)
    override fun layout(rows: PageRows) = with(rows) { listOf(RowContainer("mobile.options", rows(NetworkEntries.mobileData) + listOf(
        system("mobile.sim", "mobile.sim", SystemSettingsScreen.Mobile), system("mobile.esim", "mobile.esim", SystemSettingsScreen.Esim),
    ))) }
}
class ProxyPage : SettingsPage("network.proxy", text("proxy.title")) {
    override val entries = emptyList<SettingEntry>()
    override fun layout(rows: PageRows) = with(rows) { listOf(RowContainer("proxy.options", listOf(
        system("proxy.wifi", "proxy.wifi", SystemSettingsScreen.Wifi), system("proxy.apn", "proxy.apn", SystemSettingsScreen.Apn),
    ))) }
}
class PrivateDnsPage : SettingsPage("network.dns", text("dns.title")) {
    override val entries = listOf(NetworkEntries.dnsMode, NetworkEntries.dnsHostname)
    override fun layout(rows: PageRows) = with(rows) { listOf(
        RowContainer("dns.mode", rows(NetworkEntries.dnsMode), title = text(com.napreth.set4u.core.localization.UiText.Key("dns.title"))),
        RowContainer("dns.hostname", rows(NetworkEntries.dnsHostname)),
    ) }
}
class CertificatesPage : SettingsPage("network.certificates", text("certificates.title")) {
    override val entries = emptyList<SettingEntry>()
    override fun layout(rows: PageRows) = with(rows) { listOf(RowContainer("certificates.options", listOf(
        system("certificates.trust", "certificates.trust", SystemSettingsScreen.Certificates),
        system("certificates.install", "certificates.install", SystemSettingsScreen.InstallCertificate),
    ))) }
}
