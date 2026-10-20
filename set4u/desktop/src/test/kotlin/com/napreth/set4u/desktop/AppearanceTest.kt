package com.napreth.set4u.desktop

import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.session.CoreSession
import com.napreth.set4u.core.settings.SystemSettingsScreen
import com.napreth.set4u.ui.*
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files

class AppearanceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun portablePreferencesSurviveRestartAndUnknownThemeFallsBack() {
        val path = temporary.root.toPath().resolve("config/appearance.properties")
        val preferences = AppearancePreferences(path)
        assertEquals(AppTheme.Cupertino, preferences.read())
        preferences.write(AppTheme.Miuix)
        preferences.writeThemeMode(ThemeMode.Dark)
        preferences.writeLanguage(AppLanguage.English)
        preferences.writeWorkMode(WorkMode.Root)
        preferences.writeRootShell(true)
        preferences.writeOwnPages(true)
        assertEquals(AppTheme.Miuix, AppearancePreferences(path).read())
        preferences.write(AppTheme.Material)
        assertEquals(AppTheme.Material, AppearancePreferences(path).read())
        assertEquals(ThemeMode.Dark, AppearancePreferences(path).readThemeMode())
        assertEquals(AppLanguage.English, AppearancePreferences(path).readLanguage())
        assertEquals(WorkMode.Root, AppearancePreferences(path).readWorkMode())
        assertTrue(AppearancePreferences(path).readRootShell())
        assertTrue(AppearancePreferences(path).readOwnPages())
        Files.writeString(path, "theme=old-removed-theme\n")
        assertEquals(AppTheme.Cupertino, AppearancePreferences(path).read())
        Files.list(path.parent).use { assertEquals(1, it.count()) }
    }

    @Test fun changingThemePreservesNavigationAndDoesNotReplaceBridge() {
        val platform = Platform()
        val controller = AppController(platform, Dispatchers.Unconfined)
        try {
            controller.navigate("network")
            controller.selectTab(2)
            controller.selectTheme(AppTheme.Miuix)
            assertEquals(AppTheme.Miuix, controller.state.value.theme)
            assertEquals("network", controller.state.value.page)
            assertEquals(1, platform.bridges)
            assertEquals(0, platform.connections)
            controller.selectTheme(AppTheme.Material)
            controller.selectThemeMode(ThemeMode.Dark)
            controller.selectLanguage(AppLanguage.English)
            assertEquals(2, controller.state.value.tab)
            assertEquals("About", controller.state.value.title)
            controller.selectTab(0)
            assertEquals("network", controller.state.value.page)
            assertTrue(controller.back())
            assertEquals("home", controller.state.value.page)
            assertEquals(listOf(AppTheme.Miuix, AppTheme.Material), platform.saved)
            assertEquals(1, platform.bridges)
        } finally { controller.close() }
    }

    @Test fun failedSaveKeepsPreviousThemeAndNavigationCanBeRestored() {
        val platform = Platform()
        val controller = AppController(platform, Dispatchers.Unconfined)
        try {
            controller.restoreNavigation(listOf("0", "appearance.theme", "home", "appearance"))
            assertEquals(2, controller.state.value.tab)
            assertEquals("home", controller.state.value.page)
            platform.failSave = true
            controller.selectTheme(AppTheme.Material)
            assertEquals(AppTheme.Cupertino, controller.state.value.theme)
            assertTrue(controller.state.value.dialog is AppDialog.Message)
            controller.dismissDialog()
            assertTrue(controller.back())
            assertEquals("home", controller.state.value.page)
            assertEquals(1, platform.bridges)
        } finally { controller.close() }
    }

    @Test fun appLanguageRelocalizesCorePagesWithoutConnectingOrReplacingBridge() = kotlinx.coroutines.runBlocking {
        val platform = Platform()
        val controller = AppController(platform, Dispatchers.Unconfined)
        try {
            controller.selectLanguage(AppLanguage.English)
            val english = kotlinx.coroutines.withTimeout(5_000) {
                controller.state.first { it.settings.items.any { row -> row.component.title == "Network & communication" } }
            }
            assertEquals("Settings", english.title)
            platform.locale = "zh-HK"
            controller.selectLanguage(AppLanguage.System)
            kotlinx.coroutines.withTimeout(5_000) {
                controller.state.first { it.settings.items.any { row -> row.component.title == "网络与通信" } }
            }
            controller.selectTab(2)
            platform.locale = "en-US"
            controller.refreshOnResume()
            assertEquals("About", controller.state.value.title)
            controller.selectTab(0)
            kotlinx.coroutines.withTimeout(5_000) {
                controller.state.first { it.settings.items.any { row -> row.component.title == "Network & communication" } }
            }
            assertEquals(1, platform.bridges)
            assertEquals(0, platform.connections)
        } finally { controller.close() }
    }

    @Test fun menuBackAndDeviceInformationHaveTheirOwnNavigation() {
        val controller = AppController(Platform(), Dispatchers.Unconfined)
        try {
            controller.navigate("network")
            controller.selectTab(1)
            controller.showMenu(AppMenu("工作模式", controller.workModeOptions, 0, Rect(10f, 10f, 200f, 60f)) {})
            assertNotNull(controller.state.value.menu)
            assertTrue(controller.back())
            assertNull(controller.state.value.menu)
            assertEquals(1, controller.state.value.tab)
            controller.showDevice()
            assertEquals(ConnectionScreen.DeviceInfo, controller.state.value.connectionScreen)
            assertEquals("设备信息", controller.state.value.title)
            assertTrue(controller.state.value.nested)
            assertNull(controller.state.value.dialog)
            assertEquals("请先连接设备", controller.state.value.deviceInfo.error)
            val saved = controller.saveNavigation()
            controller.back()
            assertEquals(ConnectionScreen.Overview, controller.state.value.connectionScreen)
            assertEquals("工作模式", controller.state.value.title)
            controller.restoreNavigation(saved)
            assertEquals(ConnectionScreen.DeviceInfo, controller.state.value.connectionScreen)
            controller.back()
            controller.selectTab(0)
            assertEquals("network", controller.state.value.page)
        } finally { controller.close() }
    }

    @Test fun selectingModeWithoutDesktopSerialReplacesTheOldSessionWithoutConnecting() = kotlinx.coroutines.runBlocking {
        val platform = Platform()
        val controller = AppController(platform, Dispatchers.Unconfined)
        try {
            controller.selectMode(WorkMode.Root)
            kotlinx.coroutines.withTimeout(5_000) { controller.state.first { !it.connecting && it.mode == WorkMode.Root } }
            assertEquals(2, platform.bridges)
            assertEquals(0, platform.connections)
            assertEquals(BridgeState.Disconnected, controller.state.value.connection)
            controller.selectMode(WorkMode.Root)
            assertEquals(2, platform.bridges)
        } finally { controller.close() }
    }

    @Test fun rootShellPreferenceReplacesTheSessionAndCannotAffectAdbMode() = kotlinx.coroutines.runBlocking {
        val platform = Platform()
        val controller = AppController(platform, Dispatchers.Unconfined)
        suspend fun settled() { kotlinx.coroutines.withTimeout(5_000) { controller.state.first { !it.connecting } } }
        try {
            controller.setRootShell(true)
            assertFalse(controller.state.value.rootShell)
            assertEquals(1, platform.bridges)
            controller.selectMode(WorkMode.Root); settled()
            controller.setRootShell(true); settled()
            assertTrue(controller.state.value.rootShell)
            assertTrue(platform.rootShell)
            assertEquals(listOf(false, false, true), platform.identities)
            controller.setRootShell(true)
            assertEquals(3, platform.bridges)
            controller.selectMode(WorkMode.Shell); settled()
            assertFalse(platform.identities.last())
            controller.selectMode(WorkMode.Root); settled()
            assertTrue(platform.identities.last())
            platform.failSave = true
            controller.setRootShell(false)
            assertTrue(controller.state.value.rootShell)
            assertTrue(controller.state.value.dialog is AppDialog.Message)
        } finally { controller.close() }
    }

    @Test fun activationFailuresStayInlineAndRetryClearsThePreviousReason() = kotlinx.coroutines.runBlocking {
        val platform = Platform()
        val controller = AppController(platform, Dispatchers.Unconfined)
        suspend fun settled() { kotlinx.coroutines.withTimeout(5_000) { controller.state.first { !it.connecting } } }
        try {
            controller.updateSerial("test-device")
            platform.connectAction = { throw IOException("su denied") }
            controller.selectMode(WorkMode.Root); settled()
            assertEquals("未获得 root 权限", controller.state.value.activationError)
            assertNull(controller.state.value.dialog)

            platform.connectAction = { throw ShellIdentityUnavailableException() }
            controller.connect(); settled()
            assertEquals("当前 root 实现无法切换到 shell 身份", controller.state.value.activationError)
            assertNull(controller.state.value.dialog)

            platform.connectAction = { kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.awaitCancellation() } }
            controller.selectMode(WorkMode.Shell); settled()
            assertEquals("启用失败，请检查设备和授权", controller.state.value.activationError)
            assertNull(controller.state.value.dialog)

            val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
            platform.connectAction = { bridge ->
                ready.await()
                (bridge.connectionState as MutableStateFlow).value = BridgeState.Ready
            }
            controller.connect()
            assertTrue(controller.state.value.connecting)
            assertNull(controller.state.value.activationError)
            ready.complete(Unit); settled()
            assertEquals(BridgeState.Ready, controller.state.value.connection)
            assertNull(controller.state.value.activationError)
            assertNull(controller.state.value.dialog)
        } finally { controller.close() }
    }

    private class Platform : AppPlatform {
        override val desktop = true
        override val version = "test"
        var locale = "zh-CN"
        override val systemLocale get() = locale
        var bridges = 0
        var connections = 0
        var connectAction: suspend (IAdbBridge) -> Unit = {}
        var failSave = false
        var rootShell = false
        val identities = mutableListOf<Boolean>()
        val saved = mutableListOf<AppTheme>()
        override fun readTheme() = AppTheme.Cupertino
        override fun saveTheme(theme: AppTheme) { if (failSave) throw IOException("read only"); saved += theme }
        override fun readRootShell() = rootShell
        override fun saveRootShell(enabled: Boolean) { if (failSave) throw IOException("read only"); rootShell = enabled }
        override fun createBridge(mode: WorkMode, serial: String, emulateShell: Boolean): IAdbBridge {
            bridges++
            identities += emulateShell
            return object : IAdbBridge {
                override val connectionState = MutableStateFlow(BridgeState.Disconnected)
                override suspend fun execute(request: ShellRequest): ShellResult = error("No device operations expected")
                override suspend fun close() {}
            }
        }
        override suspend fun connect(bridge: IAdbBridge, requestPermission: Boolean) { connections++; connectAction(bridge) }
        override suspend fun openSystem(session: CoreSession, screen: SystemSettingsScreen) {}
        override fun openLink(url: String) {}
        override fun license() = "MIT"
    }
}
