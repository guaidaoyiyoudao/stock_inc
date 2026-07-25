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
import androidx.compose.foundation.shape.CircleShape
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
import com.stock.dividend.ui.theme.FinanceGreen
import com.stock.dividend.ui.theme.FinanceRed
import kotlin.math.abs

/**
 * 股息率→目标价 横轴。
 *
 * 以最近一年年股息 [latestYearlyDividend] 推算当前价对应的隐含股息率为原点，向上下各取
 * [stepsPerSide] 档（默认 3 档），步长 [stepSize]（默认 0.5%），并对齐到 [stepSize] 的网格上。
 * 目标价 P = D / (yield/100)：
 *  - 股息率从左到右**递增**排列；当前价（隐含股息率）为原点，居中高亮。
 *  - 左侧（价更高、股息率更低）为卖出区，红色；
 *  - 右侧（价更低、股息率更高）为买入区，绿色。
 *
 * 例如当前隐含股息率 5.3%：左侧卖点 4.0%/4.5%/5.0%，原点 5.3%，右侧买点 5.5%/6.0%/6.5%。
 *
 * 仅在 [currentPrice] 与 [latestYearlyDividend] 均有效时渲染，否则返回空内容。
 */
@Composable
fun DividendPriceScale(
    currentPrice: Double?,
    latestYearlyDividend: Double?,
    modifier: Modifier = Modifier,
    stepsPerSide: Int = 3,
    stepSize: Double = 0.5
) {
    if (currentPrice == null || currentPrice <= 0.0 ||
        latestYearlyDividend == null || latestYearlyDividend <= 0.0
    ) {
        return
    }

    val annualDividend = latestYearlyDividend
    val impliedYield = annualDividend / currentPrice * 100.0

    // 挡位对齐到 stepSize 网格，且买卖挡都严格不等于隐含股息率本身。
    // 例：隐含 5.3% → 买点 5.5/6.0/6.5%，卖点 5.0/4.5/4.0%；
    //     隐含正好 5.5% → 买点 6.0/6.5/7.0%，卖点 5.0/4.5/4.0%。
    // lastSellGrid = 不大于隐含股息率的最大网格（≤ impliedYield），firstBuyGrid = lastSellGrid + 1（> impliedYield）。
    val lastSellGrid = kotlin.math.floor(impliedYield / stepSize + 1e-6).toInt()
    val buySteps = (1..stepsPerSide).map { (lastSellGrid + it) * stepSize }   // 买点：股息率更高 → 价更低 → 左侧
    val sellSteps = (1..stepsPerSide).map { (lastSellGrid + 1 - it) * stepSize } // 卖点：股息率更低 → 价更高 → 右侧

    // 横向坐标映射：以「相对当前价的偏离百分比」线性映射，原点居中。
    // 取两侧最大偏离作为刻度，保证原点落在正中。
    val allDeviations = (buySteps + sellSteps).map {
        (annualDividend / (it / 100.0) - currentPrice) / currentPrice
    }
    val maxDeviation = allDeviations.maxOfOrNull { abs(it) }?.coerceAtLeast(0.0001) ?: 0.0001

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
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
                text = "卖点",
                style = MaterialTheme.typography.labelSmall,
                color = FinanceRed,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "股息率价位",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "买点",
                style = MaterialTheme.typography.labelSmall,
                color = FinanceGreen,
                fontWeight = FontWeight.SemiBold
            )
        }

        // 横轴主体：股息率从左到右递增排列。
        // 左侧卖点档（价更高、股息率更低）→ 中间原点(当前价) → 右侧买点档（价更低、股息率更高）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            sellSteps.asReversed().forEach { step ->
                ScaleTick(
                    yieldPercent = step,
                    price = annualDividend / (step / 100.0),
                    fraction = {
                        abs((annualDividend / (step / 100.0) - currentPrice) / currentPrice) / maxDeviation
                    },
                    tone = ScaleTone.Sell
                )
            }

            // 原点：当前价
            ScaleTick(
                yieldPercent = impliedYield,
                price = currentPrice,
                isOrigin = true,
                fraction = { 0.0 },
                tone = ScaleTone.Current
            )

            buySteps.forEach { step ->
                ScaleTick(
                    yieldPercent = step,
                    price = annualDividend / (step / 100.0),
                    fraction = {
                        abs((annualDividend / (step / 100.0) - currentPrice) / currentPrice) / maxDeviation
                    },
                    tone = ScaleTone.Buy
                )
            }
        }
    }
}

private enum class ScaleTone { Buy, Current, Sell }

@Composable
private fun ScaleTick(
    yieldPercent: Double,
    price: Double,
    fraction: () -> Double,
    tone: ScaleTone,
    isOrigin: Boolean = false
) {
    val color = when (tone) {
        ScaleTone.Buy -> FinanceGreen
        ScaleTone.Sell -> FinanceRed
        ScaleTone.Current -> MaterialTheme.colorScheme.primary
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = "${"%.1f".format(yieldPercent)}%",
            style = MaterialTheme.typography.labelSmall,
            color = if (isOrigin) color else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isOrigin) FontWeight.Bold else FontWeight.Normal,
            fontSize = 10.sp
        )

        // 刻度条：偏离越大柱越长（最高 ~ 限高），原点最短，便于一眼判断距离。
        val maxBarHeight = 26f
        val frac = fraction().toFloat().coerceIn(0f, 1f)
        val barHeight = (maxBarHeight * (0.18f + 0.82f * frac)).dp
        Box(
            modifier = Modifier
                .width(if (isOrigin) 4.dp else 3.dp)
                .height(barHeight)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )

        Text(
            text = "%.2f".format(price),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = if (isOrigin) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 10.sp
        )

        if (isOrigin) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        } else {
            Spacer(modifier = Modifier.size(5.dp))
        }
    }
}
