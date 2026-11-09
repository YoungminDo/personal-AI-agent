package com.parkingmemo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parkingmemo.app.TrackerState
import com.parkingmemo.engine.GuideMode
import com.parkingmemo.engine.GuideState
import kotlin.math.roundToInt

@Composable
fun GuideScreen(onFound: () -> Unit, onClose: () -> Unit) {
    val state by TrackerState.guide.collectAsState()
    val message by TrackerState.message.collectAsState()
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler { onClose() }

    val s = state
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (s?.mode == GuideMode.ARRIVE) ArriveBg else Bg)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        FloorBadge(s?.targetLabel, 96.dp)
        val togo = s?.floorsToGo
        if (togo != null && togo != 0) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Danger)
                    .padding(horizontal = 12.dp, vertical = 3.dp),
            ) {
                Text(if (togo > 0) "▼ $togo" else "▲ ${-togo}", color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        Box(Modifier.size(270.dp), contentAlignment = Alignment.Center) {
            if (s == null) {
                Text("…", color = Muted, fontSize = 64.sp)
            } else {
                when (s.mode) {
                    GuideMode.CALIBRATING -> Text("…", color = Muted, fontSize = 64.sp)
                    GuideMode.FLOOR -> Text(
                        if ((s.hRelCarM ?: 0.0) > 0) "▼" else "▲",
                        color = Danger,
                        fontSize = 140.sp,
                    )
                    GuideMode.SETTLING, GuideMode.MEMORY -> Text("●", color = Accent, fontSize = 64.sp)
                    else -> ArrowDial(s.arrowDeg, s.coneDeg)
                }
            }
        }

        Text(distanceText(s), color = Ink, fontSize = 56.sp, fontWeight = FontWeight.SemiBold)
        Text(
            s?.text ?: "준비 중이에요",
            color = Ink,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Text(debugLine(s), color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        message?.let { Text(it, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(54.dp)) { Text("종료") }
            Button(onClick = onFound, modifier = Modifier.weight(1f).height(54.dp)) {
                Text("찾았어요", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ArrowDial(arrowDeg: Double?, coneDeg: Double) {
    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val r = size.minDimension / 2f * 0.92f
        drawCircle(color = LineC, radius = r, center = c, style = Stroke(width = 5f))
        if (arrowDeg == null) return@Canvas
        rotate(degrees = arrowDeg.toFloat(), pivot = c) {
            drawArc(
                color = Accent.copy(alpha = 0.22f),
                startAngle = (-90.0 - coneDeg).toFloat(),
                sweepAngle = (2 * coneDeg).toFloat(),
                useCenter = true,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(2 * r, 2 * r),
            )
            val p = Path().apply {
                moveTo(c.x, c.y - r * 0.72f)
                lineTo(c.x + r * 0.36f, c.y - r * 0.02f)
                lineTo(c.x + r * 0.12f, c.y - r * 0.10f)
                lineTo(c.x + r * 0.12f, c.y + r * 0.55f)
                lineTo(c.x - r * 0.12f, c.y + r * 0.55f)
                lineTo(c.x - r * 0.12f, c.y - r * 0.10f)
                lineTo(c.x - r * 0.36f, c.y - r * 0.02f)
                close()
            }
            drawPath(p, color = Accent)
        }
    }
}

private fun distanceText(s: GuideState?): String {
    val d = s?.distanceM ?: return ""
    return when (s.mode) {
        GuideMode.STEP -> "약 ${d.roundToInt()} m"
        GuideMode.VECTOR, GuideMode.ARRIVE -> "${d.roundToInt()} m"
        else -> ""
    }
}

private fun debugLine(s: GuideState?): String {
    if (s == null) return ""
    val h = s.hRelCarM?.let { "차보다 %+.1f m".format(it) } ?: "높이 기준 없음"
    val sig = s.sigmaM?.let { " · 오차 약 %.0f m".format(it) } ?: ""
    val ref = s.ref?.let { " · $it" } ?: ""
    val align = s.alignDeg?.let { if (it != 0.0) " · 방향 보정 %+.0f°".format(it) else "" } ?: ""
    return "$h$sig · 등급 ${s.tier}$ref$align"
}
