package com.napreth.set4u.desktop

import com.napreth.set4u.ui.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

internal class AppearancePreferences(private val file: Path = defaultFile()) {
    private fun properties(): Properties = Properties().apply {
        try { if (Files.isRegularFile(file)) Files.newInputStream(file).use(::load) }
        catch (_: java.io.IOException) { /* Use defaults when the local file cannot be read. */ }
    }
    fun read(): AppTheme = AppTheme.fromId(properties().getProperty("theme"))
    fun readThemeMode() = ThemeMode.fromId(properties().getProperty("themeMode"))
    fun readLanguage() = AppLanguage.fromId(properties().getProperty("language"))
    fun readWorkMode() = if (properties().getProperty("workMode") == "root") WorkMode.Root else WorkMode.Shell
    fun readRootShell() = properties().getProperty("rootShell").toBoolean()
    fun write(theme: AppTheme) = writeProperty("theme", theme.id)
    fun writeThemeMode(mode: ThemeMode) = writeProperty("themeMode", mode.id)
    fun writeLanguage(language: AppLanguage) = writeProperty("language", language.id)
    fun writeWorkMode(mode: WorkMode) = writeProperty("workMode", if (mode == WorkMode.Root) "root" else "shell")
    fun writeRootShell(enabled: Boolean) = writeProperty("rootShell", enabled.toString())

    private fun writeProperty(key: String, value: String) {
        val updated = properties().apply { setProperty(key, value) }
        Files.createDirectories(file.toAbsolutePath().parent)
        val temporary = Files.createTempFile(file.toAbsolutePath().parent, "appearance-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { updated.store(it, "Set-for-U appearance") }
            try { Files.move(temporary, file.toAbsolutePath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, file.toAbsolutePath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        private fun defaultFile(): Path {
            val portableHome = System.getProperty("set4u.home") ?: System.getenv("SET4U_HOME")
            return if (portableHome != null) Path.of(portableHome, "config", "appearance.properties")
            else Path.of(System.getProperty("user.home"), ".set4u", "appearance.properties")
        }
    }
}
