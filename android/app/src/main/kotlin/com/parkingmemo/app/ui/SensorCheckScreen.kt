package com.parkingmemo.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
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
import com.parkingmemo.app.TrackerService
import com.parkingmemo.engine.AccelStepDetector
import com.parkingmemo.engine.Baro
import com.parkingmemo.engine.Heading
import com.parkingmemo.engine.PressureSmoother

/**
 * Live sensor readout for checking a phone before a garage test: ride a lift and
 * watch the height change, walk and watch the steps, turn and watch the heading.
 */
@Composable
fun SensorCheckScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler { onBack() }

    var hPa by remember { mutableDoubleStateOf(Double.NaN) }
    var zeroHpa by remember { mutableDoubleStateOf(Double.NaN) }
    var steps by remember { mutableIntStateOf(0) }
    var heading by remember { mutableDoubleStateOf(Double.NaN) }
    var gps by remember { mutableStateOf("GPS 대기 중") }
    val usingAccel = remember { TrackerService.needsAccelSteps(ctx) }

    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val lm = ctx.getSystemService(LocationManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        val smoother = PressureSmoother()
        val accel = if (usingAccel) AccelStepDetector() else null
        val rot = FloatArray(9)
        var sats = -1
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val t = e.timestamp / 1_000_000L
                when (e.sensor.type) {
                    Sensor.TYPE_PRESSURE -> {
                        val p = smoother.push(e.values[0].toDouble())
                        hPa = p
                        if (zeroHpa.isNaN()) zeroHpa = p
                    }
                    Sensor.TYPE_STEP_DETECTOR -> steps++
                    Sensor.TYPE_ACCELEROMETER -> {
                        if (accel?.onAccel(t, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble()) != null) steps++
                    }
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rot, e.values)
                        Heading.forwardBearing(DoubleArray(9) { rot[it].toDouble() })?.let { heading = it }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(listener, it, 100_000) }
        if (usingAccel) {
            sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(listener, it, 20_000) }
        } else {
            sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST) }
        }
        sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(listener, it, 50_000) }

        val loc = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                gps = "정확도 %.0f m · 위성 %s".format(location.accuracy, if (sats >= 0) "$sats" else "?")
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        val gnss = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var n = 0
                for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) n++
                sats = n
            }
        }
        val hasLoc = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (hasLoc && lm != null) startGps(lm, loc, gnss, handler) else gps = "위치 권한 없음"

        onDispose {
            sm?.unregisterListener(listener)
            try {
                lm?.removeUpdates(loc)
                lm?.unregisterGnssStatusCallback(gnss)
            } catch (_: Exception) {
            }
        }
    }

    val dh = if (hPa.isNaN() || zeroHpa.isNaN()) Double.NaN else Baro.dhM(zeroHpa, hPa)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("센서 확인", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("엘리베이터를 타면 높이가, 걸으면 걸음 수가 바뀌어야 해요.", color = Muted, fontSize = 14.sp)

        Reading("높이 변화 (0점 기준)", if (dh.isNaN()) "기압계 없음" else "%+.1f m · 약 %+.1f개 층".format(dh, dh / 3.0))
        Reading("기압", if (hPa.isNaN()) "-" else "%.2f hPa".format(hPa))
        Reading("걸음", "$steps 걸음" + if (usingAccel) " (가속도 센서로 계산)" else "")
        Reading("폰이 향한 방향", if (heading.isNaN()) "-" else "%.0f° %s".format(heading, compass(heading)))
        Reading("GPS", gps)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { zeroHpa = hPa; steps = 0 }) { Text("0점 맞추기") }
            OutlinedButton(onClick = onBack) { Text("돌아가기") }
        }
    }
}

@SuppressLint("MissingPermission")
private fun startGps(lm: LocationManager, loc: LocationListener, gnss: GnssStatus.Callback, handler: Handler) {
    try {
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, loc, Looper.getMainLooper())
        lm.registerGnssStatusCallback(gnss, handler)
    } catch (_: Exception) {
    }
}

private fun compass(deg: Double): String {
    val names = listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
    return names[(((deg + 22.5) % 360) / 45).toInt().coerceIn(0, 7)]
}

@Composable
private fun Reading(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
