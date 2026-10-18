package com.napreth.set4u.ui

import android.content.Context

class AppearancePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var theme: AppTheme
        get() = AppTheme.fromId(preferences.getString("theme", null))
        set(value) { preferences.edit().putString("theme", value.id).apply() }
    var themeMode: ThemeMode
        get() = ThemeMode.fromId(preferences.getString("themeMode", null))
        set(value) { preferences.edit().putString("themeMode", value.id).apply() }
    var language: AppLanguage
        get() = AppLanguage.fromId(preferences.getString("language", null))
        set(value) { preferences.edit().putString("language", value.id).apply() }
}
