package com.stock.dividend.data.notification

import android.Manifest
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
import com.stock.dividend.MainActivity
import com.stock.dividend.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

private const val DIVIDEND_ALERT_CHANNEL_ID = "dividend_alerts"

@Singleton
class AndroidDividendAlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) : DividendAlertNotifier {

    override suspend fun canNotify(): Boolean {
        createChannel()
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        return permissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    override suspend fun sendDividendYieldAlert(
        stockCode: String,
        stockName: String,
        yieldPercent: Double,
        thresholdPercent: Double
    ) {
        if (!canNotify()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            stockCode.hashCode().absoluteValue,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, DIVIDEND_ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("股息率达到目标")
            .setContentText(
                "%s 当前股息率 %.2f%% 已达到 %.2f%% 阈值".format(
                    Locale.US,
                    stockName,
                    yieldPercent,
                    thresholdPercent
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(stockCode.hashCode(), notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            DIVIDEND_ALERT_CHANNEL_ID,
            "股息率提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }
}
