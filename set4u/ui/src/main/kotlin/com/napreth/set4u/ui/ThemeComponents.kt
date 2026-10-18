@file:OptIn(com.slapps.cupertino.ExperimentalCupertinoApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.napreth.set4u.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slapps.cupertino.*
import com.slapps.cupertino.theme.CupertinoTheme
import com.slapps.cupertino.theme.lightColorScheme as cupertinoLight
import com.slapps.cupertino.theme.darkColorScheme as cupertinoDark
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles as miuixTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLight
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDark
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixButton
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopBar
import top.yukonga.miuix.kmp.basic.SmallTopAppBar as MiuixSmallTopBar
import top.yukonga.miuix.kmp.basic.NavigationBar as MiuixNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem
import io.github.suqi8.coui.kmp.basic.BasicComponent as CouiComponent
import io.github.suqi8.coui.kmp.basic.Card as CouiCard
import io.github.suqi8.coui.kmp.basic.Switch as CouiSwitch
import io.github.suqi8.coui.kmp.basic.Text as CouiText
import io.github.suqi8.coui.kmp.basic.TextButton as CouiButton
import io.github.suqi8.coui.kmp.basic.TextField as CouiTextField
import io.github.suqi8.coui.kmp.basic.TopAppBar as CouiTopBar
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar as CouiSmallTopBar
import io.github.suqi8.coui.kmp.basic.NavigationBar as CouiNavigationBar
import io.github.suqi8.coui.kmp.basic.NavigationBarItem as CouiNavigationBarItem
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.defaultTextStyles as couiTextStyles
import io.github.suqi8.coui.kmp.theme.lightColorScheme as couiLight
import io.github.suqi8.coui.kmp.theme.darkColorScheme as couiDark

internal val LocalDark = staticCompositionLocalOf { false }
internal val LocalTheme = staticCompositionLocalOf { AppTheme.Cupertino }
internal val LocalAppFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }
internal data class ThemeColors(val background: Color, val surface: Color, val text: Color, val secondary: Color, val accent: Color, val divider: Color)
internal val LocalColors = staticCompositionLocalOf { ThemeColors(Color(0xfff2f2f7), Color.White, Color(0xff1c1c1e), Color(0xff8e8e93), Color(0xff007aff), Color(0xffe5e5ea)) }

@Composable
internal fun AppThemeContent(theme: AppTheme, dark: Boolean, content: @Composable () -> Unit) {
    val font = LocalAppFontFamily.current
    val miuixTypography = remember(font) { miuixTextStyles().let { it.copy(
        main = it.main.copy(fontFamily = font), paragraph = it.paragraph.copy(fontFamily = font),
        body1 = it.body1.copy(fontFamily = font), body2 = it.body2.copy(fontFamily = font),
        button = it.button.copy(fontFamily = font),
    ) } }
    val couiTypography = remember(font) { couiTextStyles().let { it.copy(
        main = it.main.copy(fontFamily = font), paragraph = it.paragraph.copy(fontFamily = font),
        body1 = it.body1.copy(fontFamily = font), body2 = it.body2.copy(fontFamily = font),
        button = it.button.copy(fontFamily = font),
    ) } }
    CompositionLocalProvider(LocalTheme provides theme, LocalDark provides dark) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            when (theme) {
                AppTheme.Material -> {
                    val c = MaterialTheme.colorScheme
                    CompositionLocalProvider(LocalColors provides ThemeColors(c.surface, c.surfaceContainerLow, c.onSurface, c.onSurfaceVariant, c.primary, c.outlineVariant), content = content)
                }
                AppTheme.Cupertino -> CupertinoTheme(colorScheme = if (dark) cupertinoDark() else cupertinoLight()) {
                    val c = CupertinoTheme.colorScheme
                    CompositionLocalProvider(LocalColors provides ThemeColors(c.systemGroupedBackground, c.secondarySystemGroupedBackground, c.label, c.secondaryLabel, c.accent, c.separator), content = content)
                }
                AppTheme.Miuix -> MiuixTheme(colors = if (dark) miuixDark() else miuixLight(), textStyles = miuixTypography) {
                    val c = MiuixTheme.colorScheme
                    CompositionLocalProvider(LocalColors provides ThemeColors(c.surface, c.surfaceContainer, c.onSurface, c.onSurfaceVariantSummary, c.primary, c.dividerLine), content = content)
                }
                AppTheme.Coui -> COUITheme(colors = if (dark) couiDark() else couiLight(), textStyles = couiTypography) {
                    val c = COUITheme.colorScheme
                    CompositionLocalProvider(LocalColors provides ThemeColors(c.surface, c.background, c.onSurface, c.onSurfaceSecondary, c.primary, c.dividerLine), content = content)
                }
            }
        }
    }
}

@Composable
internal fun ThemeText(text: String, modifier: Modifier = Modifier, color: Color = LocalColors.current.text, size: Int = 17, bold: Boolean = false) {
    val weight = if (bold) FontWeight.SemiBold else FontWeight.Normal
    when (LocalTheme.current) {
        AppTheme.Material -> Text(text, modifier, color = color, fontSize = size.sp, fontWeight = weight)
        AppTheme.Cupertino -> CupertinoText(text, modifier, color = color, fontSize = size.sp, fontWeight = weight)
        AppTheme.Miuix -> MiuixText(text, modifier, color = color, fontSize = size.sp, fontWeight = weight, fontFamily = LocalAppFontFamily.current)
        AppTheme.Coui -> CouiText(text, modifier, color = color, fontSize = size.sp, fontWeight = weight, fontFamily = LocalAppFontFamily.current)
    }
}

@Composable
internal fun ThemeGroup(title: String = "", content: @Composable ColumnScope.() -> Unit) {
    if (title.isNotBlank()) ThemeText(title, Modifier.padding(start = 16.dp, bottom = 8.dp), color = LocalColors.current.secondary, size = 13)
    when (LocalTheme.current) {
        AppTheme.Material -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), content = content)
        AppTheme.Cupertino -> CupertinoSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = LocalColors.current.surface) { Column(content = content) }
        AppTheme.Miuix -> MiuixCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp), content = content)
        AppTheme.Coui -> CouiCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp), content = content)
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
internal fun ThemeDivider() { Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(.5.dp).background(LocalColors.current.divider)) }

@Composable
internal fun ThemeRow(title: String, modifier: Modifier = Modifier, icon: ImageVector? = null, action: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val start: @Composable () -> Unit = {
        if (icon != null) Icon(icon, null, Modifier.size(28.dp), tint = LocalColors.current.accent)
    }
    val end: @Composable RowScope.() -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { trailing() }
    }
    when (LocalTheme.current) {
        AppTheme.Material -> ListItem(
            headlineContent = { ThemeText(title) },
            modifier = modifier.then(if (action != null) Modifier.clickable(onClick = action) else Modifier),
            leadingContent = if (icon == null) null else start,
            trailingContent = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = end) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        // The string-title overloads force Medium, making body rows heavier than their values.
        AppTheme.Miuix -> BasicComponent(modifier = modifier, startAction = if (icon == null) null else start, endActions = end, onClick = action) { ThemeText(title) }
        AppTheme.Coui -> CouiComponent(modifier = modifier, startAction = if (icon == null) null else start, endActions = end, onClick = action) { ThemeText(title, size = 16) }
        AppTheme.Cupertino -> Row(
            modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (action != null) Modifier.clickable(onClick = action) else Modifier).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) start()
            ThemeText(title, Modifier.weight(1f))
            trailing()
        }
    }
}

@Composable
internal fun ThemeValue(value: String, chevron: Boolean = false) {
    if (value.isNotEmpty()) ThemeText(value, Modifier.widthIn(max = 160.dp), color = LocalColors.current.secondary, size = 15)
    if (chevron) Icon(AppIcons.Chevron, null, Modifier.size(20.dp), tint = LocalColors.current.secondary)
}

@Composable
internal fun ThemeSwitch(title: String, checked: Boolean, enabled: Boolean, change: () -> Unit) {
    // Libraries attach semantics at different depths. Expose one labelled, observed switch.
    Box(Modifier.clearAndSetSemantics {
        contentDescription = title; role = Role.Switch
        toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        if (enabled) onClick { change(); true } else disabled()
    }) {
        when (LocalTheme.current) {
            AppTheme.Material -> Switch(checked, { change() }, enabled = enabled)
            AppTheme.Cupertino -> CupertinoSwitch(checked, { change() }, enabled = enabled)
            AppTheme.Miuix -> MiuixSwitch(checked, { change() }, enabled = enabled)
            AppTheme.Coui -> CouiSwitch(checked, { change() }, enabled = enabled)
        }
    }
}

@Composable
internal fun ThemeButton(title: String, action: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    when (LocalTheme.current) {
        AppTheme.Material -> TextButton(action, modifier, enabled = enabled) { Text(title) }
        AppTheme.Cupertino -> CupertinoButton(action, modifier, enabled = enabled, colors = CupertinoButtonDefaults.plainButtonColors()) { CupertinoText(title) }
        AppTheme.Miuix -> MiuixButton(title, action, modifier, enabled = enabled)
        AppTheme.Coui -> CouiButton(title, action, modifier, enabled = enabled)
    }
}

@Composable
internal fun ThemeTextAction(title: String, action: () -> Unit, enabled: Boolean = true) {
    val color = LocalColors.current.accent
    Box(
        Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = action),
        contentAlignment = Alignment.CenterEnd,
    ) {
        ThemeText(title, color = if (enabled) color else color.copy(alpha = .4f), size = 15)
    }
}

@Composable
internal fun ThemeInput(value: String, change: (String) -> Unit, label: String, integer: Boolean = false) {
    val keyboard = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Text)
    val modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
    when (LocalTheme.current) {
        AppTheme.Material -> OutlinedTextField(value, change, modifier, label = { Text(label) }, singleLine = true, keyboardOptions = keyboard)
        AppTheme.Cupertino -> CupertinoTextField(value, change, modifier, placeholder = { CupertinoText(label) }, singleLine = true, keyboardOptions = keyboard)
        AppTheme.Miuix -> MiuixTextField(value, change, modifier, label = label, singleLine = true, keyboardOptions = keyboard)
        AppTheme.Coui -> CouiTextField(value, change, modifier, label = label, singleLine = true, keyboardOptions = keyboard)
    }
}

@Composable
internal fun ThemeHeading(title: String, nested: Boolean, back: () -> Unit) {
    val strings = LocalStrings.current
    val backLabel = strings.text("返回")
    val backIcon: @Composable () -> Unit = { IconButton(back, Modifier.semantics { contentDescription = backLabel }) { Icon(AppIcons.Back, backLabel, tint = LocalColors.current.accent) } }
    when (LocalTheme.current) {
        AppTheme.Material -> if (nested) TopAppBar({ Text(title) }, navigationIcon = backIcon, windowInsets = WindowInsets(0))
            else LargeTopAppBar({ Text(title) }, windowInsets = WindowInsets(0))
        AppTheme.Miuix -> if (nested) MiuixSmallTopBar(title, navigationIcon = backIcon, defaultWindowInsetsPadding = false)
            else MiuixTopBar(title, defaultWindowInsetsPadding = false)
        AppTheme.Coui -> if (nested) CouiSmallTopBar(title, navigationIcon = backIcon, defaultWindowInsetsPadding = false)
            else CouiTopBar(title, defaultWindowInsetsPadding = false)
        AppTheme.Cupertino -> if (nested) Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
            IconButton(back, Modifier.align(Alignment.CenterStart).semantics { contentDescription = backLabel }) { Icon(AppIcons.Chevron, null, Modifier.rotate(180f), tint = LocalColors.current.accent) }
            ThemeText(title, Modifier.padding(horizontal = 56.dp), bold = true)
        } else ThemeText(title, Modifier.padding(start = 22.dp, top = 24.dp, bottom = 14.dp), size = 34, bold = true)
    }
}

internal val tabIcons = listOf(AppIcons.Settings, AppIcons.Link, AppIcons.About)

@Composable
internal fun ThemeTabs(selected: Int, select: (Int) -> Unit) {
    val strings = LocalStrings.current
    val tabTitles = strings.tabs
    when (LocalTheme.current) {
        AppTheme.Material -> NavigationBar(windowInsets = WindowInsets(0)) {
            tabTitles.forEachIndexed { index, title -> NavigationBarItem(selected == index, { select(index) }, { Icon(tabIcons[index], null) }, Modifier.semantics { contentDescription = strings.tabDescription(title) }, label = { Text(title) }) }
        }
        AppTheme.Cupertino -> CupertinoNavigationBar(windowInsets = WindowInsets(0)) {
            tabTitles.forEachIndexed { index, title -> CupertinoNavigationBarItem(selected == index, { select(index) }, { Icon(tabIcons[index], null, tint = if (selected == index) LocalColors.current.accent else LocalColors.current.secondary) }, Modifier.semantics { contentDescription = strings.tabDescription(title) }, label = { CupertinoText(title) }) }
        }
        AppTheme.Miuix -> MiuixNavigationBar(defaultWindowInsetsPadding = false) {
            tabTitles.forEachIndexed { index, title -> MiuixNavigationBarItem(selected == index, { select(index) }, tabIcons[index], title, Modifier.semantics { contentDescription = strings.tabDescription(title) }) }
        }
        AppTheme.Coui -> CouiNavigationBar(defaultWindowInsetsPadding = false) {
            tabTitles.forEachIndexed { index, title -> CouiNavigationBarItem(selected == index, { select(index) }, tabIcons[index], title, Modifier.semantics { contentDescription = strings.tabDescription(title) }) }
        }
    }
}
