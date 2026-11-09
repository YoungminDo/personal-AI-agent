package com.parkingmemo.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.parkingmemo.engine.GnssSample
import com.parkingmemo.engine.GuideMode
import com.parkingmemo.engine.GuideState
import com.parkingmemo.engine.Guidance
import com.parkingmemo.engine.Heading
import com.parkingmemo.engine.LogFormat
import com.parkingmemo.engine.ParkingPipeline
import com.parkingmemo.engine.ParkingRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Foreground service that owns the sensors and runs the engine.
 *
 * Driving: low-rate barometer/GNSS into the rewind buffer.
 * Car disconnected: high-rate sensors until the walk-out is recorded.
 * Guiding: high-rate sensors, guidance state for the UI, vibration pulses.
 * Every session also writes a raw log for offline replay and tuning.
 */
class TrackerService : Service(), SensorEventListener {

    companion object {
        const val ACTION_CAR_CONNECTED = "com.parkingmemo.action.CAR_CONNECTED"
        const val ACTION_CAR_DISCONNECTED = "com.parkingmemo.action.CAR_DISCONNECTED"
        const val ACTION_MANUAL_PARK = "com.parkingmemo.action.MANUAL_PARK"
        const val ACTION_END_WALK = "com.parkingmemo.action.END_WALK"
        const val ACTION_START_GUIDE = "com.parkingmemo.action.START_GUIDE"
        const val ACTION_STOP_GUIDE = "com.parkingmemo.action.STOP_GUIDE"
        const val ACTION_FOUND = "com.parkingmemo.action.FOUND"

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, TrackerService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // Android 12+ refuses background starts unless battery optimisation is off.
                Notifier.needsTap(ctx, action)
            }
        }
    }

    private enum class Rate { OFF, DRIVE, ACTIVE }

    private data class Pending(val t: Long, val kind: Int, val v: Double)

    private val app get() = application as ParkingApp
    private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private lateinit var feedback: Feedback
    private val handler = Handler(Looper.getMainLooper())

    private var pipeline: ParkingPipeline? = null

    private var guiding = false
    private var guideRec: ParkingRecord? = null
    private var guidance: Guidance? = null
    private var guideStartMs = 0L
    private var guideSawGoodGnss = false
    private val pending = mutableListOf<Pending>()
    private var lastGuideMode: GuideMode? = null
    private var nextPulseAtMs = 0L

    private var rate = Rate.OFF
    private var gnssOn = false
    private var satsUsed = -1
    private var satsAtMs = 0L
    private var log: RawLog? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val rotM = FloatArray(9)
    private var lastHeadingMs = 0L
    private var lastRotLogMs = 0L
    private var lastTickMs = 0L
    private var loopRunning = false

    private fun now() = SystemClock.elapsedRealtime()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sm = getSystemService(SensorManager::class.java)
        lm = getSystemService(LocationManager::class.java)
        feedback = Feedback(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground("파킹메모 실행 중")
        when (intent?.action) {
            ACTION_CAR_CONNECTED -> carConnected()
            ACTION_CAR_DISCONNECTED -> carDisconnected()
            ACTION_MANUAL_PARK -> manualPark()
            ACTION_END_WALK -> endWalk()
            ACTION_START_GUIDE -> startGuide()
            ACTION_STOP_GUIDE -> stopGuide(found = false)
            ACTION_FOUND -> stopGuide(found = true)
            else -> if (pipeline == null && !guiding) shutdown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        setRate(Rate.OFF)
        releaseWake()
        closeLog()
        feedback.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- parking

    private fun newPipeline(): ParkingPipeline =
        ParkingPipeline(app.store.loadSites()).also { pipeline = it }

    private fun carConnected() {
        val t = now()
        if (guiding) stopGuide(found = true, auto = true)
        val p = pipeline ?: newPipeline()
        if (log == null) openLog("drive")
        log?.event(t, "CAR_CONNECTED")
        if (p.carConnected(t) is ParkingPipeline.Output.Cancelled) {
            TrackerState.message.value = "다시 운전을 시작해서 하차 기록을 취소했어요"
        }
        setRate(Rate.DRIVE)
        // Keep the CPU awake while driving so the rewind buffer has the ramp down (B08).
        acquireWake()
        TrackerState.status.value = TrackerState.Status.DRIVING
        Notifier.updateOngoing(this, "운전 중 · 주차하면 자동으로 기록해요")
        startLoop()
    }

    private fun carDisconnected() {
        val t = now()
        val p = pipeline ?: newPipeline()
        if (log == null) openLog("park")
        log?.event(t, "CAR_DISCONNECTED")
        p.carDisconnected(t)
        setRate(Rate.ACTIVE)
        acquireWake()
        TrackerState.status.value = TrackerState.Status.WAITING_WALK
        Notifier.updateOngoing(this, "차에서 내리면 경로를 기록해요")
        startLoop()
    }

    private fun manualPark() {
        val t = now()
        val p = pipeline ?: newPipeline()
        if (log == null) openLog("park")
        log?.event(t, "MANUAL_PARK")
        setRate(Rate.ACTIVE)
        acquireWake()
        handle(p.manualPark(t))
        startLoop()
    }

    private fun endWalk() {
        val t = now()
        log?.event(t, "END_WALK")
        pipeline?.endWalkManually(t)?.let { handle(it) }
    }

    private fun handle(out: ParkingPipeline.Output) {
        when (out) {
            is ParkingPipeline.Output.WalkStarted -> {
                log?.event(out.originMs, "WALK_STARTED")
                TrackerState.status.value = TrackerState.Status.WALK_OUT
                Notifier.updateOngoing(this, "하차 경로를 기록하고 있어요")
            }
            is ParkingPipeline.Output.Saved -> {
                val r = out.record
                app.store.saveRecord(r)
                log?.event(now(), "SAVED", r.displayLabel ?: "?")
                closeLog()
                pipeline = null
                Notifier.saved(this, r)
                TrackerState.recordVersion.value = TrackerState.recordVersion.value + 1
                TrackerState.walkInfo.value = ""
                TrackerState.message.value = "주차 위치를 저장했어요 (${r.displayLabel ?: "층 미상"})"
                if (!guiding) shutdown()
            }
            is ParkingPipeline.Output.Cancelled -> {
                TrackerState.message.value = "하차 기록을 취소했어요"
            }
            is ParkingPipeline.Output.GaveUp -> {
                TrackerState.message.value = "하차를 감지하지 못해 대기를 멈췄어요"
                pipeline = null
                closeLog()
                if (!guiding) shutdown()
            }
        }
    }

    // --------------------------------------------------------------- guidance

    private fun startGuide() {
        val rec = app.store.loadRecord()
        if (rec == null) {
            TrackerState.message.value = "저장된 주차 기록이 없어요"
            if (pipeline == null) shutdown()
            return
        }
        guiding = true
        guideRec = rec
        guidance = null
        pending.clear()
        guideSawGoodGnss = false
        guideStartMs = now()
        lastGuideMode = null
        nextPulseAtMs = 0L
        if (log == null) openLog("guide")
        log?.event(guideStartMs, "GUIDE_START")
        setRate(Rate.ACTIVE)
        acquireWake()
        TrackerState.status.value = TrackerState.Status.GUIDING
        TrackerState.guide.value = GuideState(
            GuideMode.CALIBRATING, rec.displayLabel, null, null, null, 90.0,
            "기압을 재는 중이에요", null, "-", null, null,
        )
        Notifier.updateOngoing(this, "차 찾기 안내 중")
        startLoop()
    }

    /** After a few seconds, decide where the user is starting from (street or last destination). */
    private fun createGuidance() {
        val rec = guideRec ?: return
        val ref = when {
            guideSawGoodGnss && rec.floor.dhGroundM != null -> Guidance.StartRef.GROUND
            rec.dhEndM != null -> Guidance.StartRef.WALK_END
            else -> Guidance.StartRef.GROUND
        }
        val sites = app.store.loadSites()
        val g = Guidance(rec, ref, sites.floorHeight(rec.siteKey), sites.isLearned(rec.siteKey))
        for (e in pending) when (e.kind) {
            0 -> g.onPressure(e.t, e.v)
            1 -> g.onHeading(e.v)
            else -> g.onStep(e.t)
        }
        pending.clear()
        guidance = g
        log?.event(now(), "GUIDE_REF", ref.name)
        TrackerState.message.value = if (ref == Guidance.StartRef.GROUND) {
            "지상에서 출발한 것으로 계산해요"
        } else {
            "지난번 도착한 곳(집 등)에서 출발한 것으로 계산해요"
        }
    }

    private fun stopGuide(found: Boolean, auto: Boolean = false) {
        if (!guiding) {
            if (pipeline == null) shutdown()
            return
        }
        val t = now()
        val rec = guideRec
        val g = guidance
        if (found && rec != null) {
            val err = if (g?.isAnchored == true) g.distanceToCar() else null
            val site = rec.siteKey
            if (err != null && site != null) {
                val sites = app.store.loadSites()
                sites.addFind(site, err)
                app.store.saveSites(sites)
            }
            log?.event(t, "FOUND", err?.let { "%.1f".format(Locale.US, it) } ?: "")
            val how = if (auto) "차에 연결돼서 안내를 마쳤어요" else "찾았어요"
            TrackerState.message.value = if (err != null) "$how · 추정 위치와 약 ${err.roundToInt()} m 차이" else how
        }
        log?.event(t, "GUIDE_END")
        closeLog()
        guiding = false
        guidance = null
        guideRec = null
        pending.clear()
        TrackerState.guide.value = null
        if (pipeline == null) {
            shutdown()
        } else {
            setRate(Rate.DRIVE)
            TrackerState.status.value = TrackerState.Status.DRIVING
        }
    }

    // ------------------------------------------------------------------- loop

    private val loop = object : Runnable {
        override fun run() {
            onLoop()
            if (loopRunning) handler.postDelayed(this, 200)
        }
    }

    private fun startLoop() {
        if (loopRunning) return
        loopRunning = true
        handler.post(loop)
    }

    private fun onLoop() {
        val t = now()
        val p = pipeline
        if (p != null && t - lastTickMs >= 1_000) {
            lastTickMs = t
            p.tick(t)?.let { handle(it) }
            pipeline?.walkProgress?.let { w ->
                TrackerState.walkInfo.value = "걸음 ${w.stepsSoFar} · 수직 이동 ${w.segmentsSoFar.size}회"
            }
        }
        if (!guiding) return
        if (guidance == null && t - guideStartMs >= 4_000) createGuidance()
        val g = guidance ?: return
        val s = g.state(t)
        TrackerState.guide.value = s
        val prev = lastGuideMode
        if (prev != null && s.mode != prev && isMilestone(prev, s.mode)) {
            feedback.pulse(app.prefs.soundOn, strong = true)
            nextPulseAtMs = t + 600
        }
        lastGuideMode = s.mode
        val interval = s.beepIntervalS
        if (interval != null && t >= nextPulseAtMs) {
            feedback.pulse(app.prefs.soundOn)
            nextPulseAtMs = t + (interval * 1000).toLong()
        }
        if (t - guideStartMs > 20 * 60_000L) stopGuide(found = false)
    }

    private fun isMilestone(prev: GuideMode, next: GuideMode): Boolean {
        val onFloor = setOf(GuideMode.STEP, GuideMode.VECTOR)
        return next == GuideMode.ARRIVE ||
            (next in onFloor && prev !in onFloor && prev != GuideMode.ARRIVE) ||
            (next == GuideMode.FLOOR && (prev in onFloor || prev == GuideMode.SETTLING))
    }

    // ---------------------------------------------------------------- sensors

    override fun onSensorChanged(e: SensorEvent) {
        val t = e.timestamp / 1_000_000L
        when (e.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                val hPa = e.values[0].toDouble()
                log?.line(LogFormat.pressure(t, hPa))
                pipeline?.pressure(t, hPa)
                feedGuide(t, 0, hPa)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                log?.line(LogFormat.step(t))
                pipeline?.step(t)?.let { handle(it) }
                feedGuide(t, 2, 0.0)
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotM, e.values)
                val r = DoubleArray(9) { rotM[it].toDouble() }
                val deg = Heading.forwardBearing(r) ?: return
                if (t - lastHeadingMs >= 100) {
                    lastHeadingMs = t
                    log?.line(LogFormat.heading(t, deg))
                    pipeline?.heading(t, deg)
                }
                feedGuide(t, 1, deg)
                if (t - lastRotLogMs >= 200) {
                    lastRotLogMs = t
                    log?.line("R,$t," + rotM.joinToString(",") { "%.4f".format(Locale.US, it) })
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun feedGuide(t: Long, kind: Int, v: Double) {
        if (!guiding) return
        val g = guidance
        if (g == null) {
            pending += Pending(t, kind, v)
            return
        }
        when (kind) {
            0 -> g.onPressure(t, v)
            1 -> g.onHeading(v)
            else -> g.onStep(t)
        }
    }

    private fun has(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun setRate(r: Rate) {
        if (r == rate) return
        sm.unregisterListener(this)
        rate = r
        if (r == Rate.OFF) {
            stopGnss()
            return
        }
        val pressureUs = if (r == Rate.DRIVE) 200_000 else 100_000
        val rotationUs = if (r == Rate.DRIVE) 200_000 else 33_000
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(this, it, pressureUs, 0) }
        if (has(Manifest.permission.ACTIVITY_RECOGNITION)) {
            sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, 0)
            }
        }
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, rotationUs, 0) }
        startGnss()
    }

    // Explicit object: on Android 10 the other LocationListener methods are not default methods.
    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var n = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) n++
            satsUsed = n
            satsAtMs = now()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGnss() {
        if (gnssOn || !has(Manifest.permission.ACCESS_FINE_LOCATION)) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, locListener, Looper.getMainLooper())
            lm.registerGnssStatusCallback(gnssCallback, handler)
            gnssOn = true
        } catch (e: Exception) {
            TrackerState.message.value = "GPS를 켤 수 없어요: ${e.message}"
        }
    }

    private fun stopGnss() {
        if (!gnssOn) return
        try {
            lm.removeUpdates(locListener)
            lm.unregisterGnssStatusCallback(gnssCallback)
        } catch (_: Exception) {
        }
        gnssOn = false
    }

    private fun onLocation(loc: Location) {
        val t = loc.elapsedRealtimeNanos / 1_000_000L
        val sats = if (now() - satsAtMs < 5_000) satsUsed else -1
        val s = GnssSample(t, loc.latitude, loc.longitude, loc.accuracy.toDouble(), sats)
        log?.line(LogFormat.gnss(s))
        pipeline?.gnss(s)
        if (guiding && guidance == null && s.good) guideSawGoodGnss = true
    }

    // ----------------------------------------------------------------- plumbing

    private fun startInForeground(text: String) {
        var types = 0
        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= 34) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        val n = Notifier.ongoing(this, text)
        try {
            ServiceCompat.startForeground(this, Notifier.ONGOING_ID, n, types)
        } catch (e: Exception) {
            try {
                ServiceCompat.startForeground(this, Notifier.ONGOING_ID, n, 0)
            } catch (e2: Exception) {
                TrackerState.message.value = "백그라운드 실행이 막혔어요. 설정에서 권한을 확인해 주세요"
            }
        }
    }

    private fun openLog(kind: String) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        log = try {
            RawLog(File(app.store.logsDir, "$stamp-$kind.csv"))
        } catch (e: Exception) {
            null
        }
    }

    private fun closeLog() {
        log?.close()
        log = null
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "parkingmemo:track").apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 60_000L)
        }
    }

    private fun releaseWake() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun shutdown() {
        loopRunning = false
        handler.removeCallbacks(loop)
        setRate(Rate.OFF)
        releaseWake()
        closeLog()
        pipeline = null
        if (!guiding) TrackerState.status.value = TrackerState.Status.IDLE
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
