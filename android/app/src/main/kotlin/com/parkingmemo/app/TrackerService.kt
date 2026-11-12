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
import com.parkingmemo.engine.AccelStepDetector
import com.parkingmemo.engine.GnssSample
import com.parkingmemo.engine.GuideMode
import com.parkingmemo.engine.GuideSession
import com.parkingmemo.engine.GuideState
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
        const val ACTION_CALIBRATE = "com.parkingmemo.action.CALIBRATE"
        private const val EXTRA_CURRENT = "current"
        private const val EXTRA_CAR = "car"

        /** The user says which floor they are on and which floor the car is on. */
        fun calibrate(ctx: Context, current: String, car: String) {
            val i = Intent(ctx, TrackerService::class.java)
                .setAction(ACTION_CALIBRATE)
                .putExtra(EXTRA_CURRENT, current)
                .putExtra(EXTRA_CAR, car)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (_: Exception) {
            }
        }

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, TrackerService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // Android 12+ refuses background starts unless battery optimisation is off.
                Notifier.needsTap(ctx, action)
            }
        }

        /** True when steps must come from the raw accelerometer (no detector or no permission). */
        fun needsAccelSteps(ctx: Context): Boolean {
            val sm = ctx.getSystemService(SensorManager::class.java) ?: return true
            val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
            return !granted || sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) == null
        }
    }

    private enum class Rate { OFF, DRIVE, ACTIVE }

    private val app get() = application as ParkingApp
    private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private lateinit var feedback: Feedback
    private val handler = Handler(Looper.getMainLooper())

    private var pipeline: ParkingPipeline? = null

    private var guiding = false
    private var guideRec: ParkingRecord? = null
    private var session: GuideSession? = null
    private var guideStartMs = 0L
    private var lastSwitches = 0
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
    private var accelSteps: AccelStepDetector? = null

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
            ACTION_CALIBRATE -> calibrate(intent?.getStringExtra(EXTRA_CURRENT), intent?.getStringExtra(EXTRA_CAR))
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
        val p = pipeline ?: return
        val out = p.endWalkManually(t)
        if (out != null) handle(out)
        else if (p.isFinishing) TrackerState.message.value = "도착한 층 높이를 재는 중이에요. 몇 초만 그대로 있어 주세요"
    }

    private fun calibrate(current: String?, car: String?) {
        val s = session
        if (current == null || car == null || s == null) return
        val rec = guideRec
        if (rec != null && car != rec.displayLabel) {
            app.store.setUserLabel(car)
            TrackerState.recordVersion.value = TrackerState.recordVersion.value + 1
        }
        log?.event(now(), "CALIBRATE", "$current/$car")
        TrackerState.message.value = if (s.calibrate(current, car)) {
            "지금 $current, 차는 $car 기준으로 다시 안내해요"
        } else {
            "층 표시를 읽지 못했어요"
        }
    }

    private fun handle(out: ParkingPipeline.Output) {
        when (out) {
            is ParkingPipeline.Output.WalkStarted -> {
                log?.event(out.originMs, "WALK_STARTED")
                TrackerState.status.value = TrackerState.Status.WALK_OUT
                Notifier.updateOngoing(this, "하차 경로를 기록하고 있어요 · 도착하면 완료를 눌러 주세요", endWalk = true)
                feedback.walkStarted()
            }
            is ParkingPipeline.Output.Saved -> {
                val r = out.record
                app.store.saveRecord(r)
                log?.event(now(), "SAVED", r.displayLabel ?: "?")
                closeLog()
                pipeline = null
                Notifier.parked(this, r)
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
        if (guiding) return
        val rec = app.store.loadRecord()
        if (rec == null) {
            TrackerState.message.value = "저장된 주차 기록이 없어요"
            if (pipeline == null) shutdown()
            return
        }
        val sites = app.store.loadSites()
        guiding = true
        guideRec = rec
        session = GuideSession(rec, sites.floorHeight(rec.siteKey), sites.isLearned(rec.siteKey))
        lastSwitches = 0
        guideStartMs = now()
        lastGuideMode = null
        nextPulseAtMs = 0L
        if (log == null) openLog("guide")
        log?.event(guideStartMs, "GUIDE_START", rec.displayLabel ?: "?")
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

    private fun stopGuide(found: Boolean, auto: Boolean = false) {
        if (!guiding) {
            if (pipeline == null) shutdown()
            return
        }
        val t = now()
        val rec = guideRec
        val s = session
        if (found && rec != null) {
            val err = if (s?.isAnchored == true) s.distanceToCar() else null
            val site = rec.siteKey
            if (err != null && site != null) {
                val sites = app.store.loadSites()
                sites.addFind(site, err)
                app.store.saveSites(sites)
            }
            log?.event(t, "FOUND", err?.let { "%.1f".format(Locale.US, it) } ?: "")
            val how = if (auto) "차에 연결돼서 안내를 마쳤어요" else "찾았어요"
            TrackerState.message.value = if (err != null) "$how · 추정 위치와 약 ${err.roundToInt()} m 차이" else how
            Notifier.cancelParked(this)
        }
        log?.event(t, "GUIDE_END")
        closeLog()
        guiding = false
        session = null
        guideRec = null
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
        val s = session ?: return
        val st = s.state(t)
        TrackerState.guide.value = st
        if (s.switches != lastSwitches) {
            lastSwitches = s.switches
            log?.event(t, "GUIDE_REF", st.ref ?: "?")
        }
        val prev = lastGuideMode
        if (prev != null && st.mode != prev && isMilestone(prev, st.mode)) {
            feedback.pulse(app.prefs.soundOn, strong = true)
            nextPulseAtMs = t + 600
        }
        lastGuideMode = st.mode
        val interval = st.beepIntervalS
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
                session?.onPressure(t, hPa)
            }
            Sensor.TYPE_STEP_DETECTOR -> onStep(t)
            Sensor.TYPE_ACCELEROMETER -> {
                accelSteps?.onAccel(t, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
                    ?.let { onStep(it) }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotM, e.values)
                val r = DoubleArray(9) { rotM[it].toDouble() }
                val deg = Heading.forwardBearing(r) ?: return
                // Third row of the matrix = world "up" in device axes: tells hand vs pocket.
                val up = doubleArrayOf(r[6], r[7], r[8])
                if (t - lastHeadingMs >= 100) {
                    lastHeadingMs = t
                    log?.line(LogFormat.heading(t, deg, up))
                    pipeline?.heading(t, deg, up)
                }
                session?.onHeading(t, deg, up)
                if (t - lastRotLogMs >= 500) {
                    lastRotLogMs = t
                    log?.line("R,$t," + rotM.joinToString(",") { "%.4f".format(Locale.US, it) })
                }
            }
        }
    }

    private fun onStep(t: Long) {
        log?.line(LogFormat.step(t))
        pipeline?.step(t)?.let { handle(it) }
        session?.onStep(t)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

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
        if (needsAccelSteps(this)) {
            accelSteps = AccelStepDetector()
            log?.event(now(), "STEP_SOURCE", "accelerometer")
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, 20_000, 0) }
        } else {
            accelSteps = null
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
        session?.onGnss(s)
    }

    // ----------------------------------------------------------------- plumbing

    /** Tries the strongest service type first and falls back, so a missing permission never crashes. */
    private fun startInForeground(text: String) {
        val n = Notifier.ongoing(this, text)
        val candidates = mutableListOf<Int>()
        val loc = if (has(Manifest.permission.ACCESS_FINE_LOCATION)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        if (Build.VERSION.SDK_INT >= 34) {
            candidates += loc or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            candidates += ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            if (loc != 0) candidates += loc
            candidates += 0
        }
        for (types in candidates) {
            try {
                ServiceCompat.startForeground(this, Notifier.ONGOING_ID, n, types)
                return
            } catch (_: Exception) {
            }
        }
        TrackerState.message.value = "백그라운드 실행이 막혔어요. 설정에서 권한을 확인해 주세요"
    }

    private fun openLog(kind: String) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        log = try {
            RawLog(File(app.store.logsDir, "$stamp-$kind.csv"))
        } catch (e: Exception) {
            null
        }
        if (accelSteps != null) log?.event(now(), "STEP_SOURCE", "accelerometer")
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
