package com.stock.dividend.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.data.repository.PercentFormatter
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.tabularNumberStyle

@Composable
fun IncomeSummaryCard(
    year: Int,
    totalAmount: Double,
    prevYearTotal: Double?,
    manualCount: Int,
    autoCount: Int,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier.fillMaxWidth(),
        tone = AppCardTone.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Text(
                text = "${year}年股息收入",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 大额展示：¥ 符号加粗 + 金额（tnum 等宽；千分位由 MoneyFormatter 统一）
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("¥ ")
                    }
                    append(MoneyFormatter.amount(totalAmount))
                },
                style = MaterialTheme.typography.headlineMedium.merge(tabularNumberStyle),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val ext = LocalExtendedColors.current
                // YoY comparison（同比涨跌：走财务语义色）
                val (yoyText, yoyColor) = when {
                    prevYearTotal == null -> "首年记录" to MaterialTheme.colorScheme.onSurfaceVariant
                    prevYearTotal > 0 -> {
                        val change = ((totalAmount - prevYearTotal) / prevYearTotal) * 100
                        if (change >= 0) {
                            "较去年 ↑${PercentFormatter.percent(change, decimals = 1)}" to ext.positive
                        } else {
                            "较去年 ↓${PercentFormatter.percent(-change, decimals = 1)}" to ext.negative
                        }
                    }
                    else -> "较去年 —" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = yoyText,
                    style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                    color = yoyColor
                )

                // Source breakdown
                Text(
                    text = buildString {
                        if (manualCount > 0) append("${manualCount} 笔实际")
                        if (manualCount > 0 && autoCount > 0) append(" / ")
                        if (autoCount > 0) append("${autoCount} 笔推算")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
