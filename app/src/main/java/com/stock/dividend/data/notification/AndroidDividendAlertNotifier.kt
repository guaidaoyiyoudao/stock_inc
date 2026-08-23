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
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_ABOVE
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_BELOW
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val DIVIDEND_ALERT_CHANNEL_ID = NotificationChannels.LEGACY_DIVIDEND_ALERTS

/** deep link：通知点击跳转个股详情用的 Intent extra key */
const val EXTRA_STOCK_CODE = "extra_stock_code"

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
        thresholdValue: Double,
        dedupKey: String?
    ) {
        if (!canNotify()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_STOCK_CODE, stockCode)   // deep link：点击跳个股详情
        }
        // 通知 id：默认按股票聚合（同股新通知替换旧通知）；带 dedupKey 时按来源独立
        // （同股多套网格计划各自成条，互不覆盖）
        val notifyId = if (dedupKey == null) {
            stockCode.hashCode()
        } else {
            (stockCode + dedupKey).hashCode()
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifyId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val (title, body) = notificationText(
            stockName = stockName,
            ruleType = ruleType,
            metricValue = metricValue,
            thresholdValue = thresholdValue
        )
        val notification = NotificationCompat.Builder(context, channelFor(ruleType))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // 锁屏可见
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notifyId, notification)
        } catch (_: SecurityException) {
            return
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun sendStrategySellAlert(signal: StrategySellSignal) {
        if (!canNotify()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_STOCK_CODE, signal.stockCode)   // deep link：点击跳个股详情
        }
        // 同股多条策略各自成条（planId 维度去重），互不覆盖
        val notifyId = (signal.stockCode + "strategy-" + signal.planId).hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifyId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val isAll = signal.tier == STRATEGY_SELL_TIER_ALL
        val title = if (isAll) "策略到达清仓信号" else "策略到达减仓信号"
        val action = if (isAll) {
            "全部卖出"
        } else {
            if (signal.sellShares > 0) "卖出约 ${signal.sellShares} 股" else "部分减仓"
        }
        val body = "%s（%s）：%s，按策略 %s".format(
            Locale.US, signal.stockName, signal.strategyTypeName, signal.headline, action
        )
        val notification = NotificationCompat.Builder(context, channelFor(STRATEGY_SELL_ALERT))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // 锁屏可见
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notifyId, notification)
        } catch (_: SecurityException) {
            return
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationChannels.CHANNEL_NAMES.forEach { (id, name) ->
            val importance = if (id == NotificationChannels.DIVIDEND_PAYOUTS) {
                NotificationManager.IMPORTANCE_HIGH
            } else {
                NotificationManager.IMPORTANCE_DEFAULT
            }
            manager.createNotificationChannel(
                NotificationChannel(id, name, importance)
            )
        }
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
            NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER -> "股价触及周BOLL上轨" to
                "%s 当前价格 %.2f 已达周线布林带上轨 %.2f".format(Locale.US, stockName, metricValue, thresholdValue)
            NOTIFICATION_RULE_TYPE_PRICE_BELOW -> "股价跌破目标" to
                "%s 当前价格 %.2f 已低于 %.2f".format(Locale.US, stockName, metricValue, thresholdValue)
            NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD -> "股息率跌破目标" to
                "%s 当前股息率 %.2f%% 已低于 %.2f%%".format(Locale.US, stockName, metricValue, thresholdValue)
            GRID_NEXT_LEVEL_ALERT -> "网格到达买入档" to
                "%s 现价 %.2f 已到买入档 %.2f，可按网格计划执行".format(Locale.US, stockName, metricValue, thresholdValue)
            GRID_SELL_LEVEL_ALERT -> "网格到达卖出锚" to
                "%s 现价 %.2f 已到波段卖出锚 %.2f，可减仓波段部分（底仓不动）".format(
                    Locale.US, stockName, metricValue, thresholdValue
                )
            else -> "股息率达到目标" to
                "%s 当前股息率 %.2f%% 已达到 %.2f%% 阈值".format(Locale.US, stockName, metricValue, thresholdValue)
        }
    }
}
