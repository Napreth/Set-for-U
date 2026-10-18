package com.napreth.set4u.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

internal val LocalMenuPresenter = staticCompositionLocalOf<(AppMenu) -> Unit> { error("Missing application menu host") }

/** The anchor lives in the page; its menu is drawn above the page in the same window. */
@Composable
internal fun ThemeDropdownRow(title: String, options: List<String>, selection: Int, enabled: Boolean = true, select: (Int) -> Unit) {
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val present = LocalMenuPresenter.current
    ThemeRow(title, Modifier.onGloballyPositioned { anchor = it.boundsInRoot() }.semantics {
        stateDescription = options[selection]
        if (!enabled) disabled()
    }, action = if (enabled) ({ if (!anchor.isEmpty) present(AppMenu(title, options, selection, anchor, select)) }) else null) {
        ThemeValue(options[selection])
        Icon(AppIcons.Dropdown, null, Modifier.padding(start = 8.dp).size(20.dp), tint = LocalColors.current.secondary)
    }
}
