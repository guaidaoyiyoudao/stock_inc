package com.stock.dividend.data.notification

import android.Manifest
import android.annotation.SuppressLint
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
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_ABOVE
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_BELOW
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

    @SuppressLint("MissingPermission")
    override suspend fun sendDividendYieldAlert(
        stockCode: String,
        stockName: String,
        yieldPercent: Double,
        thresholdPercent: Double
    ) {
        sendNotificationRuleAlert(
            stockCode = stockCode,
            stockName = stockName,
            ruleType = NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD,
            metricValue = yieldPercent,
            thresholdValue = thresholdPercent
        )
    }

    @SuppressLint("MissingPermission")
    override suspend fun sendNotificationRuleAlert(
        stockCode: String,
        stockName: String,
        ruleType: String,
        metricValue: Double,
        thresholdValue: Double
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
        val (title, body) = notificationText(
            stockName = stockName,
            ruleType = ruleType,
            metricValue = metricValue,
            thresholdValue = thresholdValue
        )
        val notification = NotificationCompat.Builder(context, DIVIDEND_ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(stockCode.hashCode(), notification)
        } catch (_: SecurityException) {
            return
        }
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

    private fun notificationText(
        stockName: String,
        ruleType: String,
        metricValue: Double,
        thresholdValue: Double
    ): Pair<String, String> {
        return when (ruleType) {
            NOTIFICATION_RULE_TYPE_PRICE_ABOVE -> "股价达到目标" to
                "%s 当前价格 %.2f 已达到 %.2f".format(Locale.US, stockName, metricValue, thresholdValue)
            NOTIFICATION_RULE_TYPE_PRICE_BELOW -> "股价跌破目标" to
                "%s 当前价格 %.2f 已低于 %.2f".format(Locale.US, stockName, metricValue, thresholdValue)
            NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD -> "股息率跌破目标" to
                "%s 当前股息率 %.2f%% 已低于 %.2f%%".format(Locale.US, stockName, metricValue, thresholdValue)
            else -> "股息率达到目标" to
                "%s 当前股息率 %.2f%% 已达到 %.2f%% 阈值".format(Locale.US, stockName, metricValue, thresholdValue)
        }
    }
}
