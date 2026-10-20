@file:OptIn(com.slapps.cupertino.ExperimentalCupertinoApi::class)

package com.napreth.set4u.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.extra.SuperDialog

@Composable
internal fun ThemeDialog(dialog: AppDialog, dismiss: () -> Unit) {
    val t = LocalStrings.current::text
    var input by remember(dialog) { mutableStateOf((dialog as? AppDialog.Input)?.initial.orEmpty()) }
    var form by remember(dialog) { mutableStateOf((dialog as? AppDialog.Form)?.fields?.associate { it.key to it.initial }.orEmpty()) }
    var formError by remember(dialog) { mutableStateOf<String?>(null) }
    var error by remember(dialog) { mutableStateOf(false) }
    val confirm: () -> Unit = {
        if (dialog is AppDialog.Form) {
            formError = dialog.submit(form)
            if (formError == null) dismiss()
        } else if (dialog is AppDialog.Input && !dialog.save(input)) error = true
        else dismiss()
    }
    val body: @Composable () -> Unit = {
        Column(Modifier.widthIn(max = 560.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (dialog) {
                is AppDialog.Message -> ThemeText(dialog.text, size = 15)
                is AppDialog.Report -> SelectionContainer { ThemeText(dialog.text, size = 14) }
                is AppDialog.WifiShare -> WifiShareCode(dialog.payload)
                is AppDialog.Input -> {
                    if (dialog.summary.isNotBlank()) ThemeText(dialog.summary, size = 14)
                    ThemeInput(input, { input = it; error = false }, dialog.title, dialog.integer)
                    if (error) ThemeText(t("请输入有效整数"), size = 13)
                }
                is AppDialog.Form -> {
                    dialog.fields.filter { field -> field.visibleWhen?.let { form[it.first] == it.second } != false }.forEach { field ->
                        if (field.options.isEmpty()) ThemeInput(form[field.key].orEmpty(), { value -> form = form + (field.key to value); formError = null }, field.title, secret = field.secret)
                        else ThemeDropdownRow(field.title, field.options, field.options.indexOf(form[field.key]).coerceAtLeast(0)) { index -> form = form + (field.key to field.options[index]); formError = null }
                    }
                    formError?.let { ThemeText(t(it), size = 13) }
                }
                is AppDialog.Choose -> dialog.options.forEachIndexed { index, title ->
                    ThemeRow(title, action = { dismiss(); dialog.select(index) }) {
                        ThemeValue(if (index == dialog.selection) "✓" else "")
                    }
                }
            }
        }
    }
    val primary = when (dialog) { is AppDialog.Input, is AppDialog.Form -> t("保存"); is AppDialog.Report -> t("关闭"); else -> t("好") }
    when (LocalTheme.current) {
        AppTheme.Material, AppTheme.Cupertino -> AppModalLayer(dismiss) {
            val cupertino = LocalTheme.current == AppTheme.Cupertino
            val panel: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), contentAlignment = if (cupertino) Alignment.Center else Alignment.CenterStart) {
                        ThemeText(dialog.title, size = if (cupertino) 17 else 24, bold = true)
                    }
                    Box(Modifier.weight(1f, fill = false).padding(horizontal = 20.dp)) { body() }
                    if (cupertino) { Spacer(Modifier.height(16.dp)); ThemeDivider() }
                    Row(Modifier.fillMaxWidth().padding(if (cupertino) 4.dp else 16.dp), horizontalArrangement = Arrangement.End) {
                        val button = if (cupertino) Modifier.weight(1f) else Modifier
                        if (dialog is AppDialog.Input || dialog is AppDialog.Form || dialog is AppDialog.Choose) ThemeButton(t("取消"), dismiss, button)
                        if (dialog !is AppDialog.Choose) ThemeButton(primary, confirm, button)
                    }
                }
            }
            if (cupertino) com.slapps.cupertino.CupertinoSurface(shape = RoundedCornerShape(18.dp), color = LocalColors.current.surface, content = panel)
            else Surface(shape = RoundedCornerShape(28.dp), color = LocalColors.current.surface, tonalElevation = 6.dp, content = panel)
        }
        AppTheme.Miuix -> {
            val show = remember(dialog) { mutableStateOf(true) }
            SuperDialog(show = show, title = dialog.title, onDismissRequest = dismiss, defaultWindowInsetsPadding = false) {
                body()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (dialog is AppDialog.Input || dialog is AppDialog.Form || dialog is AppDialog.Choose) ThemeButton(t("取消"), dismiss)
                    if (dialog !is AppDialog.Choose) ThemeButton(primary, confirm)
                }
            }
        }
        AppTheme.Coui -> io.github.suqi8.coui.kmp.overlay.OverlayDialog(show = true, title = dialog.title, onDismissRequest = dismiss, defaultWindowInsetsPadding = false) {
            Column(Modifier.padding(horizontal = 24.dp)) { body() }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                if (dialog is AppDialog.Input || dialog is AppDialog.Form || dialog is AppDialog.Choose) ThemeButton(t("取消"), dismiss)
                if (dialog !is AppDialog.Choose) ThemeButton(primary, confirm)
            }
        }
    }
}
