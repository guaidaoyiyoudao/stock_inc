package com.stock.dividend.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.data.repository.PercentFormatter
import com.stock.dividend.ui.theme.tabularNumberStyle

@Composable
fun DividendSummaryCard(
    totalAmount: Double,
    totalMarketValue: Double? = null,
    /** 总成本息率（基于持仓成本，非市值）。无成本或无股息数据时为 null。 */
    costDividendYield: Double? = null,
    modifier: Modifier = Modifier
) {
    val annualYield = totalMarketValue
        ?.takeIf { it > 0.0 }
        ?.let { totalAmount / it * 100 }

    AppCard(
        modifier = modifier.fillMaxWidth(),
        tone = AppCardTone.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "年股息预测",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    costDividendYield?.let {
                        Text(
                            text = "成本息率 ${PercentFormatter.fromRatio(it, decimals = 2)}",
                            style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (annualYield != null) Spacer(modifier = Modifier.width(8.dp))
                    }
                    annualYield?.let {
                        Text(
                            text = "股息率 ${PercentFormatter.percent(it)}",
                            style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 大额展示：¥ 符号加粗 + 金额（tnum 等宽对齐）
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("¥ ")
                    }
                    append(MoneyFormatter.amount(totalAmount))
                },
                style = MaterialTheme.typography.headlineSmall.merge(tabularNumberStyle),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SummaryMetric(
                    label = "日均",
                    value = MoneyFormatter.withSymbol(totalAmount / 365),
                    modifier = Modifier.weight(1f)
                )
                MetricDivider()
                SummaryMetric(
                    label = "月均",
                    value = MoneyFormatter.withSymbol(totalAmount / 12),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.merge(tabularNumberStyle),
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
private fun MetricDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .width(1.dp)
            .height(34.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}
