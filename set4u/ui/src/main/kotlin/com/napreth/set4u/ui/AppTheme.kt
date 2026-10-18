package com.napreth.set4u.ui

/** Local application appearance; independent of device settings and work mode. */
enum class AppTheme(val id: String, val title: String) {
    Material("material", "Google Material"),
    Cupertino("cupertino", "Apple Cupertino"),
    Miuix("miuix", "HyperOS Miuix"),
    Coui("coui", "ColorOS COUI");

    companion object {
        fun fromId(id: String?): AppTheme = entries.firstOrNull { it.id == id } ?: Cupertino
    }
}

enum class ThemeMode(val id: String, val title: String) {
    System("system", "跟随系统"), Light("light", "浅色"), Dark("dark", "深色");
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: System }
}

enum class AppLanguage(val id: String, val title: String) {
    System("system", "跟随系统"), Chinese("zh-CN", "简体中文"), English("en", "English");
    fun locale(systemLocale: String): String = when (this) {
        System -> if (systemLocale.startsWith("zh", ignoreCase = true)) "zh-CN" else "en"
        else -> id
    }
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: System }
}
