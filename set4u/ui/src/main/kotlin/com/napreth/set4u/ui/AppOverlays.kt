@file:OptIn(com.slapps.cupertino.ExperimentalCupertinoApi::class)

package com.napreth.set4u.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.slapps.cupertino.CupertinoSurface
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import io.github.suqi8.coui.kmp.basic.Card as CouiCard

/** Keep menus in the main window; a separate window can lose focus on Android 37. */
@Composable
internal fun AppMenuOverlay(menu: AppMenu, dismiss: () -> Unit, select: (Int) -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val focus = remember { FocusRequester() }
    val colors = LocalColors.current
    val strings = LocalStrings.current
    SubcomposeLayout(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = 8.dp.roundToPx()
        val menuWidth = minOf(248.dp.roundToPx(), (width - margin * 2).coerceAtLeast(1))
        val menuHeight = minOf(320.dp.roundToPx(), (height - margin * 2).coerceAtLeast(1))
        val backdrop = subcompose("dismiss") {
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = strings.text("关闭菜单"), onClick = dismiss))
        }.single().measure(Constraints.fixed(width, height))
        val card = subcompose("menu") {
            LaunchedEffect(menu) { focus.requestFocus() }
            val content: @Composable ColumnScope.() -> Unit = {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp).semantics { paneTitle = menu.title; isTraversalGroup = true }) {
                    menu.options.forEachIndexed { index, label ->
                        ThemeRow(label, Modifier.semantics { selected = menu.selection == index }, action = { select(index) }) {
                            if (menu.selection == index) Icon(AppIcons.Check, strings.text("已选择"), Modifier.size(22.dp), tint = colors.accent)
                            else Spacer(Modifier.size(22.dp))
                        }
                    }
                }
            }
            val modifier = Modifier.shadow(12.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { dismiss(); true } else false
                }.focusable()
            when (LocalTheme.current) {
                AppTheme.Material -> Card(modifier, colors = CardDefaults.cardColors(containerColor = colors.surface), content = content)
                AppTheme.Cupertino -> CupertinoSurface(modifier, shape = RoundedCornerShape(20.dp), color = colors.surface) { Column(content = content) }
                AppTheme.Miuix -> MiuixCard(modifier = modifier, insideMargin = PaddingValues(0.dp), content = content)
                AppTheme.Coui -> CouiCard(modifier = modifier, insideMargin = PaddingValues(0.dp), content = content)
            }
        }.single().measure(Constraints(minWidth = menuWidth, maxWidth = menuWidth, maxHeight = menuHeight))
        val anchor = menu.anchor.translate(Offset(-origin.x, -origin.y))
        val x = if (layoutDirection == LayoutDirection.Ltr) anchor.right.toInt() - card.width else anchor.left.toInt()
        val below = anchor.bottom.toInt()
        val y = if (below + card.height <= height - margin) below else anchor.top.toInt() - card.height
        layout(width, height) {
            backdrop.place(0, 0)
            card.place(x.coerceIn(margin, (width - card.width - margin).coerceAtLeast(margin)), y.coerceIn(margin, (height - card.height - margin).coerceAtLeast(margin)))
        }
    }
}

@Composable
internal fun AppModalLayer(dismiss: () -> Unit, content: @Composable () -> Unit) {
    val focus = remember { FocusRequester() }
    val strings = LocalStrings.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .38f)).clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = strings.text("关闭对话框"), onClick = dismiss))
        Box(Modifier.align(Alignment.Center).padding(20.dp).widthIn(max = if (LocalTheme.current == AppTheme.Cupertino) 340.dp else 560.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            .focusRequester(focus).onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { dismiss(); true } else false
            }.focusable()) { content() }
    }
}
