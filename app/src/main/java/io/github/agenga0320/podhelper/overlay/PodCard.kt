package io.github.agenga0320.podhelper.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agenga0320.podhelper.PodUiState

/** 仿 iPhone 打開盒蓋時的電量卡片。主畫面也用同一個元件當預覽。 */
@Composable
fun PodCard(state: PodUiState, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = cardColors()
    val p = state.pods
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(40.dp))
            .background(c.card)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                p?.modelName ?: "AirPods",
                Modifier.align(Alignment.Center).padding(top = 6.dp),
                color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            )
            if (onClose != null) {
                Box(
                    Modifier.align(Alignment.CenterEnd).size(30.dp).clip(CircleShape).background(c.closeBg).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = c.secondary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            Item("左耳", p?.leftBattery, p?.leftCharging == true, c) { PodShape(mirrored = false, c) }
            Item("右耳", p?.rightBattery, p?.rightCharging == true, c) { PodShape(mirrored = true, c) }
            Item(
                if (state.caseBatteryIsStale) "充電盒（上次）" else "充電盒",
                state.caseBattery, p?.caseCharging == true, c,
            ) { CaseShape(c) }
        }
    }
}

@Composable
private fun Item(label: String, battery: Int?, charging: Boolean, c: CardColors, icon: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(96.dp), contentAlignment = Alignment.BottomCenter) { icon() }
        Spacer(Modifier.height(14.dp))
        BatteryIndicator(battery, charging, c)
        Spacer(Modifier.height(4.dp))
        Text(label, color = c.secondary, fontSize = 13.sp)
    }
}

@Composable
private fun BatteryIndicator(level: Int?, charging: Boolean, c: CardColors) {
    val fill = when {
        level == null -> c.secondary
        charging -> Color(0xFF34C759)
        level <= 20 -> Color(0xFFFF3B30)
        else -> c.text
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 27.dp, height = 13.dp)) {
            val body = Size(size.width - 3.dp.toPx(), size.height)
            val r = CornerRadius(3.5.dp.toPx())
            drawRoundRect(c.secondary, size = body, cornerRadius = r, style = Stroke(1.2.dp.toPx()))
            val inset = 2.dp.toPx()
            val frac = (level ?: 0) / 100f
            drawRoundRect(fill, Offset(inset, inset), Size((body.width - inset * 2) * frac, body.height - inset * 2), CornerRadius(2.dp.toPx()))
            drawRoundRect(c.secondary, Offset(body.width + 1.dp.toPx(), size.height * 0.33f), Size(2.dp.toPx(), size.height * 0.34f), CornerRadius(1.dp.toPx()))
        }
        Spacer(Modifier.width(5.dp))
        Text(
            (if (charging) "⚡" else "") + (level?.let { "$it%" } ?: "--"),
            color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
        )
    }
}

/** 自己畫的簡化耳機外形（不使用 Apple 的圖片）。 */
@Composable
private fun PodShape(mirrored: Boolean, c: CardColors) {
    Canvas(Modifier.size(width = 46.dp, height = 90.dp).scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f)) {
        val w = size.width
        val h = size.height
        // 耳機頭
        drawOutlined(c) { color, style ->
            if (style == null) drawOval(color, Offset(0f, 0f), Size(w * 0.85f, h * 0.36f))
            else drawOval(color, Offset(0f, 0f), Size(w * 0.85f, h * 0.36f), style = style)
        }
        // 柄
        val stemW = w * 0.3f
        drawOutlined(c) { color, style ->
            val tl = Offset(w * 0.55f, h * 0.22f)
            val sz = Size(stemW, h * 0.78f)
            val cr = CornerRadius(stemW / 2)
            if (style == null) drawRoundRect(color, tl, sz, cr) else drawRoundRect(color, tl, sz, cr, style = style)
        }
        // 喇叭網
        drawOval(c.outline, Offset(w * 0.12f, h * 0.1f), Size(w * 0.22f, h * 0.12f))
    }
}

@Composable
private fun CaseShape(c: CardColors) {
    Canvas(Modifier.size(width = 84.dp, height = 70.dp)) {
        val r = CornerRadius(size.height * 0.36f)
        drawRoundRect(c.pod, size = size, cornerRadius = r)
        drawRoundRect(c.outline, size = size, cornerRadius = r, style = Stroke(1.dp.toPx()))
        val lidY = size.height * 0.3f
        drawLine(c.outline, Offset(size.width * 0.04f, lidY), Offset(size.width * 0.96f, lidY), strokeWidth = 1.dp.toPx())
        drawCircle(Color(0xFF34C759), radius = 2.5.dp.toPx(), center = Offset(size.width / 2, size.height * 0.55f))
    }
}

private inline fun DrawScope.drawOutlined(c: CardColors, draw: DrawScope.(Color, Stroke?) -> Unit) {
    draw(c.pod, null)
    draw(c.outline, Stroke(1.dp.toPx()))
}

private data class CardColors(val card: Color, val text: Color, val secondary: Color, val closeBg: Color, val pod: Color, val outline: Color)

@Composable
private fun cardColors() = if (isSystemInDarkTheme()) {
    CardColors(Color(0xFF1C1C1E), Color.White, Color(0xFF8E8E93), Color(0xFF2C2C2E), Color(0xFFF2F2F7), Color(0xFF8E8E93))
} else {
    CardColors(Color.White, Color(0xFF1C1C1E), Color(0xFF8E8E93), Color(0xFFE5E5EA), Color(0xFFFFFFFF), Color(0xFFC7C7CC))
}
