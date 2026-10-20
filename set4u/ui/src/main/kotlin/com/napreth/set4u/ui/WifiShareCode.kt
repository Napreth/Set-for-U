package com.napreth.set4u.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@Composable
internal fun WifiShareCode(payload: String) {
    val matrix = remember(payload) { QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 1, 1, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4)) }
    val label = LocalStrings.current.text("扫描二维码连接网络")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(230.dp).semantics { contentDescription = label }) {
            drawRect(Color.White)
            val step = size.minDimension / matrix.width
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y])
                drawRect(Color.Black, Offset(x * step, y * step), Size(step + .1f, step + .1f))
        }
        Spacer(Modifier.height(12.dp))
        ThemeText(label, size = 14, color = LocalColors.current.secondary)
    }
}
