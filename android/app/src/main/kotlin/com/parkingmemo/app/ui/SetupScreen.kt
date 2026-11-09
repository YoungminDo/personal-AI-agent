package com.parkingmemo.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.content.ContextCompat
import com.parkingmemo.app.ParkingApp
import kotlinx.coroutines.delay

private data class BtDevice(val name: String, val address: String, val likelyCar: Boolean)

private fun granted(ctx: Context, perm: String) =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

private fun basicPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@SuppressLint("MissingPermission")
private fun bondedDevices(ctx: Context): List<BtDevice> {
    if (Build.VERSION.SDK_INT >= 31 && !granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)) return emptyList()
    val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    return try {
        adapter.bondedDevices.map { d ->
            val cls = d.bluetoothClass?.deviceClass
            val car = cls == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || cls == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
            BtDevice(d.name ?: d.address, d.address, car)
        }.sortedByDescending { it.likelyCar }
    } catch (e: SecurityException) {
        emptyList()
    }
}

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ParkingApp
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val perms = remember { basicPermissions() }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    var carAddress by remember { mutableStateOf(app.prefs.carAddress) }

    // Re-read system state every tick (permissions can change in Settings).
    val refresh = tick
    val basicOk = refresh >= 0 && perms.all { granted(ctx, it) }
    val bgOk = granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    val batteryOk = ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    val hasBaro = ctx.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
    val devices = remember(refresh / 5, basicOk) { bondedDevices(ctx) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("처음 설정", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("테스트 버전이라 권한을 직접 허용해 주세요. 한 번만 하면 돼요.", color = Muted, fontSize = 14.sp)

        SetupStep("1. 권한 허용", "위치 · 걸음 감지 · 블루투스 · 알림", basicOk) { permLauncher.launch(perms) }
        SetupStep("2. 위치 '항상 허용'", "앱을 열지 않아도 주차를 기록하려면 필요해요", bgOk, enabled = basicOk) {
            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        SetupStep("3. 배터리 최적화 제외", "운전 중이나 내린 직후에 앱이 꺼지지 않게 해요", batteryOk) {
            @SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            try {
                ctx.startActivity(i)
            } catch (e: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        Text("4. 내 차 블루투스 고르기", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (!basicOk) {
            Text("1번 권한을 먼저 허용해 주세요", color = Muted, fontSize = 14.sp)
        } else if (devices.isEmpty()) {
            Text("연결된 적 있는 블루투스 기기가 없어요. 차와 먼저 페어링해 주세요", color = Muted, fontSize = 14.sp)
        } else {
            devices.forEach { d ->
                val selected = d.address == carAddress
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardBg)
                        .border(2.dp, if (selected) Accent else LineC, RoundedCornerShape(12.dp))
                        .clickable {
                            app.prefs.carAddress = d.address
                            app.prefs.carName = d.name
                            carAddress = d.address
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (d.likelyCar) "차량 오디오로 보여요" else d.address, color = Muted, fontSize = 12.sp)
                    }
                    if (selected) Text("선택됨", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .padding(14.dp),
        ) {
            Text(
                if (hasBaro) "기압계 있음 · 층을 판별할 수 있어요" else "이 폰에는 기압계가 없어 층을 판별할 수 없어요",
                color = if (hasBaro) Ok else Danger,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onDone,
            enabled = basicOk && carAddress != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Text("시작하기", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        if (!(bgOk && batteryOk)) {
            Text(
                "2번과 3번을 건너뛰면 주차할 때 '탭해서 기록' 알림이 뜰 수 있어요",
                color = Muted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SetupStep(title: String, desc: String, done: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(10.dp))
        if (done) {
            Text("완료", color = Ok, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        } else {
            OutlinedButton(onClick = onClick, enabled = enabled) { Text("허용") }
        }
    }
}
