package com.napreth.set4u.ui

import com.napreth.set4u.core.Core
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.compatibility.*
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.session.*
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.network.ApplicationSettings
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.viewmodel.*
import com.napreth.set4u.core.state.SettingAccess
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class WorkMode(val title: String) { Shell("ADB"), Root("root") }
enum class ConnectionScreen { Overview, DeviceInfo }
data class DeviceInfoState(val loading: Boolean = false, val fields: Map<String, String?> = emptyMap(), val error: String? = null)
data class AppMenu(val title: String, val options: List<String>, val selection: Int, val anchor: Rect, val select: (Int) -> Unit)

interface AppPlatform {
    val desktop: Boolean
    val glassEffects: Boolean get() = true
    val uiFontFamily: FontFamily get() = FontFamily.SansSerif
    val version: String
    fun readTheme(): AppTheme
    fun saveTheme(theme: AppTheme)
    fun readThemeMode(): ThemeMode = ThemeMode.System
    fun saveThemeMode(mode: ThemeMode) {}
    fun readLanguage(): AppLanguage = AppLanguage.System
    fun saveLanguage(language: AppLanguage) {}
    fun applyDarkMode(dark: Boolean) {}
    val systemLocale: String get() = java.util.Locale.getDefault().toLanguageTag()
    fun readWorkMode(): WorkMode = WorkMode.Shell
    fun saveWorkMode(mode: WorkMode) {}
    fun readRootShell(): Boolean = false
    fun saveRootShell(enabled: Boolean) {}
    fun createBridge(mode: WorkMode, serial: String, emulateShell: Boolean = false): IAdbBridge
    suspend fun connect(bridge: IAdbBridge, requestPermission: Boolean)
    suspend fun deviceProfile(session: CoreSession): DeviceProfile = session.compatibility.deviceProfile()
    suspend fun openSystem(session: CoreSession, screen: SystemSettingsScreen)
    fun openLink(url: String)
    fun license(): String
}

data class AppState(
    val theme: AppTheme = AppTheme.Cupertino,
    val themeMode: ThemeMode = ThemeMode.System,
    val language: AppLanguage = AppLanguage.System,
    val locale: String = "zh-CN",
    val tab: Int = 0,
    val page: String = "home",
    val title: String = "设置",
    val nested: Boolean = false,
    val settings: SettingsUiState = SettingsUiState(),
    val mode: WorkMode = WorkMode.Shell,
    val rootShell: Boolean = false,
    val serial: String = "",
    val connection: BridgeState = BridgeState.Disconnected,
    val connecting: Boolean = false,
    val activationError: String? = null,
    val dialog: AppDialog? = null,
    val menu: AppMenu? = null,
    val connectionScreen: ConnectionScreen = ConnectionScreen.Overview,
    val deviceInfo: DeviceInfoState = DeviceInfoState(),
)

sealed interface AppDialog {
    val title: String
    data class Message(override val title: String, val text: String) : AppDialog
    data class Report(override val title: String, val text: String) : AppDialog
    data class Input(override val title: String, val summary: String, val initial: String, val integer: Boolean, val save: (String) -> Boolean) : AppDialog
    data class Choose(override val title: String, val options: List<String>, val selection: Int, val select: (Int) -> Unit) : AppDialog
}

/** Owns the screen/session lifecycle. Theme changes only replace rendering, never the session. */
class AppController(val platform: AppPlatform, dispatcher: CoroutineDispatcher) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(AppState(theme = platform.readTheme(), themeMode = platform.readThemeMode(), language = platform.readLanguage(), locale = platform.readLanguage().locale(platform.systemLocale), mode = platform.readWorkMode(), rootShell = platform.readRootShell()))
    val state: StateFlow<AppState> = mutable.asStateFlow()
    private val strings get() = AppStrings(state.value.locale)
    private fun t(text: String) = strings.text(text)
    private val history = mutableListOf<String>()
    private var bridge = platform.createBridge(state.value.mode, "", state.value.mode == WorkMode.Root && state.value.rootShell)
    private var session = Core.open(bridge, ApplicationSettings.catalog(), compatibility = NetworkCompatibility.registry)
    private var model: SettingsViewModel? = null
    private var pageJob: Job? = null
    private var connectionJob: Job? = null
    private var deviceJob: Job? = null
    private val reported = LinkedHashSet<String>()
    private var closed = false

    init { watchConnection(); showPage("home") }

    fun start() { if (!platform.desktop) connect(false) }

    fun selectTab(index: Int) {
        if (index !in 0..2) return
        if (index == state.value.tab) {
            if (index == 1 && state.value.connectionScreen == ConnectionScreen.DeviceInfo) showConnectionOverview()
            return
        }
        dismissMenu(); dismissDialog(); stopPage(); deviceJob?.cancel()
        val device = index == 1 && state.value.connectionScreen == ConnectionScreen.DeviceInfo
        mutable.update { it.copy(tab = index, title = if (device) t("设备信息") else strings.tabs[index], nested = device) }
        if (index == 0) showPage(state.value.page)
        else if (device) loadDeviceInfo()
    }

    fun navigate(page: String) {
        if (page.startsWith("appearance")) { selectTab(2); return }
        history.add(state.value.page)
        showPage(page)
    }

    /** Returns false at the root, allowing the platform to close its window. */
    fun back(): Boolean {
        if (state.value.menu != null) { dismissMenu(); return true }
        if (state.value.dialog != null) { dismissDialog(); return true }
        if (state.value.tab == 1 && state.value.connectionScreen == ConnectionScreen.DeviceInfo) { showConnectionOverview(); return true }
        if (state.value.tab != 0) { selectTab(0); return true }
        if (state.value.page == "home") return false
        showPage(if (history.isNotEmpty()) history.removeAt(history.lastIndex) else "home")
        return true
    }

    fun selectTheme(theme: AppTheme) {
        if (theme == state.value.theme) return
        savePreference({ platform.saveTheme(theme) }) { it.copy(theme = theme) }
    }

    fun selectThemeMode(mode: ThemeMode) {
        if (mode == state.value.themeMode) return
        savePreference({ platform.saveThemeMode(mode) }) { it.copy(themeMode = mode) }
    }

    fun selectLanguage(language: AppLanguage) {
        if (language == state.value.language) return
        if (savePreference({ platform.saveLanguage(language) }) { it.copy(language = language, locale = language.locale(platform.systemLocale)) }) relocalize()
    }

    private fun savePreference(save: () -> Unit, update: (AppState) -> AppState): Boolean = try {
        save(); mutable.update(update); true
    } catch (_: Exception) { message("无法保存设置", "请检查本机配置目录的写入权限。"); false }

    private fun relocalize() {
        if (state.value.tab == 0) showPage(state.value.page)
        else mutable.update { it.copy(title = if (it.tab == 1 && it.connectionScreen == ConnectionScreen.DeviceInfo) t("设备信息") else strings.tabs[it.tab]) }
    }

    fun updateSerial(serial: String) { mutable.update { it.copy(serial = serial) } }

    val workModeOptions: List<String>
        get() = (if (platform.desktop) listOf("ADB", "root（通过 ADB）") else listOf("ADB（Shizuku）", "root")).map(::t)

    fun selectMode(mode: WorkMode) {
        if (state.value.connecting || mode == state.value.mode) return
        try { platform.saveWorkMode(mode) }
        catch (_: Exception) { message("无法保存设置", "请检查本机配置目录的写入权限。"); return }
        mutable.update { it.copy(mode = mode) }
        connect(true, replace = true)
    }

    fun setRootShell(enabled: Boolean) {
        if (state.value.mode != WorkMode.Root || state.value.connecting || enabled == state.value.rootShell) return
        try { platform.saveRootShell(enabled) }
        catch (_: Exception) { message("无法保存设置", "请检查本机配置目录的写入权限。"); return }
        mutable.update { it.copy(rootShell = enabled) }
        connect(true, replace = true)
    }

    fun connect(requestPermission: Boolean = true, replace: Boolean = platform.desktop) {
        if (state.value.connecting || (platform.desktop && state.value.serial.isBlank() && !replace)) return
        mutable.update { it.copy(connecting = true, activationError = null) }
        scope.launch {
            try {
                if (replace) {
                    stopPage(); connectionJob?.cancel(); deviceJob?.cancel()
                    mutable.update { it.copy(deviceInfo = DeviceInfoState()) }
                    session.close()
                    bridge = platform.createBridge(state.value.mode, state.value.serial.trim(), state.value.mode == WorkMode.Root && state.value.rootShell)
                    session = Core.open(bridge, ApplicationSettings.catalog(), compatibility = NetworkCompatibility.registry)
                    watchConnection()
                    if (state.value.tab == 0) showPage(state.value.page)
                }
                if (!platform.desktop || state.value.serial.isNotBlank()) platform.connect(bridge, requestPermission)
            } catch (exception: Exception) {
                // A Bridge timeout is a failed attempt; cancellation of this controller still propagates.
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(activationError = when {
                    exception is ShellIdentityUnavailableException -> "当前 root 实现无法切换到 shell 身份"
                    state.value.mode == WorkMode.Root -> "未获得 root 权限"
                    else -> "启用失败，请检查设备和授权"
                }) }
            } finally { mutable.update { it.copy(connecting = false, connection = bridge.connectionState.value) } }
        }
    }

    fun refreshOnResume() {
        val locale = state.value.language.locale(platform.systemLocale)
        if (locale != state.value.locale) { mutable.update { it.copy(locale = locale) }; relocalize() }
        else if (bridge.connectionState.value == BridgeState.Ready) {
            if (state.value.tab == 1 && state.value.connectionScreen == ConnectionScreen.DeviceInfo) loadDeviceInfo()
            else model?.refresh()
        }
    }

    private fun watchConnection() {
        val current = bridge
        connectionJob = scope.launch { current.connectionState.collect { connection ->
            if (bridge !== current) return@collect
            mutable.update { it.copy(connection = connection, activationError = if (connection == BridgeState.Ready) null else it.activationError) }
            model?.refresh()
            if (connection == BridgeState.Ready && state.value.tab == 1 && state.value.connectionScreen == ConnectionScreen.DeviceInfo && state.value.deviceInfo.error != null) loadDeviceInfo()
        } }
    }

    private fun stopPage() { pageJob?.cancel(); model?.close(); model = null }

    private fun showPage(id: String) {
        stopPage()
        val title = if (id == "home") t("设置") else ""
        mutable.update { it.copy(page = id, title = title, nested = id != "home", settings = SettingsUiState(), dialog = null, menu = null) }
        val current = session.viewModels.settings(DeviceTarget(), id, state.value.locale).also { model = it }
        pageJob = scope.launch {
            launch { current.state.collect { settings ->
                if (model !== current) return@collect
                mutable.update { it.copy(settings = settings, title = if (id == "home") t("设置") else settings.page?.title.orEmpty()) }
                settings.items.forEach { item -> reportFailure(item.status.lastResult) }
            } }
            launch { current.effects.collect { effect ->
                if (model !== current) return@collect
                when (effect) {
                    is SettingsEffect.Navigate -> navigate(effect.pageId)
                    is SettingsEffect.OpenSystemSettings -> openSystem(effect.screen)
                }
            } }
        }
        current.enter()
    }

    fun activate(ui: SettingUiItem) {
        val current = model ?: return
        val control = (ui.component as? SettingRow)?.control
        if (!ui.isEnabled && ui.component !is NavigationRow) {
            val snapshot = ui.status.observation?.snapshot
            message(ui.component.title, when {
                snapshot?.access == SettingAccess.Unsupported -> "此设备不支持这项设置。"
                snapshot?.message?.contains("SIM") == true -> "没有可用的数据 SIM 卡。"
                else -> "此设置暂不可用，请检查工作模式和连接。"
            })
            return
        }
        when (control) {
            is RowControl.Input -> {
                val initial = when (val value = ui.value) { is SettingValue.TextValue -> value.value; is SettingValue.IntegerValue -> value.value.toString(); else -> "" }
                mutable.update { it.copy(dialog = AppDialog.Input(ui.component.title, ui.component.summary, initial, control.integer) { text ->
                    val value = if (control.integer) text.toLongOrNull()?.let(SettingValue::IntegerValue) else SettingValue.TextValue(text.trim())
                    if (value == null) false else {
                        if (model === current) current.dispatch(SettingsEvent.ChangeValue(ui.id, value, ui.token))
                        true
                    }
                }) }
            }
            is RowControl.Choice -> mutable.update { it.copy(dialog = AppDialog.Choose(ui.component.title, control.options.map { option -> option.title }, control.options.indexOfFirst { option -> option.id == (ui.value as? SettingValue.ChoiceValue)?.id }) { index ->
                if (model === current) current.dispatch(SettingsEvent.SelectChoice(ui.id, control.options[index].id, ui.token))
            }) }
            RowControl.Toggle -> toggle(ui)
            else -> current.dispatch(SettingsEvent.Activate(ui.id, ui.token))
        }
    }

    fun toggle(ui: SettingUiItem) {
        val checked = (ui.value as? SettingValue.BooleanValue)?.value ?: return
        if (ui.isEnabled) model?.dispatch(SettingsEvent.ChangeValue(ui.id, SettingValue.BooleanValue(!checked), ui.token))
    }

    private fun reportFailure(result: SettingResult?) {
        if (result == null || result.outcome in listOf(SettingOutcome.Verified, SettingOutcome.AlreadySatisfied) || !reported.add(result.operationId)) return
        if (reported.size > 100) reported.remove(reported.first())
        message("未能完成修改", result.reason?.message ?: "系统未确认这次修改，请检查当前设置。")
    }

    fun showDevice() {
        if (state.value.tab != 1) selectTab(1)
        mutable.update { it.copy(connectionScreen = ConnectionScreen.DeviceInfo, title = t("设备信息"), nested = true, menu = null, dialog = null) }
        loadDeviceInfo()
    }

    private fun showConnectionOverview() {
        deviceJob?.cancel()
        mutable.update { it.copy(connectionScreen = ConnectionScreen.Overview, title = t("工作模式"), nested = false, menu = null, dialog = null) }
    }

    private fun loadDeviceInfo() {
        deviceJob?.cancel()
        if (platform.desktop && bridge.connectionState.value != BridgeState.Ready) {
            mutable.update { it.copy(deviceInfo = DeviceInfoState(error = "请先连接设备")) }
            return
        }
        val current = session
        mutable.update { it.copy(deviceInfo = DeviceInfoState(loading = true)) }
        deviceJob = scope.launch {
            try {
                val fields = DeviceProfileReport.fields(platform.deviceProfile(current))
                if (isActive && session === current && state.value.tab == 1 && state.value.connectionScreen == ConnectionScreen.DeviceInfo)
                    mutable.update { it.copy(deviceInfo = DeviceInfoState(fields = fields)) }
            } catch (exception: CancellationException) { throw exception }
            catch (_: Exception) {
                if (isActive && session === current) mutable.update { it.copy(deviceInfo = DeviceInfoState(error = "无法读取设备信息，请检查连接")) }
            }
        }
    }

    fun showMenu(menu: AppMenu) {
        if (state.value.dialog == null) mutable.update { it.copy(menu = menu) }
    }
    fun dismissMenu() { mutable.update { it.copy(menu = null) } }
    fun selectMenu(index: Int) {
        val menu = state.value.menu ?: return
        if (index !in menu.options.indices) return
        dismissMenu()
        menu.select(index)
    }

    fun showLicense() { mutable.update { it.copy(menu = null, dialog = AppDialog.Report("MIT License", platform.license())) } }
    fun openLink(url: String) { try { platform.openLink(url) } catch (_: Exception) { message("无法打开链接", "没有可用的浏览器") } }
    private fun openSystem(screen: SystemSettingsScreen) { scope.launch {
        try { platform.openSystem(session, screen) }
        catch (exception: CancellationException) { throw exception }
        catch (_: Exception) { message("无法打开设置", "设备不支持此入口，或当前连接不可用。") }
    } }
    fun message(title: String, text: String) { mutable.update { it.copy(menu = null, dialog = AppDialog.Message(t(title), t(text))) } }
    fun dismissDialog() { mutable.update { it.copy(dialog = null) } }

    fun saveNavigation(): List<String> = listOf(state.value.tab.toString(), state.value.page, "connection:${state.value.connectionScreen.name}") + history
    fun restoreNavigation(saved: List<String>) {
        if (saved.size < 2) return
        val connection = saved.getOrNull(2)?.takeIf { it.startsWith("connection:") }
        history.clear(); history.addAll(saved.drop(if (connection != null) 3 else 2))
        val legacyAppearance = saved[1].startsWith("appearance")
        if (legacyAppearance) history.clear()
        val tab = if (legacyAppearance) 2 else (saved[0].toIntOrNull() ?: 0).coerceIn(0, 2)
        val page = if (legacyAppearance) "home" else saved[1]
        val screen = if (connection == "connection:DeviceInfo") ConnectionScreen.DeviceInfo else ConnectionScreen.Overview
        stopPage(); deviceJob?.cancel()
        mutable.update { it.copy(tab = tab, page = page, connectionScreen = screen, menu = null, dialog = null,
            nested = if (tab == 0) page != "home" else tab == 1 && screen == ConnectionScreen.DeviceInfo,
            title = if (tab == 1 && screen == ConnectionScreen.DeviceInfo) t("设备信息") else strings.tabs[tab]) }
        if (tab == 0) showPage(page)
        else if (tab == 1 && screen == ConnectionScreen.DeviceInfo) loadDeviceInfo()
    }

    fun close() {
        if (closed) return
        closed = true; stopPage(); deviceJob?.cancel(); scope.cancel()
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleanup.launch { try { session.close() } finally { cleanup.cancel() } }
    }
}
