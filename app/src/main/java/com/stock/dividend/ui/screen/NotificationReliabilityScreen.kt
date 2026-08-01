package com.stock.dividend.ui.screen

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.stock.dividend.ui.component.AppButton
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardTone
import com.stock.dividend.data.notification.VivoPermissionIntents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationReliabilityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var batteryIgnored by remember { mutableStateOf(isBatteryOptimizationIgnored(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知可靠性") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "为保证股价/股息率提醒按时推送，请保持以下开关开启",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ① 通知权限（可检测状态）
            ReliabilityCard(
                title = "通知权限",
                status = if (notifGranted) "已开启" else "未开启",
                statusOk = notifGranted,
                actionText = "去开启",
                onAction = {
                    try {
                        context.startActivity(VivoPermissionIntents.appNotificationSettings(context.packageName))
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        })
                    }
                }
            )

            // ② 自启动（Vivo，无法检测状态）
            ReliabilityCard(
                title = "自启动",
                status = "无法自动检测，请确认已开启",
                statusOk = false,
                actionText = "去开启",
                onAction = {
                    try {
                        context.startActivity(VivoPermissionIntents.bgStartUp())
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(VivoPermissionIntents.appDetails(context.packageName))
                    }
                }
            )

            // ③ 电池优化白名单（可检测状态）
            ReliabilityCard(
                title = "允许后台运行（电池优化）",
                status = if (batteryIgnored) "已允许" else "未允许",
                statusOk = batteryIgnored,
                actionText = "允许后台运行",
                onAction = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        )
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            )
        }
    }
}

@Composable
private fun ReliabilityCard(
    title: String,
    status: String,
    statusOk: Boolean,
    actionText: String,
    onAction: () -> Unit,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        tone = AppCardTone.List,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            AppButton(onClick = onAction, modifier = Modifier.fillMaxWidth(), text = actionText)
        }
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        true
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
