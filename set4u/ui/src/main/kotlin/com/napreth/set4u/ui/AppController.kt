package com.napreth.set4u.ui

import com.napreth.set4u.core.Core
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.compatibility.*
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.session.*
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.network.*
import com.napreth.set4u.core.network.form.WirelessForms
import com.napreth.set4u.core.form.*
import com.napreth.set4u.core.settings.form.SettingForms
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
enum class AboutScreen { Overview, Appearance, Debug }
enum class WifiScreen { Overview, Saved, Details, Advanced }
data class DeviceInfoState(val loading: Boolean = false, val fields: Map<String, String?> = emptyMap(), val error: String? = null)
data class AppMenu(val title: String, val options: List<String>, val selection: Int, val anchor: Rect, val select: (Int) -> Unit)

interface AppPlatform {
    val catalog: SettingsCatalog get() = ApplicationSettings.catalog()
    val compatibility: CompatibilityRegistry get() = NetworkCompatibility.registry
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
    fun readOwnPages(): Boolean = false
    fun saveOwnPages(enabled: Boolean) {}
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
    val ownPages: Boolean = false,
    val identity: ExecutionIdentity = ExecutionIdentity.Unknown,
    val aboutScreen: AboutScreen = AboutScreen.Overview,
    val wireless: WirelessUiState = WirelessUiState(),
    val wifiScreen: WifiScreen = WifiScreen.Overview,
    val wifiSelection: WifiNetwork? = null,
    val wifiReturnTo: WifiScreen = WifiScreen.Overview,
    val serial: String = "",
    val connection: BridgeState = BridgeState.Disconnected,
    val connecting: Boolean = false,
    val activationError: String? = null,
    val dialog: AppDialog? = null,
    val menu: AppMenu? = null,
    val connectionScreen: ConnectionScreen = ConnectionScreen.Overview,
    val deviceInfo: DeviceInfoState = DeviceInfoState(),
    val notice: String? = null,
    val searchQuery: String = "",
    val searchResults: List<AvailableEntryResult> = emptyList(),
    val searchLoading: Boolean = false,
)

sealed interface AppDialog {
    val title: String
    data class Message(override val title: String, val text: String) : AppDialog
    data class Report(override val title: String, val text: String) : AppDialog
    data class WifiShare(override val title: String, val payload: String) : AppDialog {
        override fun toString() = "WifiShare(title=$title)"
    }
    class Form<R>(val session: FormSession<R>, private val send: suspend (R) -> Unit) : AppDialog {
        override val title get() = session.definition.title
        suspend fun submit() = session.submit(send)
        override fun toString() = "Form(id=${session.definition.id})"
    }
    data class Choose(override val title: String, val options: List<String>, val selection: Int, val select: (Int) -> Unit) : AppDialog
}

/** Owns the screen/session lifecycle. Theme changes only replace rendering, never the session. */
class AppController(val platform: AppPlatform, private val dispatcher: CoroutineDispatcher) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(AppState(theme = platform.readTheme(), themeMode = platform.readThemeMode(), language = platform.readLanguage(), locale = platform.readLanguage().locale(platform.systemLocale), mode = platform.readWorkMode(), rootShell = platform.readRootShell(), ownPages = platform.readOwnPages()))
    val state: StateFlow<AppState> = mutable.asStateFlow()
    private val strings get() = AppStrings(state.value.locale)
    private fun t(text: String) = strings.text(text)
    private val history = mutableListOf<String>()
    private var bridge = platform.createBridge(state.value.mode, "", state.value.mode == WorkMode.Root && state.value.rootShell)
    private var session = Core.open(bridge, platform.catalog, compatibility = platform.compatibility)
    private var model: SettingsViewModel? = null
    private var wirelessModel: WirelessViewModel? = null
    private var pageJob: Job? = null
    private var connectionJob: Job? = null
    private var deviceJob: Job? = null
    private val reported = LinkedHashSet<String>()
    private var closed = false
    private var searchJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        mutable.update { it.copy(searchQuery = query, searchResults = emptyList(), searchLoading = query.isNotBlank()) }
        if (query.isBlank()) return
        val current = session
        val locale = state.value.locale
        searchJob = scope.launch {
            delay(150)
            try {
                val results = current.search.search(query, locale)
                if (session === current && state.value.searchQuery == query && state.value.locale == locale) mutable.update { it.copy(searchResults = results, searchLoading = false) }
            } catch (exception: CancellationException) { throw exception }
            catch (_: Exception) { if (session === current) mutable.update { it.copy(searchLoading = false, notice = t("搜索失败，请重试")) } }
        }
    }
    fun openSearchResult(result: AvailableEntryResult) {
        val destination = result.entry.destination
        search("")
        val system = destination?.systemScreen
        if (system != null) openSystem(system)
        else (destination?.pageId ?: result.entry.pageIds.sortedByDescending { it.length }.firstOrNull())?.let(::navigate)
    }
    fun dismissNotice() { mutable.update { it.copy(notice = null) } }
    fun showPageError() { state.value.settings.error?.let { message("页面加载失败", it) } }
    fun changeValue(ui: SettingUiItem, value: SettingValue) { model?.dispatch(SettingsEvent.ChangeValue(ui.id, value, ui.token)) }

    init { watchConnection(); showPage("home") }

    fun start() { if (!platform.desktop) connect(false) }

    fun selectTab(index: Int) {
        if (index !in 0..2) return
        if (index == state.value.tab) {
            if (index == 2 && state.value.aboutScreen != AboutScreen.Overview) showAbout(AboutScreen.Overview)
            if (index == 1 && state.value.connectionScreen == ConnectionScreen.DeviceInfo) showConnectionOverview()
            return
        }
        dismissMenu(); dismissDialog(); stopPage(); deviceJob?.cancel()
        val device = index == 1 && state.value.connectionScreen == ConnectionScreen.DeviceInfo
        val about = index == 2 && state.value.aboutScreen != AboutScreen.Overview
        mutable.update { it.copy(tab = index, title = if (device) t("设备信息") else if (about) t(if (it.aboutScreen == AboutScreen.Appearance) "外观" else "调试") else strings.tabs[index], nested = device || about) }
        if (index == 0) showPage(state.value.page)
        else if (device) loadDeviceInfo()
    }

    fun navigate(page: String) {
        if (page.startsWith("appearance")) { selectTab(2); return }
        val fallback = when (page) { "network.wifi" -> SystemSettingsScreen.Wifi; "network.bluetooth" -> SystemSettingsScreen.Bluetooth; "network.hotspot" -> SystemSettingsScreen.Hotspot; else -> null }
        if (fallback != null && !state.value.ownPages && state.value.identity != ExecutionIdentity.Root) { openSystem(fallback); return }
        history.add(state.value.page)
        showPage(page)
    }

    /** Returns false at the root, allowing the platform to close its window. */
    fun back(): Boolean {
        if (state.value.menu != null) { dismissMenu(); return true }
        if (state.value.dialog != null) { dismissDialog(); return true }
        if (state.value.tab == 0 && state.value.page == "network.wifi" && state.value.wifiScreen != WifiScreen.Overview) {
            val screen = if (state.value.wifiScreen == WifiScreen.Details) state.value.wifiReturnTo else WifiScreen.Overview
            mutable.update { it.copy(wifiScreen = screen, wifiSelection = null, title = wifiTitle(screen)) }
            return true
        }
        if (state.value.tab == 1 && state.value.connectionScreen == ConnectionScreen.DeviceInfo) { showConnectionOverview(); return true }
        if (state.value.tab == 2 && state.value.aboutScreen != AboutScreen.Overview) { showAbout(AboutScreen.Overview); return true }
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
        if (state.value.searchQuery.isNotBlank()) search(state.value.searchQuery)
        if (state.value.tab == 0) showPage(state.value.page)
        else mutable.update { it.copy(title = if (it.tab == 1 && it.connectionScreen == ConnectionScreen.DeviceInfo) t("设备信息") else if (it.tab == 2 && it.aboutScreen != AboutScreen.Overview) t(if (it.aboutScreen == AboutScreen.Appearance) "外观" else "调试") else strings.tabs[it.tab]) }
    }

    fun showAbout(screen: AboutScreen) {
        if (state.value.tab != 2) selectTab(2)
        mutable.update { it.copy(aboutScreen = screen, title = t(when (screen) { AboutScreen.Overview -> "关于"; AboutScreen.Appearance -> "外观"; AboutScreen.Debug -> "调试" }), nested = screen != AboutScreen.Overview, menu = null, dialog = null) }
    }
    fun setOwnPages(enabled: Boolean) {
        savePreference({ platform.saveOwnPages(enabled) }) { it.copy(ownPages = enabled) }
    }
    private fun wifiTitle(screen: WifiScreen = state.value.wifiScreen): String = when (screen) {
        WifiScreen.Overview -> "WLAN"
        WifiScreen.Saved -> t("已保存网络")
        WifiScreen.Details -> t("WLAN 详情")
        WifiScreen.Advanced -> t("高级 WLAN 设置")
    }
    fun showSavedWifi() {
        mutable.update { it.copy(wifiScreen = WifiScreen.Saved, wifiSelection = null, title = t("已保存网络")) }
    }
    fun showWifiDetails(network: WifiNetwork) {
        mutable.update { it.copy(wifiScreen = WifiScreen.Details, wifiSelection = network, wifiReturnTo = it.wifiScreen, title = t("WLAN 详情")) }
    }
    fun showAdvancedWifi() { mutable.update { it.copy(wifiScreen = WifiScreen.Advanced, wifiSelection = null, title = t("高级 WLAN 设置")) } }
    fun openWifiCertificate() = openSystem(SystemSettingsScreen.InstallCertificate)
    fun openWifiDirect() = openSystem(SystemSettingsScreen.WifiDirect)
    fun wifiPreference(option: String, enabled: Boolean) = wirelessAction("preference", mapOf("option" to option, "value" to enabled.toString()))
    fun configureWifi(network: WifiNetwork, option: String, value: String, extra: Map<String, String> = emptyMap()) {
        val saved = WifiNetworks.from(state.value.wireless.snapshot).updated(network).saved ?: return
        wirelessAction("configure", extra + mapOf("id" to saved.id, "ssid" to saved.name, "option" to option, "value" to value))
    }
    fun shareWifi(network: WifiNetwork) {
        val saved = WifiNetworks.from(state.value.wireless.snapshot).updated(network).saved ?: return
        val current = session
        val model = wirelessModel
        scope.launch {
            try {
                val result = current.handlers.queries.invoke(WirelessCapabilities.queries, DeviceTarget()) { h, context -> h.share(context, saved.id, saved.name) }.requireCompleted()
                if (session === current && wirelessModel === model && state.value.wifiScreen == WifiScreen.Details && state.value.wifiSelection?.key == network.key) replaceDialog(AppDialog.WifiShare(saved.name, result))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (session === current) message("无法分享网络", "无法读取此网络的分享凭据") }
        }
    }
    fun editWifiProxy(network: WifiNetwork) = showWirelessForm(WirelessForms.proxy(network))
    fun editWifiIp(network: WifiNetwork) {
        val current = if (network.connected) state.value.wireless.snapshot?.fields.orEmpty() else emptyMap()
        showWirelessForm(WirelessForms.ip(network, current))
    }
    fun editWifiPassword(network: WifiNetwork) = showWirelessForm(WirelessForms.password(network))
    fun <R> showForm(definition: FormDefinition<R>, submit: suspend (R) -> Unit) = replaceDialog(AppDialog.Form(FormSession(definition), submit))
    private fun showWirelessForm(definition: FormDefinition<WirelessRequest>) {
        val current = wirelessModel ?: return
        showForm(definition) { request ->
            check(wirelessModel === current) { "Stale wireless page" }
            current.apply(request.operation, request.parameters)
        }
    }
    fun connectWifiNetwork(network: WifiNetwork) {
        val latest = WifiNetworks.from(state.value.wireless.snapshot).updated(network)
        val saved = latest.saved
        when {
            latest.connected -> showWifiDetails(latest)
            saved != null -> wirelessAction("connectSaved", mapOf("id" to saved.id, "ssid" to latest.name))
            latest.security == "enterprise" -> openWirelessSystem()
            else -> connectWifi(latest.available ?: WirelessItem("", latest.name, "available", latest.security))
        }
    }
    fun forgetWifi(network: WifiNetwork) {
        val saved = WifiNetworks.from(state.value.wireless.snapshot).updated(network).saved ?: return
        wirelessAction("forget", mapOf("id" to saved.id, "ssid" to saved.name))
    }
    fun selectWirelessItem(item: WirelessItem) {
        val current = wirelessModel ?: return
        fun choose(options: List<String>, action: (Int) -> Unit) {
            replaceDialog(AppDialog.Choose(item.name, options.map(::t), -1) { index -> if (wirelessModel === current) action(index) })
        }
        if (current.kind == WirelessKind.Bluetooth) {
            if (item.category == "paired") choose(listOf(if (item.connected) "断开连接" else "连接", "取消配对")) { index ->
                current.apply(if (index == 1) "unpair" else if (item.connected) "disconnect" else "connect", mapOf("address" to item.id))
            } else current.apply("pair", mapOf("address" to item.id))
        } else {
            val networks = WifiNetworks.from(state.value.wireless.snapshot)
            (listOfNotNull(networks.current) + networks.saved + networks.others)
                .firstOrNull { it.saved?.id == item.id || it.available?.id == item.id }?.let(::connectWifiNetwork)
        }
    }
    fun connectWifi(item: WirelessItem? = null) = showWirelessForm(WirelessForms.connect(item))
    fun renameBluetooth() = showWirelessForm(WirelessForms.bluetoothName(state.value.wireless.snapshot?.fields?.get("name").orEmpty()))
    fun configureHotspot() = showWirelessForm(WirelessForms.hotspot(state.value.wireless.snapshot?.fields.orEmpty()))
    fun scanWireless() { wirelessModel?.reload(scan = true) }
    fun wirelessAction(operation: String, parameters: Map<String, String> = emptyMap()) { wirelessModel?.apply(operation, parameters) }
    fun openWirelessSystem() { when (wirelessModel?.kind) { WirelessKind.Wifi -> openSystem(SystemSettingsScreen.Wifi); WirelessKind.Bluetooth -> openSystem(SystemSettingsScreen.Bluetooth); WirelessKind.Hotspot -> openSystem(SystemSettingsScreen.Hotspot); else -> Unit } }

    fun updateSerial(serial: String) { mutable.update { it.copy(serial = serial) } }

    val workModeOptions: List<String>
        get() = (if (platform.desktop) listOf("ADB", "root（通过 ADB）") else listOf("ADB（Shizuku）", "root")).map(::t)

    fun selectMode(mode: WorkMode) {
        if (state.value.connecting || mode == state.value.mode) return
        try { platform.saveWorkMode(mode) }
        catch (_: Exception) { message("无法保存设置", "请检查本机配置目录的写入权限。"); return }
        mutable.update { it.copy(mode = mode, identity = ExecutionIdentity.Unknown) }
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
        mutable.update { it.copy(connecting = true, activationError = null, identity = ExecutionIdentity.Unknown) }
        scope.launch {
            try {
                if (replace) {
                    stopPage(); connectionJob?.cancel(); deviceJob?.cancel()
                    mutable.update { it.copy(deviceInfo = DeviceInfoState()) }
                    session.close()
                    bridge = platform.createBridge(state.value.mode, state.value.serial.trim(), state.value.mode == WorkMode.Root && state.value.rootShell)
                    session = Core.open(bridge, platform.catalog, compatibility = platform.compatibility)
                    if (state.value.searchQuery.isNotBlank()) search(state.value.searchQuery)
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
            } finally {
                mutable.update { it.copy(connecting = false, connection = bridge.connectionState.value) }
                if (state.value.tab == 0 && systemPage(state.value.page) != null && !state.value.ownPages && state.value.identity != ExecutionIdentity.Root) showPage(state.value.page)
            }
        }
    }

    fun refreshOnResume() {
        val locale = state.value.language.locale(platform.systemLocale)
        if (locale != state.value.locale) { mutable.update { it.copy(locale = locale) }; relocalize() }
        else if (bridge.connectionState.value == BridgeState.Ready) {
            if (state.value.tab == 1 && state.value.connectionScreen == ConnectionScreen.DeviceInfo) loadDeviceInfo()
            else refreshPage()
        }
    }

    private fun watchConnection() {
        val current = bridge
        connectionJob = scope.launch { current.connectionState.collect { connection ->
            if (bridge !== current) return@collect
            val identity = if (connection == BridgeState.Ready) try { session.executionEnvironment().identity } catch (e: CancellationException) { throw e } catch (_: Exception) { ExecutionIdentity.Unknown } else ExecutionIdentity.Unknown
            if (bridge !== current) return@collect
            mutable.update { it.copy(connection = connection, identity = identity, activationError = if (connection == BridgeState.Ready) null else it.activationError) }
            refreshPage()
            if (connection == BridgeState.Ready && state.value.tab == 1 && state.value.connectionScreen == ConnectionScreen.DeviceInfo && state.value.deviceInfo.error != null) loadDeviceInfo()
        } }
    }

    private fun refreshPage() {
        val current = model ?: return
        reloadWirelessAfter(current.refresh(), current, wirelessModel)
    }

    private fun reloadWirelessAfter(load: Job, current: SettingsViewModel, wireless: WirelessViewModel?) {
        if (wireless == null) return
        scope.launch {
            // The radio state is a short read; cold DEX deployment must not consume its queue deadline.
            load.join()
            if (!load.isCancelled && model === current && wirelessModel === wireless && bridge.connectionState.value == BridgeState.Ready) wireless.reload()
        }
    }

    private fun stopPage() { dismissDialog(); pageJob?.cancel(); model?.close(); model = null; wirelessModel?.close(); wirelessModel = null }

    private fun systemPage(id: String) = when (id) { "network.wifi" -> SystemSettingsScreen.Wifi; "network.bluetooth" -> SystemSettingsScreen.Bluetooth; "network.hotspot" -> SystemSettingsScreen.Hotspot; else -> null }

    private fun showPage(id: String, allowSystemFallback: Boolean = true) {
        val external = systemPage(id)
        if (allowSystemFallback && !state.value.connecting && external != null && !state.value.ownPages && state.value.identity != ExecutionIdentity.Root) {
            while (history.lastOrNull() == "network") history.removeAt(history.lastIndex)
            showPage("network")
            openSystem(external)
            return
        }
        stopPage()
        val title = if (id == "home") t("设置") else ""
        mutable.update { it.copy(page = id, title = title, nested = id != "home", settings = SettingsUiState(), wireless = WirelessUiState(), dialog = null, menu = null,
            wifiScreen = if (id == "network.wifi" && it.page == id) it.wifiScreen else WifiScreen.Overview,
            wifiSelection = if (id == "network.wifi" && it.page == id) it.wifiSelection else null) }
        val current = session.viewModels.settings(DeviceTarget(), id, state.value.locale).also { model = it }
        val kind = when (id) { "network.wifi" -> WirelessKind.Wifi; "network.bluetooth" -> WirelessKind.Bluetooth; "network.hotspot" -> WirelessKind.Hotspot; else -> null }
        val wireless = kind?.let { WirelessViewModel(session, it, dispatcher).also { wirelessModel = it } }
        pageJob = scope.launch {
            if (wireless != null) launch { wireless.state.collect { value -> if (wirelessModel === wireless) mutable.update { it.copy(wireless = value) } } }
            launch { current.state.collect { settings ->
                if (model !== current) return@collect
                mutable.update { it.copy(settings = settings, title = if (id == "home") t("设置") else if (id == "network.wifi") wifiTitle() else settings.page?.title.orEmpty()) }
                settings.items.forEach { item -> reportFailure(item.status.lastResult) }
            } }
            launch { current.effects.collect { effect ->
                if (model !== current) return@collect
                when (effect) {
                    is SettingsEffect.Rejected -> mutable.update { it.copy(notice = t(when {
                        effect.message.contains("Stale", true) || effect.message.contains("Dynamic", true) -> "列表已更新，请重试"
                        effect.message.contains("busy", true) -> "此设置正在修改，请稍后重试"
                        effect.message.contains("writable", true) -> "当前无法修改这项设置"
                        else -> "操作未提交，请检查输入或重新进入页面"
                    })) }
                    is SettingsEffect.Navigate -> navigate(effect.pageId)
                    is SettingsEffect.OpenSystemSettings -> openSystem(effect.screen)
                }
            } }
        }
        reloadWirelessAfter(current.enter(), current, wireless)
    }

    fun activate(ui: SettingUiItem) {
        val current = model ?: return
        if (ui.component is NavigationRow && !ui.isEnabled) { mutable.update { it.copy(notice = ui.availability?.reason ?: t("页面暂不可用")) }; return }
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
                showForm(SettingForms.input("setting.${ui.id}", ui.component.title, ui.component.summary, initial, control.integer)) { value ->
                    check(model === current) { "Stale settings page" }
                    current.dispatch(SettingsEvent.ChangeValue(ui.id, value, ui.token))
                }
            }
            is RowControl.Choice -> replaceDialog(AppDialog.Choose(ui.component.title, control.options.map { option -> option.title }, control.options.indexOfFirst { option -> option.id == (ui.value as? SettingValue.ChoiceValue)?.id }) { index ->
                if (model === current) current.dispatch(SettingsEvent.SelectChoice(ui.id, control.options[index].id, ui.token))
            })
            RowControl.Toggle -> toggle(ui)
            else -> current.dispatch(SettingsEvent.Activate(ui.id, ui.token))
        }
    }

    fun toggle(ui: SettingUiItem) {
        val checked = (ui.value as? SettingValue.BooleanValue)?.value ?: return
        if (ui.isEnabled) {
            wirelessModel?.cancelScan()
            val wireless = wirelessModel
            model?.dispatch(SettingsEvent.ChangeValue(ui.id, SettingValue.BooleanValue(!checked), ui.token))?.invokeOnCompletion {
                scope.launch { if (wirelessModel === wireless) wireless?.reload() }
            }
        }
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
        if (state.value.dialog == null || state.value.dialog is AppDialog.Form<*>) mutable.update { it.copy(menu = menu) }
    }
    fun dismissMenu() { mutable.update { it.copy(menu = null) } }
    fun selectMenu(index: Int) {
        val menu = state.value.menu ?: return
        if (index !in menu.options.indices) return
        dismissMenu()
        menu.select(index)
    }

    fun showLicense() = replaceDialog(AppDialog.Report("MIT License", platform.license()))
    fun openLink(url: String) { try { platform.openLink(url) } catch (_: Exception) { message("无法打开链接", "没有可用的浏览器") } }
    private fun openSystem(screen: SystemSettingsScreen) { scope.launch {
        try { platform.openSystem(session, screen) }
        catch (exception: CancellationException) { throw exception }
        catch (_: Exception) { message("无法打开设置", "设备不支持此入口，或当前连接不可用。") }
    } }
    fun message(title: String, text: String) = replaceDialog(AppDialog.Message(t(title), t(text)))
    private fun replaceDialog(dialog: AppDialog?) {
        val previous = state.value.dialog
        if (previous !== dialog) (previous as? AppDialog.Form<*>)?.session?.close()
        mutable.update { it.copy(dialog = dialog, menu = null) }
    }
    fun dismissDialog() = replaceDialog(null)
    fun dismissDialog(expected: AppDialog) { if (state.value.dialog === expected) dismissDialog() }

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
        if (tab == 0) showPage(page, allowSystemFallback = false)
        else if (tab == 1 && screen == ConnectionScreen.DeviceInfo) loadDeviceInfo()
    }

    fun close() {
        if (closed) return
        closed = true; stopPage(); deviceJob?.cancel(); scope.cancel()
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleanup.launch { try { session.close() } finally { cleanup.cancel() } }
    }
}
