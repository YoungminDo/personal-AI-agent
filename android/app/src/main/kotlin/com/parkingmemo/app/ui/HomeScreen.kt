package com.parkingmemo.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.parkingmemo.app.ParkingApp
import com.parkingmemo.app.TrackerService
import com.parkingmemo.app.TrackerState
import com.parkingmemo.engine.ParkingRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One clear next action per state (design doc 6: one thing at a time).
 * No record -> how to start; driving -> nothing to do; recording -> "I've arrived";
 * record saved -> "find my car".
 */
@Composable
fun HomeScreen(onStartGuide: () -> Unit, onOpenSetup: () -> Unit, onOpenSensors: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ParkingApp
    val status by TrackerState.status.collectAsState()
    val version by TrackerState.recordVersion.collectAsState()
    val message by TrackerState.message.collectAsState()
    val walkInfo by TrackerState.walkInfo.collectAsState()
    val record = remember(version) { app.store.loadRecord() }
    var showPicker by remember { mutableStateOf(false) }
    var soundOn by remember { mutableStateOf(app.prefs.soundOn) }
    val manualPark = { TrackerService.send(ctx, TrackerService.ACTION_MANUAL_PARK) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("파킹메모", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(app.prefs.carName?.let { "내 차: $it" } ?: "차가 선택되지 않았어요", color = Muted, fontSize = 14.sp)

        when {
            status == TrackerState.Status.WALK_OUT -> RecordingCard(
                walkInfo = walkInfo,
                onDone = { TrackerService.send(ctx, TrackerService.ACTION_END_WALK) },
            )
            status == TrackerState.Status.WAITING_WALK -> InfoCard(
                title = "차에서 내렸나요?",
                body = "걷기 시작하면 자동으로 경로 기록을 시작해요. 아직 차 안이면 그대로 두세요.",
            )
            status == TrackerState.Status.DRIVING -> InfoCard(
                title = "운전 중이에요",
                body = "주차하고 내려서 걸어가면 자동으로 기록해요. 아무것도 누르지 않아도 돼요.",
            )
            record != null -> {
                RecordCard(record, onEditFloor = { showPicker = true })
                Button(
                    onClick = onStartGuide,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                ) {
                    Text("차 찾기 시작", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
                Text("잠금화면의 [차 찾기] 카드로도 바로 시작할 수 있어요", color = Muted, fontSize = 13.sp)
            }
            else -> FirstRunCard(onRecordNow = manualPark, onOpenSensors = onOpenSensors)
        }

        message?.let {
            Text(
                it,
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardBg)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        HorizontalDivider(color = LineC)
        Text("테스트 도구", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (record != null && status == TrackerState.Status.IDLE) {
            OutlinedButton(onClick = manualPark, modifier = Modifier.fillMaxWidth()) {
                Text("새로 주차했어요 · 지금 기록 시작")
            }
        }
        OutlinedButton(onClick = onOpenSensors, modifier = Modifier.fillMaxWidth()) {
            Text("센서 확인 (기압·걸음·방향·GPS)")
        }
        OutlinedButton(onClick = { shareLogs(ctx, app) }, modifier = Modifier.fillMaxWidth()) {
            Text("기록 파일 내보내기")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("안내할 때 비프음", color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = soundOn, onCheckedChange = {
                soundOn = it
                app.prefs.soundOn = it
            })
        }
        TextButton(onClick = onOpenSetup) { Text("설정 다시 하기") }
        Text("버전 ${appVersion(ctx)}", color = Muted, fontSize = 12.sp)
    }

    if (showPicker) {
        FloorPicker(
            current = record?.displayLabel,
            onPick = {
                app.store.setUserLabel(it)
                TrackerState.recordVersion.value = TrackerState.recordVersion.value + 1
                TrackerState.message.value = "${it}(으)로 고쳤어요. 이 주차장은 다음부터 반영돼요"
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** Highlighted card: the one thing to do now. */
@Composable
private fun ActionCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(2.dp, Accent, RoundedCornerShape(16.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun FirstRunCard(onRecordNow: () -> Unit, onOpenSensors: () -> Unit) {
    ActionCard {
        Text("다음 할 일", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        StepLine(1, "센서 확인 (선택)", "엘리베이터를 타면서 높이가 바뀌는지 봐요")
        StepLine(2, "평소처럼 운전하고 주차하기", "차 블루투스가 끊기고 걷기 시작하면 자동으로 기록돼요")
        StepLine(3, "목적지까지 걸어가기", "도착해서 [도착했어요]를 누르거나, 몇 분 지나면 저장돼요")
        Spacer(Modifier.height(2.dp))
        Text("지금 바로 테스트해 보려면", color = Muted, fontSize = 13.sp)
        Button(
            onClick = onRecordNow,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("지금 차 옆이에요 · 바로 기록 시작", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onOpenSensors, modifier = Modifier.fillMaxWidth()) {
            Text("센서부터 확인하기")
        }
    }
}

@Composable
private fun RecordingCard(walkInfo: String, onDone: () -> Unit) {
    ActionCard {
        Text("하차 경로를 기록하고 있어요", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        if (walkInfo.isNotEmpty()) Text(walkInfo, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "엘리베이터나 계단으로 목적지(집·사무실)까지 평소처럼 가세요. 폰은 주머니에 넣어도 돼요.",
            color = Ink,
            fontSize = 15.sp,
        )
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
        ) {
            Text("도착했어요 · 기록 완료", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "안 눌러도 도착 후 5분간 가만히 있거나, 밖으로 나가 GPS가 잡히면 자동으로 저장돼요.",
            color = Muted,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    ActionCard {
        Text(title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(body, color = Ink, fontSize = 15.sp)
    }
}

@Composable
private fun StepLine(n: Int, title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", color = OnAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RecordCard(r: ParkingRecord, onEditFloor: () -> Unit) {
    val time = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(r.parkedAtWallMs))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FloorBadge(r.displayLabel, 76.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(time, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                r.floor.dhGroundM?.let {
                    Text("지상보다 %.1f m %s".format(abs(it), if (it < 0) "아래" else "위"), color = Muted, fontSize = 14.sp)
                }
                r.dhEndM?.let {
                    Text("도착한 곳(집 등)보다 %.1f m %s".format(abs(it), if (it > 0) "아래" else "위"), color = Muted, fontSize = 14.sp)
                }
                r.carInAnchorFrame?.let {
                    Text("엘리베이터·계단에서 약 ${it.norm().roundToInt()} m", color = Muted, fontSize = 14.sp)
                }
                if (r.carGeo != null && r.carInAnchorFrame == null) {
                    Text("지상 주차 · GPS로 안내해요", color = Muted, fontSize = 14.sp)
                }
            }
        }
        val conf = (r.floor.confidence * 100).roundToInt()
        Text(
            if (r.userLabel != null) "직접 고친 층이에요" else "층 신뢰도 $conf% · 근거: ${r.floor.basis}",
            color = Muted,
            fontSize = 12.sp,
        )
        Text(
            "층이 틀렸나요? 눌러서 고치기",
            color = Accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onEditFloor),
        )
    }
}

@Composable
fun FloorPicker(current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val labels = listOf("B6", "B5", "B4", "B3", "B2", "B1", "1F", "2F", "3F", "4F", "5F")
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        title = { Text("주차한 층을 골라주세요") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                labels.forEach { l ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(l) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FloorBadge(l, 38.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(if (l == current) "$l (지금 기록)" else l, fontSize = 16.sp)
                    }
                }
            }
        },
    )
}

private fun appVersion(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
} catch (e: Exception) {
    "?"
}

private fun shareLogs(ctx: Context, app: ParkingApp) {
    try {
        val zip = app.store.exportZip()
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "기록 파일 보내기"))
    } catch (e: Exception) {
        TrackerState.message.value = "내보내기에 실패했어요: ${e.message}"
    }
}
