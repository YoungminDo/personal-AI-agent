package com.parkingmemo.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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

@Composable
fun HomeScreen(onStartGuide: () -> Unit, onOpenSetup: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ParkingApp
    val status by TrackerState.status.collectAsState()
    val version by TrackerState.recordVersion.collectAsState()
    val message by TrackerState.message.collectAsState()
    val walkInfo by TrackerState.walkInfo.collectAsState()
    val record = remember(version) { app.store.loadRecord() }
    var showPicker by remember { mutableStateOf(false) }
    var soundOn by remember { mutableStateOf(app.prefs.soundOn) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("파킹메모", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(app.prefs.carName?.let { "내 차: $it" } ?: "차가 선택되지 않았어요", color = Muted, fontSize = 14.sp)

        StatusLine(status, walkInfo)

        if (record != null) {
            RecordCard(record, onEditFloor = { showPicker = true })
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardBg)
                    .padding(18.dp),
            ) {
                Text("아직 주차 기록이 없어요", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("차 블루투스가 끊기고 걷기 시작하면 자동으로 기록해요", color = Muted, fontSize = 14.sp)
            }
        }

        Button(
            onClick = onStartGuide,
            enabled = record != null && status != TrackerState.Status.GUIDING,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
        ) {
            Text("차 찾기 시작", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }

        message?.let { Text(it, color = Accent, fontSize = 14.sp) }

        HorizontalDivider(color = LineC)
        Text("테스트 도구", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        OutlinedButton(
            onClick = { TrackerService.send(ctx, TrackerService.ACTION_MANUAL_PARK) },
            enabled = status == TrackerState.Status.IDLE || status == TrackerState.Status.DRIVING,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("지금 주차한 것으로 기록 시작") }
        if (status == TrackerState.Status.WALK_OUT) {
            OutlinedButton(
                onClick = { TrackerService.send(ctx, TrackerService.ACTION_END_WALK) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("하차 기록 끝내기 (도착했어요)") }
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

@Composable
private fun StatusLine(status: TrackerState.Status, walkInfo: String) {
    val text = when (status) {
        TrackerState.Status.IDLE -> "대기 중 · 차에 타면 자동으로 시작해요"
        TrackerState.Status.DRIVING -> "운전 중 · 주차하면 자동으로 기록해요"
        TrackerState.Status.WAITING_WALK -> "차에서 내리기를 기다리는 중"
        TrackerState.Status.WALK_OUT -> "하차 경로 기록 중" + if (walkInfo.isNotEmpty()) " · $walkInfo" else ""
        TrackerState.Status.GUIDING -> "차 찾기 안내 중"
    }
    val active = status != TrackerState.Status.IDLE
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) Accent else CardBg)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, color = if (active) OnAccent else Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
