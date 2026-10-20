package com.napreth.set4u.core.settings.network

import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.presentation.*

object ApplicationSettings {
    fun catalog() = SettingsCatalog(HomePage(), NetworkPage(), WlanPage(), BluetoothPage(), HotspotPage(), MobileDataPage(), ProxyPage(), PrivateDnsPage(), CertificatesPage(), texts = NetworkTexts.resources, handlers = listOf(com.napreth.set4u.core.network.WirelessCapabilities.queries), definitions = com.napreth.set4u.core.network.WirelessCapabilities.actions.values.toList())
}

class HomePage : SettingsPage("home", text("home.title")) {
    override val entries = emptyList<SettingEntry>()
    override val navigationEntries = listOf(
        NavigationEntry("open.network", text("network.title"), SettingsDestination("network"), icon = "network"),
    )
    override fun layout(rows: PageRows) = with(rows) { listOf(RowContainer("home.network", listOf(navigation("open.network")))) }
}
class NetworkPage : SettingsPage("network", text("network.title")) {
    override val entries = listOf(NetworkEntries.wifi, NetworkEntries.mobileData, NetworkEntries.airplane, NetworkEntries.bluetooth, NetworkEntries.nfc)
    override val navigationEntries = listOf(
        NavigationEntry("open.wifi", text("wifi.title"), SettingsDestination("network.wifi")),
        NavigationEntry("open.mobile", text("mobile.title"), SettingsDestination("network.mobile")),
        NavigationEntry("open.bluetooth", text("bluetooth.title"), SettingsDestination("network.bluetooth")),
        NavigationEntry("open.proxy", text("proxy.title"), SettingsDestination("network.proxy")),
        NavigationEntry("open.dns", text("dns.title"), SettingsDestination("network.dns")),
        NavigationEntry("open.certificates", text("certificates.title"), SettingsDestination("network.certificates")),
        NavigationEntry("open.hotspot", text("hotspot.title"), SettingsDestination("network.hotspot")),
        NavigationEntry("open.vpn", text("vpn.title"), SettingsDestination(systemScreen = SystemSettingsScreen.Vpn)),
    )
    override suspend fun onEnter(context: SettingsPageContext) {
        context.read(NetworkSettings.airplane.id)
        context.read(NetworkSettings.nfc.id)
    }
    override fun layout(rows: PageRows) = with(rows) { listOf(
        RowContainer("network.connections", listOf(
            navigation("open.wifi"),
            navigation("open.mobile"),
            navigation("open.hotspot"),
        ) + rows(NetworkEntries.airplane)),
        RowContainer("network.nearby", listOf(navigation("open.bluetooth")) + rows(NetworkEntries.nfc, unsupported = UnsupportedVisibility.Hide)),
        RowContainer("network.advanced", listOf(
            navigation("open.proxy"), navigation("open.vpn"),
            navigation("open.dns"), navigation("open.certificates"),
        )),
    ) }
}
class BluetoothPage : SettingsPage("network.bluetooth", text("bluetooth.title")) {
    override suspend fun assess(context: com.napreth.set4u.core.compatibility.AvailabilityContext): com.napreth.set4u.core.compatibility.AvailabilityAssessment {
        val support = context.assess(NetworkSettings.bluetooth).support
        return if (support is com.napreth.set4u.core.compatibility.DeviceSupport.Unsupported) com.napreth.set4u.core.compatibility.AvailabilityAssessment(com.napreth.set4u.core.compatibility.AvailabilityStatus.Unsupported, support.reason)
        else com.napreth.set4u.core.compatibility.AvailabilityAssessment()
    }
    override val entries = listOf(NetworkEntries.bluetooth)
    override fun layout(rows: PageRows) = listOf(RowContainer("bluetooth.toggle", rows.rows(NetworkEntries.bluetooth)))
}
class HotspotPage : SettingsPage("network.hotspot", text("hotspot.title")) {
    override val entries = emptyList<SettingEntry>()
    override fun layout(rows: PageRows) = emptyList<RowContainer>()
}
class WlanPage : SettingsPage("network.wifi", text("wifi.title")) {
    override val entries = listOf(NetworkEntries.wifi)
    override val navigationEntries = listOf(
        NavigationEntry("wifi.select", text("wifi.manage"), SettingsDestination("network.wifi")),
    )
    override fun layout(rows: PageRows) = with(rows) { listOf(
        RowContainer("wifi.toggle", rows(NetworkEntries.wifi)),
    ) }
}
class MobileDataPage : SettingsPage("network.mobile", text("mobile.title")) {
    override val entries = listOf(NetworkEntries.mobileData)
    override val navigationEntries = listOf(
        NavigationEntry("mobile.sim", text("mobile.sim"), SettingsDestination(systemScreen = SystemSettingsScreen.Mobile)),
        NavigationEntry("mobile.esim", text("mobile.esim"), SettingsDestination(systemScreen = SystemSettingsScreen.Esim)),
    )
    override fun layout(rows: PageRows) = with(rows) { listOf(RowContainer("mobile.options", rows(NetworkEntries.mobileData) + listOf(
        navigation("mobile.sim"), navigation("mobile.esim"),
    ))) }
}
class ProxyPage : SettingsPage("network.proxy", text("proxy.title")) {
    override val entries = emptyList<SettingEntry>()
    override val navigationEntries = listOf(
        NavigationEntry("proxy.wifi", text("proxy.wifi"), SettingsDestination(systemScreen = SystemSettingsScreen.Wifi)),
        NavigationEntry("proxy.apn", text("proxy.apn"), SettingsDestination(systemScreen = SystemSettingsScreen.Apn)),
    )
    override fun layout(rows: PageRows) = with(rows) { listOf(RowContainer("proxy.options", listOf(
        navigation("proxy.wifi"), navigation("proxy.apn"),
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
    override val navigationEntries = listOf(
        NavigationEntry("certificates.trust", text("certificates.trust"), SettingsDestination(systemScreen = SystemSettingsScreen.Certificates)),
        NavigationEntry("certificates.install", text("certificates.install"), SettingsDestination(systemScreen = SystemSettingsScreen.InstallCertificate)),
    )
    override fun layout(rows: PageRows) = with(rows) { listOf(RowContainer("certificates.options", listOf(
        navigation("certificates.trust"),
        navigation("certificates.install"),
    ))) }
}
