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
    private const val SAVED_ID = 2
    private const val TAP_ID = 3

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TRACK, "기록 중 표시", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_EVENTS, "주차 알림", NotificationManager.IMPORTANCE_DEFAULT))
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

    fun ongoing(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_TRACK)
            .setSmallIcon(R.drawable.ic_stat_parking)
            .setContentTitle("파킹메모")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    @SuppressLint("MissingPermission")
    fun updateOngoing(ctx: Context, text: String) {
        if (canPost(ctx)) NotificationManagerCompat.from(ctx).notify(ONGOING_ID, ongoing(ctx, text))
    }

    @SuppressLint("MissingPermission")
    fun saved(ctx: Context, r: ParkingRecord) {
        if (!canPost(ctx)) return
        val label = r.displayLabel
        val title = if (label != null) "${label}에 주차했어요" else "주차 위치를 기록했어요"
        val body = buildString {
            r.floor.dhGroundM?.let { append("지상보다 %.1f m %s".format(abs(it), if (it < 0) "아래" else "위")) }
            r.carInAnchorFrame?.let {
                if (isNotEmpty()) append(" · ")
                append("엘리베이터에서 약 ${it.norm().roundToInt()} m")
            }
            if (isNotEmpty()) append(" · ")
            append("탭해서 층 확인")
        }
        val n = NotificationCompat.Builder(ctx, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_parking)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(SAVED_ID, n)
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
