@file:OptIn(com.slapps.cupertino.ExperimentalCupertinoApi::class)

package com.napreth.set4u.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.napreth.set4u.core.bridge.BridgeState
import com.napreth.set4u.core.settings.SettingValue
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.viewmodel.*
import com.slapps.cupertino.CupertinoScaffold
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import io.github.suqi8.coui.kmp.basic.Scaffold as CouiScaffold
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

/** The same pages and interactions on Android and desktop, using the selected component library. */
@Composable
fun Set4uApp(controller: AppController, renderers: RowRenderers = RowRenderers.Empty, icon: @Composable () -> Unit = {}) {
    val state by controller.state.collectAsState()
    val dark = when (state.themeMode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }
    SideEffect { controller.platform.applyDarkMode(dark) }
    CompositionLocalProvider(LocalStrings provides AppStrings(state.locale), LocalMenuPresenter provides controller::showMenu, LocalAppFontFamily provides controller.platform.uiFontFamily) {
        AppThemeContent(state.theme, dark) {
            val heading: @Composable () -> Unit = { ThemeHeading(state.title, state.nested) { controller.back() } }
            val tabs: @Composable () -> Unit = { ThemeTabs(state.tab, controller::selectTab) }
            val backdrop = rememberLayerBackdrop()
            val content: @Composable (PaddingValues) -> Unit = { padding ->
                // Observations update the same page without resetting its scroll position.
                key(state.tab, if (state.tab == 1) state.connectionScreen else if (state.tab == 2) state.aboutScreen else state.page,
                    if (state.tab == 0 && state.page == "network.wifi") state.wifiScreen to state.wifiSelection?.key else null) {
                    val scroll = rememberScrollState()
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                        if (state.tab == 0) SettingsPage(state, controller, renderers)
                        else Column(Modifier.widthIn(max = 800.dp).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp)) {
                            when (state.tab) {
                                1 -> ConnectionPage(state, controller)
                                2 -> AboutPage(state, controller, icon)
                            }
                            Spacer(Modifier.height(if (state.theme == AppTheme.Cupertino) 100.dp else 16.dp))
                        }
                        if (state.theme == AppTheme.Miuix || state.theme == AppTheme.Coui) state.dialog?.let { dialog -> key(dialog) { ThemeDialog(dialog) { controller.dismissDialog(dialog) } } }
                    }
                }
            }
            Box(Modifier.fillMaxSize().background(LocalColors.current.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
                val inlineDialog = state.dialog != null && (state.theme == AppTheme.Material || state.theme == AppTheme.Cupertino)
                Box(if (state.menu != null || inlineDialog) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) {
                when (state.theme) {
                    AppTheme.Material -> Scaffold(topBar = heading, bottomBar = tabs, containerColor = LocalColors.current.background, contentWindowInsets = WindowInsets(0), content = content)
                    AppTheme.Cupertino -> {
                        CupertinoScaffold(modifier = if (controller.platform.glassEffects) Modifier.layerBackdrop(backdrop) else Modifier, topBar = heading, containerColor = LocalColors.current.background, contentWindowInsets = WindowInsets(0), appBarsBlurAlpha = 0f, appBarsBlurRadius = 0.dp, content = content)
                        Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp)) {
                            CupertinoGlassTabs(backdrop, state.tab, controller::selectTab, controller.platform.glassEffects)
                        }
                    }
                    AppTheme.Miuix -> MiuixScaffold(topBar = heading, bottomBar = tabs, containerColor = LocalColors.current.background, contentWindowInsets = WindowInsets(0), content = content)
                    AppTheme.Coui -> CouiScaffold(topBar = heading, bottomBar = tabs, containerColor = LocalColors.current.background, contentWindowInsets = WindowInsets(0), content = content)
                }
                }
                if (inlineDialog) state.dialog?.let { dialog -> key(dialog) { ThemeDialog(dialog) { controller.dismissDialog(dialog) } } }
                state.menu?.let { AppMenuOverlay(it, controller::dismissMenu, controller::selectMenu) }
                state.notice?.let { notice ->
                    LaunchedEffect(notice) { kotlinx.coroutines.delay(5_000); controller.dismissNotice() }
                    Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp).padding(bottom = 90.dp).widthIn(max = 760.dp)) {
                        ThemeGroup { Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ThemeText(notice, Modifier.weight(1f), size = 15)
                            ThemeTextAction(LocalStrings.current.text("关闭"), controller::dismissNotice)
                        } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(state: AppState, controller: AppController, renderers: RowRenderers) {
    val strings = LocalStrings.current
    val unavailable = state.settings.availability?.takeIf { it.status != com.napreth.set4u.core.compatibility.AvailabilityStatus.Available }
    LazyColumn(Modifier.widthIn(max = 800.dp).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        if (state.page == "home") item("search") {
            ThemeInput(state.searchQuery, controller::search, strings.text("搜索设置"))
            Spacer(Modifier.height(20.dp))
        }
        state.settings.error?.let { error -> item("page-error") {
            ThemeGroup { ThemeRow(strings.text("部分内容加载失败"), action = controller::showPageError) { ThemeValue(strings.text("查看详情"), true) } }
        } }
        unavailable?.let { assessment -> item("page-unavailable") {
            ThemeGroup { ThemeText(assessment.reason ?: strings.text("页面暂不可用"), Modifier.padding(16.dp), size = 15) }
        } }
        if (state.page == "home" && state.searchQuery.isNotBlank()) {
            if (!state.searchLoading && state.searchResults.isEmpty()) item("no-results") { ThemeText(strings.text("没有找到相关设置"), Modifier.padding(16.dp), size = 15) }
            itemsIndexed(state.searchResults, key = { _, hit -> "search:${hit.entry.entryId}" }) { _, hit ->
                ThemeRow(hit.entry.title, action = { controller.openSearchResult(hit) }) { ThemeValue("", true) }
            }
        } else if (unavailable == null && !(state.page == "network.wifi" && state.wifiScreen != WifiScreen.Overview)) state.settings.containers.forEach { container ->
            if (container.component.title.isNotBlank()) item("${container.component.id}:title") {
                ThemeText(container.component.title, Modifier.padding(start = 16.dp, bottom = 8.dp), size = 13, color = LocalColors.current.secondary)
            }
            itemsIndexed(container.items, key = { _, ui -> "${container.component.id}:${ui.id}" }) { index, ui ->
                val shape = RoundedCornerShape(topStart = if (index == 0) 20.dp else 0.dp, topEnd = if (index == 0) 20.dp else 0.dp,
                    bottomStart = if (index == container.items.lastIndex) 20.dp else 0.dp, bottomEnd = if (index == container.items.lastIndex) 20.dp else 0.dp)
                Column(Modifier.clip(shape).background(LocalColors.current.surface)) {
                    val custom = renderers.find(state.page, (ui.component as? SettingRow)?.rendererKey)
                    if (custom == null) SettingItem(ui, controller)
                    else custom(ui, RowActions({ controller.activate(ui) }, { controller.changeValue(ui, it) })) { SettingItem(ui, controller) }
                    if (index < container.items.lastIndex) ThemeDivider()
                }
            }
            item("${container.component.id}:spacing") { Spacer(Modifier.height(24.dp)) }
        }
        wirelessPage(state, controller)
        item("bottom-space") { Spacer(Modifier.height(if (state.theme == AppTheme.Cupertino) 100.dp else 16.dp)) }
    }
}
@Composable
private fun SettingItem(ui: SettingUiItem, controller: AppController) {
    val strings = LocalStrings.current
    val item = ui.component
    val control = (item as? SettingRow)?.control
    val clickable = ui.isInteractive && !ui.isBusy
    val modifier = if (control is RowControl.Selection) Modifier.semantics {
        role = if (control.multiple) Role.Checkbox else Role.RadioButton
        selected = ui.selected == true
    } else Modifier
    ThemeRow(item.title, modifier, icon = if (item.icon == "network") AppIcons.Network else null,
        action = if (clickable) ({ controller.activate(ui) }) else null) {
        if (ui.isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        when (control) {
            RowControl.Toggle -> {
                val checked = (ui.value as? SettingValue.BooleanValue)?.value
                if (checked != null) ThemeSwitch(item.title, checked, ui.isEnabled) { controller.toggle(ui) }
                else if (ui.status.observation == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else ThemeValue(strings.text("不可用"))
            }
            is RowControl.Selection -> if (ui.selected == true) Icon(AppIcons.Check, strings.text("已选择"), Modifier.size(24.dp), tint = LocalColors.current.accent)
            else -> ThemeValue(strings.text(ui.displayValue()), item is NavigationRow || control is RowControl.Input || control is RowControl.Choice)
        }
        if (ui.status.readDeferred) ThemeText(strings.text("待更新"), color = LocalColors.current.secondary, size = 12)
    }
}

@Composable
private fun ConnectionPage(state: AppState, controller: AppController) {
    val t = LocalStrings.current::text
    if (state.connectionScreen == ConnectionScreen.DeviceInfo) { DeviceInfoPage(state.deviceInfo); return }
    ThemeGroup {
        ThemeDropdownRow(t("工作模式"), controller.workModeOptions, state.mode.ordinal, enabled = !state.connecting) { controller.selectMode(WorkMode.entries[it]) }
        ThemeDivider()
        ThemeRow(t("状态")) {
            ThemeValue(when {
                state.connecting -> t("正在启用…")
                state.activationError != null -> t(state.activationError)
                state.connection == BridgeState.Ready -> t("已启用")
                state.connection == BridgeState.Unauthorized -> t("待授权")
                else -> t("未启用")
            })
        }
        ThemeDivider()
        ThemeRow(t("授权")) {
            ThemeTextAction(t(when {
                state.activationError != null -> "重试"
                state.connection == BridgeState.Ready -> "重新获取"
                else -> "获取权限"
            }), { controller.connect(replace = true) }, enabled = !state.connecting && (!controller.platform.desktop || state.serial.isNotBlank()))
        }

    }
    if (controller.platform.desktop) ThemeGroup { Box(Modifier.padding(16.dp)) { ThemeInput(state.serial, controller::updateSerial, t("设备序列号")) } }
    ThemeGroup { ThemeRow(t("设备信息"), icon = AppIcons.Device, action = controller::showDevice) { ThemeValue("", true) } }
}

@Composable
private fun DeviceInfoPage(info: DeviceInfoState) {
    val strings = LocalStrings.current
    info.error?.let { ThemeGroup { ThemeText(strings.text(it), Modifier.padding(20.dp), color = LocalColors.current.secondary, size = 15) } }
    if (info.fields.isNotEmpty()) ThemeGroup {
        info.fields.entries.forEachIndexed { index, field ->
            if (index > 0) ThemeDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                ThemeText(strings.deviceField(field.key), color = LocalColors.current.secondary, size = 13)
                Spacer(Modifier.height(4.dp))
                SelectionContainer { ThemeText(field.value ?: strings.text("未知"), size = 16) }
            }
        }
    }
}

@Composable
private fun AboutPage(state: AppState, controller: AppController, icon: @Composable () -> Unit) {
    val t = LocalStrings.current::text
    if (state.aboutScreen == AboutScreen.Overview) Column(Modifier.fillMaxWidth().padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp))) { icon() }
        Spacer(Modifier.height(10.dp))
        ThemeText("Set-for-U", size = 22, bold = true)
    }
    when (state.aboutScreen) {
        AboutScreen.Appearance -> {
            ThemeGroup {
                ThemeDropdownRow(t("界面风格"), AppTheme.entries.map { t(it.title) }, state.theme.ordinal) { controller.selectTheme(AppTheme.entries[it]) }
                ThemeDivider()
                ThemeDropdownRow(t("主题"), ThemeMode.entries.map { t(it.title) }, state.themeMode.ordinal) { controller.selectThemeMode(ThemeMode.entries[it]) }
            }
            return
        }
        AboutScreen.Debug -> {
            val enabled = state.mode == WorkMode.Root && !state.connecting
            ThemeGroup {
                ThemeRow(t("以 root 模拟 shell 身份"), modifier = Modifier.then(if (enabled) Modifier else Modifier.alpha(.45f).semantics { disabled() }),
                    action = if (enabled) ({ controller.setRootShell(!state.rootShell) }) else null) {
                    ThemeSwitch(t("以 root 模拟 shell 身份"), state.rootShell, enabled) { controller.setRootShell(!state.rootShell) }
                }
                ThemeDivider()
                ThemeRow(t("始终使用内置设置页"), action = { controller.setOwnPages(!state.ownPages) }, summary = t("非 root 模式下，部分功能可能不可用")) {
                    ThemeSwitch(t("始终使用内置设置页"), state.ownPages, true) { controller.setOwnPages(!state.ownPages) }
                }
            }
            return
        }
        AboutScreen.Overview -> Unit
    }
    ThemeGroup(t("应用设置")) {
        ThemeRow(t("外观"), action = { controller.showAbout(AboutScreen.Appearance) }) { ThemeValue("", true) }
        ThemeDivider()
        ThemeDropdownRow(t("语言"), AppLanguage.entries.map { if (it == AppLanguage.System) t(it.title) else it.title }, state.language.ordinal) { controller.selectLanguage(AppLanguage.entries[it]) }
        ThemeDivider()
        ThemeRow(t("调试"), action = { controller.showAbout(AboutScreen.Debug) }) { ThemeValue("", true) }
    }
    ThemeGroup {
        ThemeRow(t("版本")) { ThemeValue(controller.platform.version) }
        ThemeDivider()
        ThemeRow(t("软件更新"), action = { controller.openLink("https://github.com/Napreth/Set-for-U/releases") }) { ThemeValue("", true) }
        ThemeDivider()
        ThemeRow(t("源代码"), action = { controller.openLink("https://github.com/Napreth/Set-for-U") }) { ThemeValue("GitHub", true) }
    }
    ThemeGroup { ThemeRow(t("许可证"), action = controller::showLicense) { ThemeValue("MIT", true) } }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ThemeText("MIT License · 2026 · Napreth", color = LocalColors.current.secondary, size = 13) }
}
