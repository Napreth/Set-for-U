package com.napreth.set4u.core.settings

import com.napreth.set4u.core.bridge.ShellCommand
import com.napreth.set4u.core.execution.ShellExecutor
import com.napreth.set4u.core.session.DeviceTarget

/** Device destinations. ROM-specific screens have an explicit, broader fallback. */
enum class SystemSettingsScreen(val action: String, val fallback: String, val requiredFeature: String? = null, val component: String? = null) {
    Wifi("android.settings.WIFI_SETTINGS", "android.settings.WIRELESS_SETTINGS"),
    WifiDirect("android.settings.WIFI_DIRECT_SETTINGS", "android.settings.WIFI_SETTINGS", "android.hardware.wifi.direct", "com.android.settings/.Settings\$WifiP2pSettingsActivity"),
    Mobile("android.settings.DATA_ROAMING_SETTINGS", "android.settings.WIRELESS_SETTINGS"),
    Hotspot("android.settings.TETHER_SETTINGS", "android.settings.WIRELESS_SETTINGS"),
    Esim("android.telephony.euicc.action.MANAGE_EMBEDDED_SUBSCRIPTIONS", "android.settings.WIRELESS_SETTINGS", "android.hardware.telephony.euicc"),
    Bluetooth("android.settings.BLUETOOTH_SETTINGS", "android.settings.WIRELESS_SETTINGS"),
    Nfc("android.settings.NFC_SETTINGS", "android.settings.WIRELESS_SETTINGS", "android.hardware.nfc"),
    Apn("android.settings.APN_SETTINGS", "android.settings.WIRELESS_SETTINGS"),
    Vpn("android.settings.VPN_SETTINGS", "android.settings.WIRELESS_SETTINGS"),
    Certificates("com.android.settings.TRUSTED_CREDENTIALS", "android.settings.SECURITY_SETTINGS"),
    InstallCertificate("android.credentials.INSTALL", "android.settings.SECURITY_SETTINGS"),
}

class SystemSettingsNavigator internal constructor(private val shell: ShellExecutor) {
    /** True means only the broader fallback screen could be opened. */
    suspend fun open(screen: SystemSettingsScreen, target: DeviceTarget = DeviceTarget()): Boolean {
        screen.requiredFeature?.let { feature ->
            val features = shell.execute(com.napreth.set4u.core.bridge.ShellRequest(ShellCommand.arguments("pm", "list", "features"))).requireSuccess().stdout
            check(features.lineSequence().any { it.trim() == "feature:$feature" || it.trim().startsWith("feature:$feature=") }) { "设备未声明支持 ${screen.name}" }
        }
        suspend fun launch(action: String, component: Boolean = false): Boolean {
            val result = shell.execute(com.napreth.set4u.core.bridge.ShellRequest(ShellCommand.arguments(
                "am", "start", "--user", target.userId?.toString() ?: "current", "-W", if (component) "-n" else "-a", action,
            ), timeoutMillis = 10_000))
            return result.exitCode == 0 && !result.outputTruncated &&
                result.stdout.lineSequence().any { it.trim() == "Status: ok" } &&
                !result.stderr.contains("Error", ignoreCase = true)
        }
        if (launch(screen.action)) return false
        if (screen.component?.let { launch(it, true) } == true) return false
        check(launch(screen.fallback)) { "设备上没有可打开的系统设置页面" }
        return true
    }
}
