package com.stock.dividend.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 设置页分组标题（按渲染顺序）。供 [SettingsScreen] 渲染与单测断言共用，
 * 避免把文案散落在 Composable 内导致测试只能比对渲染树。
 */
internal val settingsGroupTitles = listOf(
    "提醒与评估",
    "AI 与策略",
    "数据",
    "交易记录",
    "网格交易",
    "成就",
)

/**
 * 设置页（底部导航「设置」Tab）—— 纯入口列表。
 *
 * 每个分组一个跳转入口，点进去是对应的二级详情页（表单/子入口）：
 * - [AlertEvalSettingsScreen]：股息率阈值 + 评估门槛 + 通知可靠性
 * - [LlmStrategySettingsScreen]：LLM 配置 + 策略库
 * - [DataSettingsScreen]：数据管理 + 缓存管理 + OCR 调试
 */
@Composable
fun SettingsScreen(
    onOpenAlertEvalSettings: () -> Unit,
    onOpenLlmStrategySettings: () -> Unit,
    onOpenDataSettings: () -> Unit,
    onOpenTransactionHistory: () -> Unit,
    onOpenGridPlan: () -> Unit,
    onOpenAchievements: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsNavRow(
            title = settingsGroupTitles[0],
            description = "股息率阈值提醒、一键评估门槛、通知可靠性",
            icon = Icons.Filled.Tune,
            onClick = onOpenAlertEvalSettings
        )
        SettingsNavRow(
            title = settingsGroupTitles[1],
            description = "LLM 配置（AI 解读）、策略库",
            icon = Icons.Filled.AutoGraph,
            onClick = onOpenLlmStrategySettings
        )
        SettingsNavRow(
            title = settingsGroupTitles[2],
            description = "备份/恢复、缓存管理、OCR 调试",
            icon = Icons.Filled.CloudSync,
            onClick = onOpenDataSettings
        )
        SettingsNavRow(
            title = settingsGroupTitles[3],
            description = "全局买卖流水、复盘备注",
            icon = Icons.Filled.ReceiptLong,
            onClick = onOpenTransactionHistory
        )
        SettingsNavRow(
            title = settingsGroupTitles[4],
            description = "网格档位表、下一档提示（仅计划，不下单）",
            icon = Icons.Filled.GridOn,
            onClick = onOpenGridPlan
        )
        SettingsNavRow(
            title = settingsGroupTitles[5],
            description = "使用成就与里程碑",
            icon = Icons.Filled.EmojiEvents,
            onClick = onOpenAchievements
        )
    }
}

/**
 * 跳转入口行：[icon] + 标题/描述 + 右箭头，整行可点击。
 *
 * 设置主页与各二级详情页共用。图标染 primary 色以区分功能。
 */
@Composable
internal fun SettingsNavRow(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null
        )
    }
}
