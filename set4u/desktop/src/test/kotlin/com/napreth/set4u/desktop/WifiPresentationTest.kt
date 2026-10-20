package com.napreth.set4u.desktop

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.compatibility.CompatibilityRegistry
import com.napreth.set4u.core.network.*
import com.napreth.set4u.core.session.CoreSession
import com.napreth.set4u.core.settings.SystemSettingsScreen
import com.napreth.set4u.core.viewmodel.WirelessUiState
import com.napreth.set4u.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.swing.Swing
import org.junit.Assert.*
import org.junit.Test
import javax.swing.SwingUtilities

@OptIn(ExperimentalComposeUiApi::class)
class WifiPresentationTest {
    @Test fun proxyAndIpFormDropdownsRemainUsableInsideEveryThemeDialog() {
        SwingUtilities.invokeAndWait {
            for (theme in AppTheme.entries) {
                val controller = AppController(Platform(), Dispatchers.Swing.immediate)
                controller.selectTheme(theme)
                val network = WifiNetwork("Demo", "open", WirelessItem("1", "Demo", "saved", properties = mapOf("proxyMode" to "none", "ipMode" to "dhcp")))
                controller.editWifiProxy(network)
                val scene = ImageComposeScene(620, 900, Density(1f), coroutineContext = Dispatchers.Swing.immediate) { Set4uApp(controller) }
                var frame = 0L
                fun render(): List<SemanticsNode> {
                    repeat(4) { scene.render(1_000_000_000L + frame++ * 200_000_000L).close() }
                    fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
                    return scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                }
                fun text() = render().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
                fun choose(value: String, index: Int) {
                    val row = render().first { it.config.getOrNull(SemanticsProperties.StateDescription) == value && it.config.getOrNull(SemanticsActions.OnClick) != null }
                    row.config[SemanticsActions.OnClick].action!!.invoke()
                    assertNotNull("Form dropdown was rejected in $theme", controller.state.value.menu)
                    render(); controller.selectMenu(index)
                }
                try {
                    choose("无", 1)
                    assertTrue(text().containsAll(listOf("代理主机", "代理端口", "不使用代理的地址")))
                    choose("手动", 2)
                    assertTrue("PAC 地址" in text())
                    assertFalse("代理主机" in text())
                    controller.dismissDialog(); assertNull(controller.state.value.menu)
                    controller.editWifiIp(network)
                    choose("DHCP", 1)
                    assertTrue(text().containsAll(listOf("IPv4 地址", "网络前缀长度", "网关", "DNS")))
                } finally { scene.close(); controller.close() }
            }
        }
    }
    @Test fun allThemesShowNetworksOnceAndKeepManagementInSeparatePages() {
        val snapshot = WirelessSnapshot(mapOf("enabled" to "true", "ssid" to "Home", "networkId" to "1", "bssid" to "home-ap",
            "ip" to "10.0.0.2", "txSpeed" to "144", "rxSpeed" to "144", "frequency" to "5200", "wake" to "false", "notifier" to "true", "scanAlways" to "true", "wep" to "true", "wepSupported" to "true"), listOf(
            WirelessItem("1", "Home", "saved", "wpa2", connected = true, properties = mapOf("autoJoin" to "true", "shared" to "true", "metered" to "0", "macMode" to "1", "macModes" to "0,1", "proxyMode" to "none", "ipMode" to "dhcp")),
            WirelessItem("home-ap", "Home", "available", "wpa2", "-50 dBm · 5200 MHz", true),
            WirelessItem("2", "Office", "saved", "wpa3"), WirelessItem("office-ap", "Office", "available", "wpa3"),
            WirelessItem("cafe-ap", "Cafe", "available", "open")))
        for (theme in AppTheme.entries) {
            lateinit var controller: AppController
            lateinit var scene: ImageComposeScene
            var screen by mutableStateOf(AppState(page = "network.wifi", identity = ExecutionIdentity.Root,
                wireless = WirelessUiState(snapshot = snapshot)))
            SwingUtilities.invokeAndWait {
                controller = AppController(Platform(), Dispatchers.Swing.immediate)
                scene = ImageComposeScene(420, 1200, Density(1f), coroutineContext = Dispatchers.Swing.immediate) {
                    CompositionLocalProvider(LocalAppFontFamily provides DesktopFonts.ui, LocalMenuPresenter provides controller::showMenu) {
                        AppThemeContent(theme, false) { LazyColumn { wifiPage(screen, controller) } }
                    }
                }
            }
            fun texts(): List<String> {
                var texts = emptyList<String>()
                SwingUtilities.invokeAndWait {
                    scene.render(System.nanoTime()).close()
                    fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
                    texts = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
                }
                return texts
            }
            try {
                val overview = texts()
                for (name in listOf("Home", "Office", "Cafe")) assertEquals("$theme: $overview", 1, overview.count { it == name })
                assertTrue("已保存网络" in overview)
                assertTrue("添加网络" in overview)
                assertTrue(overview.containsAll(listOf("自动开启 WLAN", "附近有公共网络时发出通知", "高级 WLAN 设置")))
                assertFalse("允许连接 WEP 网络" in overview)
                assertFalse(overview.any { "dBm" in it || "Mbps" in it || it in listOf("10.0.0.2", "断开连接", "忘记网络", "系统设置") })
                val current = WifiNetworks.from(snapshot).current!!
                SwingUtilities.invokeAndWait { screen = screen.copy(wifiScreen = WifiScreen.Details, wifiSelection = current) }
                val details = texts()
                assertTrue("$theme: $details", details.containsAll(listOf("10.0.0.2", "144 Mbps", "断开连接", "忘记网络")))
                assertFalse("添加网络" in details)
                assertTrue(details.containsAll(listOf("自动连接", "代理", "IP 设置", "隐私", "按流量计费", "修改密码")))
                SwingUtilities.invokeAndWait { screen = screen.copy(wifiScreen = WifiScreen.Advanced) }
                val advanced = texts()
                assertTrue("允许连接 WEP 网络" in advanced)
                assertFalse("Home" in advanced)
                SwingUtilities.invokeAndWait { screen = screen.copy(wifiScreen = WifiScreen.Saved) }
                val saved = texts()
                assertEquals(1, saved.count { it == "Home" })
                assertEquals(1, saved.count { it == "Office" })
                assertFalse("Cafe" in saved)
            } finally { SwingUtilities.invokeAndWait { scene.close(); controller.close() } }
        }
    }

    @Test fun theRenderedSharingQrCodeCanBeDecodedWithUnicodeAndReservedCharacters() {
        val payload = WifiSharing.payload("家;里\\网", "wpa2", "demo:secret;password", true)
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(360, 360, Density(1f), coroutineContext = Dispatchers.Swing.immediate) {
                AppThemeContent(AppTheme.Material, false) { WifiShareCode(payload) }
            }
            try {
                val image = scene.render(System.nanoTime())
                val png = image.encodeToData()!!.use { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(it.bytes)) }
                image.close()
                val source = com.google.zxing.RGBLuminanceSource(png.width, png.height, png.getRGB(0, 0, png.width, png.height, null, 0, png.width))
                val result = com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)))
                assertEquals(payload, result.text)
                assertFalse(AppDialog.WifiShare("Demo", payload).toString().contains("password"))
            } finally { scene.close() }
        }
    }

    private class Platform : AppPlatform {
        override val desktop = true
        override val version = "test"
        override val compatibility = CompatibilityRegistry.Empty
        override fun readTheme() = AppTheme.Material
        override fun saveTheme(theme: AppTheme) {}
        override fun createBridge(mode: WorkMode, serial: String, emulateShell: Boolean) = object : IAdbBridge {
            override val connectionState = MutableStateFlow(BridgeState.Disconnected)
            override suspend fun execute(request: ShellRequest): ShellResult = error("No transport expected")
            override suspend fun close() {}
        }
        override suspend fun connect(bridge: IAdbBridge, requestPermission: Boolean) {}
        override suspend fun openSystem(session: CoreSession, screen: SystemSettingsScreen) {}
        override fun openLink(url: String) {}
        override fun license() = "MIT"
    }
}
