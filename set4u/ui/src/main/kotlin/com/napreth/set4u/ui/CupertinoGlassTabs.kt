package com.napreth.set4u.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/** Only the page is captured by Backdrop; the tab bar is a separate Compose layer. */
@Composable
internal fun CupertinoGlassTabs(backdrop: Backdrop, selected: Int, select: (Int) -> Unit, effectsEnabled: Boolean) {
    val strings = LocalStrings.current
    val tabTitles = strings.tabs
    val dark = LocalDark.current
    val surface = LocalColors.current.surface
    val secondary = LocalColors.current.secondary
    val currentSelect by rememberUpdatedState(select)
    val glass = if (effectsEnabled) Modifier.drawBackdrop(backdrop, shape = { RoundedCornerShape(35.dp) }, effects = {
        vibrancy(); blur(12.dp.toPx()); lens(12.dp.toPx(), 18.dp.toPx())
    }, onDrawSurface = { drawRect(surface.copy(alpha = .68f)) })
    else Modifier.shadow(8.dp, RoundedCornerShape(35.dp)).background(surface, RoundedCornerShape(35.dp))
    BoxWithConstraints(
        Modifier.widthIn(max = 440.dp).fillMaxWidth().height(70.dp)
            .then(glass)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { currentSelect((it.x / (size.width / 3f)).toInt().coerceIn(0, 2)) },
                    onDrag = { change, _ -> change.consume(); currentSelect((change.position.x / (size.width / 3f)).toInt().coerceIn(0, 2)) },
                )
            }.selectableGroup(),
    ) {
        val tabWidth = (maxWidth - 12.dp) / 3
        val offset by animateDpAsState(tabWidth * selected, spring(dampingRatio = .8f, stiffness = 500f))
        Box(Modifier.padding(6.dp).offset(x = offset).width(tabWidth).height(58.dp).clip(RoundedCornerShape(29.dp)).background((if (dark) Color(0xff48484a) else Color(0xffe1e4e9)).copy(alpha = .75f)))
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
            tabTitles.forEachIndexed { index, title ->
                val color = if (selected == index) LocalColors.current.accent else secondary
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(30.dp))
                        .selectable(selected == index, role = Role.Tab) { select(index) }
                        .semantics { contentDescription = strings.tabDescription(title) },
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(tabIcons[index], null, Modifier.size(25.dp), tint = color)
                    Spacer(Modifier.height(3.dp))
                    ThemeText(title, color = color, size = 11, bold = selected == index)
                }
            }
        }
    }
}
