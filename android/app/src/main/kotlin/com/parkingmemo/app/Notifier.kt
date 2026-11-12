package com.parkingmemo.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.parkingmemo.app.ui.MainActivity
import com.parkingmemo.engine.ParkingRecord
import kotlin.math.abs
import kotlin.math.roundToInt

object Notifier {
    private const val CH_TRACK = "track"
    private const val CH_EVENTS = "events"
    const val ONGOING_ID = 1
    private const val CH_PARKED = "parked"
    private const val SAVED_ID = 2
    private const val TAP_ID = 3
    private const val ASK_ID = 4

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TRACK, "기록 중 표시", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_EVENTS, "주차 알림", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_PARKED, "주차 위치 (잠금화면)", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openApp(ctx: Context, action: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (action != null) i.putExtra(MainActivity.EXTRA_ACTION, action)
        return PendingIntent.getActivity(
            ctx, action?.hashCode() ?: 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** [endWalk]: show an "I've arrived" button while the walk-out is being recorded. */
    fun ongoing(ctx: Context, text: String, endWalk: Boolean = false): Notification {
        val b = NotificationCompat.Builder(ctx, CH_TRACK)
            .setSmallIcon(R.drawable.ic_stat_parking)
            .setContentTitle("파킹메모")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (endWalk) b.addAction(0, "도착했어요 · 기록 완료", openApp(ctx, TrackerService.ACTION_END_WALK))
        return b.build()
    }

    @SuppressLint("MissingPermission")
    fun updateOngoing(ctx: Context, text: String, endWalk: Boolean = false) {
        if (canPost(ctx)) NotificationManagerCompat.from(ctx).notify(ONGOING_ID, ongoing(ctx, text, endWalk))
    }

    /**
     * Stays in the notification shade and on the lock screen after parking, with a
     * one-tap "find my car" button (the design's lock-screen card).
     */
    @SuppressLint("MissingPermission")
    fun parked(ctx: Context, r: ParkingRecord) {
        if (!canPost(ctx)) return
        val label = r.displayLabel
        val title = if (label != null) "${label}에 주차했어요" else "주차 위치를 기록했어요"
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(r.parkedAtWallMs))
        val body = buildString {
            append(time)
            r.floor.dhGroundM?.let { append(" · 지상보다 %.1f m %s".format(abs(it), if (it < 0) "아래" else "위")) }
            r.carInAnchorFrame?.let { append(" · 엘리베이터에서 약 ${it.norm().roundToInt()} m") }
        }
        val find = openApp(ctx, TrackerService.ACTION_START_GUIDE)
        val n = NotificationCompat.Builder(ctx, CH_PARKED)
            .setSmallIcon(R.drawable.ic_stat_parking)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "차 찾기", find)
            .build()
        NotificationManagerCompat.from(ctx).notify(SAVED_ID, n)
    }

    /**
     * "Did you park on B4? [Yes] [B5] [B3]" right after the walk-out starts, while
     * the floor signs are still in view. Buttons answer without opening the app.
     */
    @SuppressLint("MissingPermission")
    fun askParkedFloor(ctx: Context, guess: String?) {
        if (!canPost(ctx)) return
        val b = NotificationCompat.Builder(ctx, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_parking)
            .setContentTitle(if (guess != null) "${guess}에 주차했나요?" else "몇 층에 주차했나요?")
            .setContentText("기둥이나 벽의 층 표시를 보고 눌러주세요")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
        if (guess != null) {
            b.addAction(0, "맞아요", confirmIntent(ctx, guess))
            com.parkingmemo.engine.SiteBook.shiftLabel(guess, -1)?.let { b.addAction(0, it, confirmIntent(ctx, it)) }
            com.parkingmemo.engine.SiteBook.shiftLabel(guess, 1)?.let { b.addAction(0, it, confirmIntent(ctx, it)) }
        }
        NotificationManagerCompat.from(ctx).notify(ASK_ID, b.build())
    }

    fun cancelAskParkedFloor(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(ASK_ID)
    }

    private fun confirmIntent(ctx: Context, label: String): PendingIntent {
        val i = Intent(ctx, TrackerService::class.java)
            .setAction(TrackerService.ACTION_CONFIRM_FLOOR)
            .putExtra(TrackerService.EXTRA_LABEL, label)
        return PendingIntent.getForegroundService(
            ctx, ("confirm" + label).hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun cancelParked(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(SAVED_ID)
    }

    /** Android refused to start the service from the background: ask for one tap instead. */
    @SuppressLint("MissingPermission")
    fun needsTap(ctx: Context, action: String) {
        if (!canPost(ctx)) return
        val text = when (action) {
            TrackerService.ACTION_CAR_DISCONNECTED -> "주차를 기록하려면 탭하세요. 배터리 최적화를 끄면 자동으로 돼요"
            TrackerService.ACTION_CAR_CONNECTED -> "운전 기록을 시작하려면 탭하세요. 배터리 최적화를 끄면 자동으로 돼요"
            else -> "탭해서 계속하세요"
        }
        val n = NotificationCompat.Builder(ctx, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_parking)
            .setContentTitle("파킹메모")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, action))
            .build()
        NotificationManagerCompat.from(ctx).notify(TAP_ID, n)
    }

    private fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
