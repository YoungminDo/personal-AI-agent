package com.parkingmemo.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.parkingmemo.engine.LogFormat
import java.io.BufferedWriter
import java.io.File

/** Vibration (always) and an optional beep for each guidance pulse. */
class Feedback(ctx: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Vibrator::class.java)
    }
    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    } catch (e: RuntimeException) {
        null
    }

    fun pulse(sound: Boolean, strong: Boolean = false) {
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(if (strong) 300 else 45, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
        if (sound) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, if (strong) 200 else 70)
    }

    fun release() {
        tone?.release()
    }
}

/** Raw sensor log in the format [com.parkingmemo.engine.LogReplay] reads. */
class RawLog(file: File) {
    private val w: BufferedWriter = file.bufferedWriter()
    private var lastFlush = SystemClock.elapsedRealtime()

    init {
        w.write("# parking-memo raw log v1 wall=${System.currentTimeMillis()} mono=${SystemClock.elapsedRealtime()} " +
            "model=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}\n")
    }

    fun line(s: String) {
        try {
            w.write(s)
            w.write("\n")
            val n = SystemClock.elapsedRealtime()
            if (n - lastFlush > 2_000) {
                w.flush()
                lastFlush = n
            }
        } catch (_: Exception) {
        }
    }

    fun event(tMs: Long, name: String, arg: String? = null) = line(LogFormat.event(tMs, name, arg))

    fun close() {
        try {
            w.flush()
            w.close()
        } catch (_: Exception) {
        }
    }
}
