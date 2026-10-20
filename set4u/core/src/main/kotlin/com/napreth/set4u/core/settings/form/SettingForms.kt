package com.napreth.set4u.core.settings.form

import com.napreth.set4u.core.form.*
import com.napreth.set4u.core.settings.SettingValue

object SettingForms {
    fun input(id: String, title: String, summary: String, initial: String, integer: Boolean): FormDefinition<SettingValue> {
        val value = TextField("value", title, initial, integer = integer, validate = {
            if (integer && it.toLongOrNull() == null) "请输入有效整数" else null
        })
        return FormDefinition(id, title, listOf(value), summary) { if (integer) SettingValue.IntegerValue(it[value].toLong()) else SettingValue.TextValue(it[value].trim()) }
    }
}
