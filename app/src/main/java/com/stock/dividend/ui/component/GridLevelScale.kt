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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stock.dividend.data.repository.GridLevel
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.tabularNumberStyle
import kotlin.math.abs

/**
 * 网格档位刻度尺（横向）。
 *
 * 把纯买入网格的档位表画成一条价格轴：**价格从左到右递增**——
 * 最左是资金用完位（最便宜档），最右是买入起点（最贵档）。
 *  - 已触发档（实际买入过）淡化 + ✓；
 *  - 下一买档（现价下方最近档）primary 高亮 + 圆点；
 *  - 刻度柱高度按「相对买入起点的偏离幅度」增长（偏离越深柱越长）。
 *
 * 底部一行「现价 + 距下一档跌幅」，回答"还差多少到下一档"。
 * 无现价或无档位时渲染固定高占位（避免卡片高度跳动，参照 BollPriceScale）。
 */
@Composable
fun GridLevelScale(
    currentPrice: Double?,
    levels: List<GridLevel>,
    nextBuyHint: Double?,
    modifier: Modifier = Modifier
) {
    if (levels.isEmpty()) return
    val ext = LocalExtendedColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "资金用完位",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "档位",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "买入起点",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (currentPrice == null || currentPrice <= 0.0) {
            // 固定高占位：现价未加载时保持刻度尺高度，避免卡片跳动
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "现价加载中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        // 刻度柱高度按相对买入起点的偏离幅度归一化（偏离越深柱越长）
        val basePrice = levels.maxOf { it.price }.coerceAtLeast(0.0001)
        val maxDeviation = levels
            .map { abs(basePrice - it.price) / basePrice }
            .maxOrNull()?.coerceAtLeast(0.0001) ?: 0.0001

        // 价格从左到右递增：最便宜档（资金用完位）在左，买入起点在右
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            levels.forEach { level ->
                val isNextBuy = nextBuyHint != null && level.price == nextBuyHint
                GridScaleTick(
                    price = level.price,
                    triggered = level.triggered,
                    isNextBuy = isNextBuy,
                    fraction = {
                        (abs(basePrice - level.price) / basePrice / maxDeviation).coerceIn(0.0, 1.0)
                    }
                )
            }
        }

        // 底部：现价 + 距下一档跌幅（回答"还差多少到下一档"）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "现价 ${"%.2f".format(currentPrice)}",
                style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            if (nextBuyHint != null && nextBuyHint < currentPrice) {
                val gapPct = (nextBuyHint - currentPrice) / currentPrice * 100.0
                Text(
                    text = "距下一档 ${"%.1f".format(gapPct)}%",
                    style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                    color = ext.positive,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Text(
                    text = "已到/跌破资金用完位",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GridScaleTick(
    price: Double,
    triggered: Boolean,
    isNextBuy: Boolean,
    fraction: () -> Double
) {
    val ext = LocalExtendedColors.current
    // 下一买档 primary 强调；已触发档淡化；其余档用正向色（买入区）
    val color = when {
        isNextBuy -> MaterialTheme.colorScheme.primary
        triggered -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        else -> ext.positive.copy(alpha = 0.8f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = if (triggered) "✓" else "买",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp
        )

        // 刻度柱：偏离买入起点越深柱越长
        val barHeight = (26f * (0.18f + 0.82f * fraction().toFloat())).dp
        Box(
            modifier = Modifier
                .width(if (isNextBuy) 4.dp else 3.dp)
                .height(barHeight)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )

        Text(
            text = "%.2f".format(price),
            style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
            color = color,
            fontWeight = if (isNextBuy) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.size(5.dp))
    }
}
