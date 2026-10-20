package com.napreth.set4u

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.napreth.set4u.bridge.LocalProcessBridge
import com.napreth.set4u.bridge.ShizukuBridge
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.compatibility.DeviceInfoCollector
import com.napreth.set4u.core.session.CoreSession
import com.napreth.set4u.core.settings.SystemSettingsScreen
import com.napreth.set4u.ui.*
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    private lateinit var controller: AppController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appearance = AppearancePreferences(this)
        val modePreferences = getPreferences(MODE_PRIVATE)
        controller = AppController(object : AppPlatform {
            override val desktop = false
            override val glassEffects = android.os.Build.VERSION.SDK_INT >= 31
            @Suppress("DEPRECATION")
            override val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            override fun applyDarkMode(dark: Boolean) {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            override fun readTheme() = appearance.theme
            override fun saveTheme(theme: AppTheme) { appearance.theme = theme }
            override fun readThemeMode() = appearance.themeMode
            override fun saveThemeMode(mode: ThemeMode) { appearance.themeMode = mode }
            override fun readLanguage() = appearance.language
            override fun saveLanguage(language: AppLanguage) { appearance.language = language }
            override fun readWorkMode() = if (modePreferences.getString("workMode", "shell") == "root") WorkMode.Root else WorkMode.Shell
            override fun saveWorkMode(mode: WorkMode) { modePreferences.edit().putString("workMode", if (mode == WorkMode.Root) "root" else "shell").apply() }
            override fun readRootShell() = modePreferences.getBoolean("rootShell", false)
            override fun saveRootShell(enabled: Boolean) { modePreferences.edit().putBoolean("rootShell", enabled).apply() }
            override fun readOwnPages() = modePreferences.getBoolean("ownPages", false)
            override fun saveOwnPages(enabled: Boolean) { modePreferences.edit().putBoolean("ownPages", enabled).apply() }
            override fun createBridge(mode: WorkMode, serial: String, emulateShell: Boolean): IAdbBridge = if (mode == WorkMode.Root) RootBridge(LocalProcessBridge(), emulateShell) else ShizukuBridge(applicationContext)
            override suspend fun connect(bridge: IAdbBridge, requestPermission: Boolean) {
                when (bridge) {
                    is ShizukuBridge -> bridge.connect(requestPermission, if (requestPermission) 15_000 else 2_000)
                    is RootBridge -> bridge.connect()
                }
            }
            override suspend fun deviceProfile(session: CoreSession) = LocalProcessBridge().let { local ->
                try { DeviceInfoCollector(local).collect() } finally { local.close() }
            }
            override suspend fun openSystem(session: CoreSession, screen: SystemSettingsScreen) {
                check(screen.requiredFeature?.let { packageManager.hasSystemFeature(it) } != false)
                try { startActivity(Intent(screen.action)) }
                catch (_: android.content.ActivityNotFoundException) {
                    try { startActivity(screen.component?.let { Intent().setComponent(android.content.ComponentName.unflattenFromString(it)) } ?: Intent(screen.fallback)) }
                    catch (_: android.content.ActivityNotFoundException) { startActivity(Intent(screen.fallback)) }
                }
            }
            override fun openLink(url: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            override fun license() = assets.open("LICENSE").bufferedReader().use { it.readText() }
        }, Dispatchers.Main.immediate)
        savedInstanceState?.getStringArrayList("navigation")?.let(controller::restoreNavigation)
        setContent {
            BackHandler { if (!controller.back()) finish() }
            Set4uApp(controller, icon = { Image(painterResource(R.drawable.app_icon), "Set-for-U", Modifier.fillMaxSize()) })
        }
        controller.start()
    }

    override fun onResume() { super.onResume(); if (::controller.isInitialized) controller.refreshOnResume() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("navigation", ArrayList(controller.saveNavigation()))
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { if (::controller.isInitialized) controller.close(); super.onDestroy() }
}
