package com.napreth.set4u.desktop

import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.compatibility.CompatibilityRegistry
import com.napreth.set4u.core.session.CoreSession
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.network.WifiNetwork
import com.napreth.set4u.core.settings.presentation.RowContainer
import com.napreth.set4u.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class WirelessNavigationTest {
    @Test fun routingUsesTheActualIdentityAndTheLocalOverride() {
        for (uid in listOf(2000, 0)) {
            val platform = Platform(uid)
            val controller = AppController(platform, Dispatchers.Unconfined)
            try {
                assertEquals(if (uid == 0) ExecutionIdentity.Root else ExecutionIdentity.Shell, controller.state.value.identity)
                controller.navigate("network.wifi")
                if (uid == 2000) {
                    assertEquals("home", controller.state.value.page)
                    assertEquals(listOf(SystemSettingsScreen.Wifi), platform.opened)
                    controller.navigate("network.bluetooth"); controller.navigate("network.hotspot")
                    assertEquals(listOf(SystemSettingsScreen.Wifi, SystemSettingsScreen.Bluetooth, SystemSettingsScreen.Hotspot), platform.opened)
                    controller.setOwnPages(true); assertTrue(platform.own)
                    controller.navigate("network.wifi"); assertEquals("network.wifi", controller.state.value.page)
                    controller.showAbout(AboutScreen.Debug); controller.setOwnPages(false)
                    controller.selectTab(0)
                    assertEquals("network", controller.state.value.page)
                    assertEquals(SystemSettingsScreen.Wifi, platform.opened.last())
                } else {
                    assertEquals("network.wifi", controller.state.value.page); assertTrue(platform.opened.isEmpty())
                    controller.showSavedWifi()
                    controller.showWifiDetails(WifiNetwork("Home", "wpa2"))
                    assertEquals("WLAN 详情", controller.state.value.title)
                    assertEquals(WifiScreen.Details, controller.state.value.wifiScreen)
                    assertNull(controller.state.value.dialog)
                    controller.back(); assertEquals(WifiScreen.Saved, controller.state.value.wifiScreen)
                    assertEquals("已保存网络", controller.state.value.title)
                    controller.back(); assertEquals(WifiScreen.Overview, controller.state.value.wifiScreen)
                    assertEquals("WLAN", controller.state.value.title)
                    controller.showAdvancedWifi(); assertEquals(WifiScreen.Advanced, controller.state.value.wifiScreen)
                    controller.back(); assertEquals(WifiScreen.Overview, controller.state.value.wifiScreen)
                }
                controller.showAbout(AboutScreen.Debug)
                assertTrue(controller.state.value.nested)
                controller.back(); assertEquals(AboutScreen.Overview, controller.state.value.aboutScreen)
            } finally { controller.close() }
        }
    }
    private class Platform(private val uid: Int) : AppPlatform {
        override val desktop = false
        override val version = "test"
        override val compatibility = CompatibilityRegistry.Empty
        override val catalog = SettingsCatalog(*listOf("home", "network", "network.wifi", "network.bluetooth", "network.hotspot").map { id -> object : SettingsPage(id, id) {
            override val entries = emptyList<com.napreth.set4u.core.settings.presentation.SettingEntry>()
            override fun layout(rows: PageRows) = emptyList<RowContainer>()
        } }.toTypedArray())
        val opened = mutableListOf<SystemSettingsScreen>()
        var own = false
        override fun readTheme() = AppTheme.Material
        override fun saveTheme(theme: AppTheme) {}
        override fun saveOwnPages(enabled: Boolean) { own = enabled }
        override fun createBridge(mode: WorkMode, serial: String, emulateShell: Boolean) = object : IAdbBridge {
            override val connectionState = MutableStateFlow(BridgeState.Ready)
            override suspend fun environment() = ExecutionEnvironment(BridgeTransport.Local, uid)
            override suspend fun execute(request: ShellRequest): ShellResult = error("No command expected")
            override suspend fun close() {}
        }
        override suspend fun connect(bridge: IAdbBridge, requestPermission: Boolean) {}
        override suspend fun openSystem(session: CoreSession, screen: SystemSettingsScreen) { opened += screen }
        override fun openLink(url: String) {}
        override fun license() = "MIT"
    }
}
