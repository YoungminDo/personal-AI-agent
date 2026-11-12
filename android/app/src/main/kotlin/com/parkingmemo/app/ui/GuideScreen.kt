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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.parkingmemo.app.TrackerService
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
    val ctx = LocalContext.current
    var showCalibrate by remember { mutableStateOf(false) }
    if (showCalibrate) {
        CalibrateDialog(
            carLabel = s?.targetLabel,
            onConfirm = { current, car ->
                TrackerService.calibrate(ctx, current, car)
                showCalibrate = false
            },
            onDismiss = { showCalibrate = false },
        )
    }
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

        Box(Modifier.size(if (s?.askFloor == true) 150.dp else 270.dp), contentAlignment = Alignment.Center) {
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
        if (s != null && s.askFloor) {
            FloorAskCard(guess = s.guessLabel) { picked ->
                TrackerService.calibrate(ctx, picked, s.targetLabel ?: picked)
            }
        }
        Spacer(Modifier.weight(1f))
        if (s != null && s.mode != GuideMode.CALIBRATING) {
            TextButton(onClick = { showCalibrate = true }) {
                Text("층이 맞지 않나요? 지금 층 알려주기", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
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
private fun LevelChips(selected: String?, onSelect: (String) -> Unit) {
    val labels = listOf("B7", "B6", "B5", "B4", "B3", "B2", "B1", "1F", "2F", "3F")
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEach { l ->
            val on = l == selected
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) levelColor(l) else CardBg)
                    .border(2.dp, if (on) Accent else LineC, RoundedCornerShape(12.dp))
                    .clickable { onSelect(l) },
                contentAlignment = Alignment.Center,
            ) {
                Text(l, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
    }
}

/** Asked right after stepping off a lift or stairs: one tap removes any floor error. */
@Composable
private fun FloorAskCard(guess: String?, onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(2.dp, Accent, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("지금 몇 층인가요?", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "엘리베이터나 기둥의 층 표시를 보고 눌러주세요" + (guess?.let { " · 예상 $it" } ?: ""),
            color = Muted,
            fontSize = 13.sp,
        )
        LevelChips(guess, onPick)
    }
}

/** One-tap fix when the floor count is off: which floor am I on, which floor is the car on. */
@Composable
private fun CalibrateDialog(carLabel: String?, onConfirm: (current: String, car: String) -> Unit, onDismiss: () -> Unit) {
    var car by remember { mutableStateOf(carLabel) }
    var current by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("지금 몇 층에 있나요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("기둥이나 벽의 층 표시를 보고 골라주세요", color = Muted, fontSize = 13.sp)
                LevelChips(current) { current = it }
                Spacer(Modifier.height(4.dp))
                Text("차가 있는 층 (틀렸으면 바꿔주세요)", color = Muted, fontSize = 13.sp)
                LevelChips(car) { car = it }
            }
        },
        confirmButton = {
            val c = current
            val k = car
            TextButton(onClick = { if (c != null && k != null) onConfirm(c, k) }, enabled = c != null && k != null) {
                Text("맞추기")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
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
