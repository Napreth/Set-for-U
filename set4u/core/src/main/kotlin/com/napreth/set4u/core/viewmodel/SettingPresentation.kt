package com.napreth.set4u.core.viewmodel

import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.presentation.*

fun SettingUiItem.displayValue(): String {
    val row = component
    val options = ((row as? SettingRow)?.control as? RowControl.Choice)?.options.orEmpty()
    return when (val current = value) {
        null -> when (row) { is NavigationRow -> row.detail; is SettingRow -> row.detail }
        is SettingValue.BooleanValue -> if (current.value) "已开启" else "已关闭"
        is SettingValue.TextValue -> current.value.ifEmpty { "未设置" }
        is SettingValue.IntegerValue -> current.value.toString()
        is SettingValue.ChoiceValue -> options.firstOrNull { it.id == current.id }?.title ?: current.id
        is SettingValue.MultiChoiceValue -> current.ids.joinToString("、") { id -> options.firstOrNull { it.id == id }?.title ?: id }
        SettingValue.Action, is SettingValue.ActionValue -> ""
    }
}
