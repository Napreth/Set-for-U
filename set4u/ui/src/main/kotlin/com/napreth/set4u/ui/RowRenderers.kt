package com.napreth.set4u.ui

import androidx.compose.runtime.Composable
import com.napreth.set4u.core.settings.SettingValue
import com.napreth.set4u.core.viewmodel.SettingUiItem

class RowActions(val activate: () -> Unit, val changeValue: (SettingValue) -> Unit)
typealias RowRenderer = @Composable (SettingUiItem, RowActions, @Composable () -> Unit) -> Unit

/** Renderer keys are private to a page. An unknown key uses the standard component. */
class RowRenderers(pages: Map<String, Map<String, RowRenderer>>) {
    private val pages = pages.mapValues { it.value.toMap() }
    internal fun find(pageId: String, key: String?) = key?.let { pages[pageId]?.get(it) }
    companion object { val Empty = RowRenderers(emptyMap()) }
}
