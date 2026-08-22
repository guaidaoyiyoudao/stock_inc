package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 「数据」设置页（设置主页的二级页面）。
 *
 * 含数据管理（备份/恢复）、缓存管理、失败日志与 OCR 调试（临时）四个跳转入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSettingsScreen(
    onBack: () -> Unit,
    onOpenDataManagement: () -> Unit,
    onOpenCacheManagement: () -> Unit,
    onOpenErrorLogs: () -> Unit,
    onOpenOcrDebug: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsNavRow(
                title = "数据管理",
                description = "导入或导出本地备份文件",
                icon = Icons.Filled.CloudSync,
                onClick = onOpenDataManagement
            )
            SettingsNavRow(
                title = "缓存管理",
                description = "查看各类缓存条目数，按需清理；历史不可变数据永久缓存",
                icon = Icons.Filled.CleaningServices,
                onClick = onOpenCacheManagement
            )
            SettingsNavRow(
                title = "失败日志",
                description = "查看数据获取失败等关键失败记录，支持一键清理",
                icon = Icons.Filled.Warning,
                onClick = onOpenErrorLogs
            )
            // 临时调试入口，定位 OCR 识别问题后删除
            SettingsNavRow(
                title = "OCR 调试（临时）",
                description = "测试不同预处理方式对截图识别的影响",
                icon = Icons.Filled.BugReport,
                onClick = onOpenOcrDebug
            )
        }
    }
}
