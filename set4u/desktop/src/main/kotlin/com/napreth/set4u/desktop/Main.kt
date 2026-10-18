package com.napreth.set4u.desktop

import com.napreth.set4u.core.Core
import com.napreth.set4u.core.compatibility.*
import com.napreth.set4u.core.session.CoreSession
import com.napreth.set4u.desktop.bridge.AdbBridge
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import kotlinx.coroutines.*
import kotlin.system.exitProcess

private val appVersion = object {}.javaClass.`package`.implementationVersion ?: "development"

fun main(args: Array<String>) {
    try {
        when {
            "--help" in args -> println("Set-for-U $appVersion\nGUI: Set-for-U\nCLI: Set-for-U --device SERIAL --profile [--adb PATH]\nChecks: --self-test [--gui] | --version")
            "--version" in args -> println("Set-for-U $appVersion")
            "--self-test" in args -> {
                val profile = DeviceProfile(mapOf("ro.build.version.sdk" to "35"))
                val registry = CompatibilityRegistry(listOf(CompatibilityRule("self-test", "test", DeviceCondition.IntegerRange(DeviceFields.Sdk, 35), RuleDecision.Use("native"))))
                check(registry.resolve("test", profile) == CompatibilityResolution.Supported("native", "self-test"))
                check(MainWindow::class.java.getResource("/icons/set4u.png") != null)
                if ("--gui" in args) SwingUtilities.invokeAndWait { MainWindow().dispose() }
                println("Set-for-U self-test OK")
            }
            "--profile" in args -> runBlocking {
                val serial = argument(args, "--device") ?: error("--device SERIAL is required")
                val bridge = AdbBridge(serial, argument(args, "--adb") ?: adbPath())
                try {
                    bridge.connect()
                    val session = Core.open(bridge)
                    try { println(DeviceProfileReport.json(session.compatibility.deviceProfile())) }
                    finally { session.close() }
                } finally { bridge.close() }
            }
            else -> {
                check(!GraphicsEnvironment.isHeadless()) { "A desktop environment is required. Use --help for CLI options." }
                SwingUtilities.invokeLater { MainWindow().isVisible = true }
            }
        }
    } catch (exception: Exception) {
        System.err.println("Set-for-U: ${exception.message}")
        exitProcess(1)
    }
}

private fun argument(args: Array<String>, key: String): String? = args.indexOf(key).takeIf { it >= 0 }?.let {
    args.getOrNull(it + 1)?.takeUnless { value -> value.startsWith("--") } ?: error("Missing value for $key")
}

internal fun adbPath(): String {
    val executable = if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb"
    val base = System.getProperty("set4u.home") ?: System.getenv("SET4U_HOME")
    return base?.let { File(it, "tools/$executable") }?.takeIf { it.isFile }?.absolutePath ?: executable
}
