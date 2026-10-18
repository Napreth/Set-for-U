package com.napreth.set4u.desktop

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import java.awt.GraphicsEnvironment

/** Use an installed CJK sans face, rather than falling back to SimSun for Chinese glyphs. */
internal object DesktopFonts {
    @OptIn(ExperimentalTextApi::class)
    val ui: FontFamily by lazy {
        val installed = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val name = listOf("Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", "Noto Sans SC", "WenQuanYi Micro Hei", "Noto Sans", "DejaVu Sans")
            .firstOrNull { it in installed }
        name?.let { FontFamily(it) } ?: FontFamily.SansSerif
    }
}
