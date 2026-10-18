package com.napreth.set4u.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.session.*
import com.napreth.set4u.core.settings.SystemSettingsScreen
import com.napreth.set4u.desktop.bridge.AdbBridge
import com.napreth.set4u.ui.*
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import javax.swing.WindowConstants
import javax.swing.JFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.swing.Swing

internal class MainWindow : JFrame() {
    private val appearance = AppearancePreferences()
    private val appBitmap by lazy { ImageIO.read(javaClass.getResource("/icons/set4u.png")).toComposeImageBitmap() }
    internal val controller = AppController(object : AppPlatform {
        private var adbTransport: AdbBridge? = null
        override val desktop = true
        override val uiFontFamily get() = DesktopFonts.ui
        override val version = javaClass.getPackage().implementationVersion ?: "development"
        override fun readTheme() = appearance.read()
        override fun saveTheme(theme: AppTheme) = appearance.write(theme)
        override fun readThemeMode() = appearance.readThemeMode()
        override fun saveThemeMode(mode: ThemeMode) = appearance.writeThemeMode(mode)
        override fun readLanguage() = appearance.readLanguage()
        override fun saveLanguage(language: AppLanguage) = appearance.writeLanguage(language)
        override fun readWorkMode() = appearance.readWorkMode()
        override fun saveWorkMode(mode: WorkMode) = appearance.writeWorkMode(mode)
        override fun readRootShell() = appearance.readRootShell()
        override fun saveRootShell(enabled: Boolean) = appearance.writeRootShell(enabled)
        override fun createBridge(mode: WorkMode, serial: String, emulateShell: Boolean): IAdbBridge {
            if (serial.isBlank()) return object : IAdbBridge {
                override val connectionState = MutableStateFlow(BridgeState.Disconnected)
                override suspend fun execute(request: ShellRequest): ShellResult = error("请先连接设备")
                override suspend fun close() {}
            }
            val adb = AdbBridge(serial, adbPath()).also { adbTransport = it }
            return if (mode == WorkMode.Root) RootBridge(adb, emulateShell) { source, target ->
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                Unit
            } else adb
        }
        override suspend fun connect(bridge: IAdbBridge, requestPermission: Boolean) = withContext(Dispatchers.IO) {
            when (bridge) {
                is AdbBridge -> bridge.connect()
                is RootBridge -> { checkNotNull(adbTransport).connect(); bridge.connect() }
            }
        }
        override suspend fun openSystem(session: CoreSession, screen: SystemSettingsScreen) { session.navigation.open(screen, DeviceTarget()) }
        override fun openLink(url: String) { Desktop.getDesktop().browse(URI(url)) }
        override fun license() = MainWindow::class.java.getResourceAsStream("/LICENSE")!!.bufferedReader().use { it.readText() }
    }, Dispatchers.Swing)

    init {
        title = "Set-for-U"; setSize(620, 800); minimumSize = Dimension(480, 640)
        iconImage = ImageIO.read(javaClass.getResource("/icons/set4u.png"))
        setLocationRelativeTo(null)
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() { override fun windowClosing(event: WindowEvent) { dispose() } })
        val panel = ComposePanel()
        contentPane = panel
        panel.setContent { Set4uApp(controller, icon = { Image(appBitmap, "Set-for-U", Modifier.fillMaxSize()) }) }
    }

    override fun dispose() { controller.close(); super.dispose() }
}
