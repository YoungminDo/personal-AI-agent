package com.parkingmemo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

// Garage signage palette, same as the prototype: concrete dark, safety yellow, level colours.
val Bg = Color(0xFF101314)
val CardBg = Color(0xFF1C2124)
val Ink = Color(0xFFF1F3F3)
val Muted = Color(0xFF9AA3A6)
val LineC = Color(0xFF2C3337)
val Accent = Color(0xFFF2B705)
val OnAccent = Color(0xFF131617)
val Danger = Color(0xFFC4423A)
val Ok = Color(0xFF22844C)
val ArriveBg = Color(0xFF2A2610)

fun levelColor(label: String?): Color = when (label) {
    "B1" -> Color(0xFFC29300)
    "B2" -> Color(0xFF22844C)
    "B3" -> Color(0xFF2C64C8)
    "B4" -> Color(0xFFC4423A)
    "B5" -> Color(0xFF7B4FB0)
    "B6" -> Color(0xFF2E8C8C)
    else -> Color(0xFF596164)
}

@Composable
fun ParkingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = OnAccent,
            secondary = Accent,
            background = Bg,
            onBackground = Ink,
            surface = CardBg,
            onSurface = Ink,
        ),
        content = content,
    )
}

@Composable
fun FloorBadge(label: String?, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.2f))
            .background(levelColor(label)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.48f).sp,
        )
    }
}
