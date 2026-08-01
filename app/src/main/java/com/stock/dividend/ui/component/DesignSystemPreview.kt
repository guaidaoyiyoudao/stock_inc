package com.stock.dividend.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.StockDividendTheme

/**
 * 设计系统预览页（仅用于 Android Studio 预览面板，不打包进功能）。
 *
 * 集中展示所有设计 Token 与新组件，便于：
 * 1. 视觉回归（改主题后一眼看出影响）
 * 2. 下期逐屏迁移时作组件参考
 *
 * 用法：Android Studio 打开本文件，在 @Preview 处查看渲染结果。
 */

// ── Token 展示 ───────────────────────────────────────────────────────

@Composable
private fun ColorPaletteRow(label: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color, MaterialTheme.shapes.small),
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ColorPaletteSection() {
    SectionHeader(title = "调色板")
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        ColorPaletteRow("primary", MaterialTheme.colorScheme.primary)
        ColorPaletteRow("onPrimary", MaterialTheme.colorScheme.onPrimary)
        ColorPaletteRow("primaryContainer", MaterialTheme.colorScheme.primaryContainer)
        ColorPaletteRow("secondary", MaterialTheme.colorScheme.secondary)
        ColorPaletteRow("tertiary", MaterialTheme.colorScheme.tertiary)
        ColorPaletteRow("background", MaterialTheme.colorScheme.background)
        ColorPaletteRow("surface", MaterialTheme.colorScheme.surface)
        ColorPaletteRow("surfaceVariant", MaterialTheme.colorScheme.surfaceVariant)
        ColorPaletteRow("outline", MaterialTheme.colorScheme.outline)
        // 扩展财务色
        val ext = LocalExtendedColors.current
        ColorPaletteRow("positive (涨)", ext.positive)
        ColorPaletteRow("positiveContainer", ext.positiveContainer)
        ColorPaletteRow("negative (跌)", ext.negative)
        ColorPaletteRow("negativeContainer", ext.negativeContainer)
    }
}

@Composable
private fun TypographySection() {
    SectionHeader(title = "字体样张（Inter）")
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("headlineLarge 30sp Bold", style = MaterialTheme.typography.headlineLarge)
        Text("headlineMedium 24sp Bold", style = MaterialTheme.typography.headlineMedium)
        Text("titleLarge 18sp Bold", style = MaterialTheme.typography.titleLarge)
        Text("titleMedium 16sp SemiBold", style = MaterialTheme.typography.titleMedium)
        Text("bodyLarge 16sp Regular", style = MaterialTheme.typography.bodyLarge)
        Text("bodyMedium 14sp Regular", style = MaterialTheme.typography.bodyMedium)
        Text("labelLarge 14sp SemiBold", style = MaterialTheme.typography.labelLarge)
        Text("labelSmall 11sp Medium", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ShapeSection() {
    SectionHeader(title = "形状（圆角）")
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val shapes = listOf(
            "xs" to MaterialTheme.shapes.extraSmall,
            "sm" to MaterialTheme.shapes.small,
            "md" to MaterialTheme.shapes.medium,
            "lg" to MaterialTheme.shapes.large,
            "xl" to MaterialTheme.shapes.extraLarge,
        )
        shapes.forEach { (label, shape) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), shape),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

// ── 组件展示 ─────────────────────────────────────────────────────────

@Composable
private fun AppCardSection() {
    SectionHeader(title = "AppCard（三态）")
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppCard(tone = AppCardTone.Surface) {
            Text("Surface 态", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }
        AppCard(tone = AppCardTone.List) {
            Text("List 态（列表行）", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }
        AppCard(tone = AppCardTone.Summary) {
            Text("Summary 态（汇总）", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun AmountTextSection() {
    SectionHeader(title = "AmountText / PercentText（金融专用）")
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 金额三态：正/负/零
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("盈利", style = MaterialTheme.typography.bodyMedium)
            AmountText(value = 12345.67, signed = true)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("亏损", style = MaterialTheme.typography.bodyMedium)
            AmountText(value = -5432.10, signed = true)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("总资产", style = MaterialTheme.typography.bodyMedium)
            AmountText(value = 123456.78, colored = false, style = MaterialTheme.typography.headlineMedium)
        }
        // 百分比
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("股息率", style = MaterialTheme.typography.bodyMedium)
            PercentText(value = 5.23, colored = true)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("涨跌幅", style = MaterialTheme.typography.bodyMedium)
            PercentText(value = -2.15, colored = true, signed = true)
        }
        // 指标行
        FinanceMetricRow(label = "持仓市值", value = "¥1,234,567.89")
        FinanceMetricRow(
            label = "今日盈亏",
            value = "+¥12,345.67",
            valueColor = LocalExtendedColors.current.positive,
        )
    }
}

@Composable
private fun ButtonSection() {
    SectionHeader(title = "按钮族")
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppButton(onClick = {}, text = "主按钮 AppButton")
        AppOutlinedButton(onClick = {}, text = "边框按钮 AppOutlinedButton")
        AppTextButton(onClick = {}, text = "文字按钮 AppTextButton")
    }
}

// ── 主题预览入口 ─────────────────────────────────────────────────────

@Composable
private fun DesignSystemPreviewContent() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column {
            ColorPaletteSection()
            TypographySection()
            ShapeSection()
            AppCardSection()
            AmountTextSection()
            ButtonSection()
        }
    }
}

@Preview(name = "亮色主题", showBackground = true, widthDp = 360)
@Composable
fun DesignSystemPreviewLight() {
    StockDividendTheme(darkTheme = false) {
        DesignSystemPreviewContent()
    }
}

@Preview(name = "深色主题", showBackground = true, widthDp = 360, uiMode = 0x20)
@Composable
fun DesignSystemPreviewDark() {
    StockDividendTheme(darkTheme = true) {
        DesignSystemPreviewContent()
    }
}
