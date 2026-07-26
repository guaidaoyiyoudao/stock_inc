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
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.BollTone
import com.stock.dividend.data.repository.HoldingRecommender
import com.stock.dividend.ui.theme.FinanceGreen
import com.stock.dividend.ui.theme.FinanceRed

/**
 * 周线 BOLL 带→价位 横轴。与 [DividendPriceScale] 视觉对等：一条横轴展示
 * 上轨 / 中轨(MA20) / 下轨 三档价位，并按当前价在带内的位置高亮。
 *
 * 语义（与股息率横轴的买/卖点配色保持一致）：
 *  - 当前价 **触及/跌破下轨**（超卖区，[BollTone.Buy]）→ 绿色，买入信号；
 *  - 当前价 **触及/突破上轨**（超买区，[BollTone.Sell]）→ 红色，卖出信号；
 *  - 当前价 **在中轨附近**（[BollTone.Current]）→ 主色，中性。
 *
 * 仅在 [band] 与 [currentPrice] 均有效时渲染带状刻度；否则显示加载/无数据占位条。
 */
@Composable
fun BollPriceScale(
    currentPrice: Double?,
    band: BollBand?,
    modifier: Modifier = Modifier
) {
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
                text = "超卖",
                style = MaterialTheme.typography.labelSmall,
                color = FinanceGreen,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "周线BOLL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "超买",
                style = MaterialTheme.typography.labelSmall,
                color = FinanceRed,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (band == null || currentPrice == null || currentPrice <= 0.0) {
            // 占位：保持与 DividendPriceScale 空态等高，避免切换时卡片高度跳动
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (band == null) "BOLL 数据加载中" else "暂无现价",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        val price = currentPrice
        val upper = band.upper
        val middle = band.middle
        val lower = band.lower
        // 当前价落点的高亮 tone：越靠近下轨越偏买，越靠近上轨越偏卖
        val tone = HoldingRecommender.bollTone(price, upper, middle, lower)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // 下轨（左侧，超卖/买区）
            BollTick(
                label = "下轨",
                price = lower,
                tone = BollTone.Buy
            )
            // 中轨 MA20（中间，原点）
            BollTick(
                label = "中轨",
                price = middle,
                tone = BollTone.Current,
                isOrigin = true
            )
            // 上轨（右侧，超买/卖区）
            BollTick(
                label = "上轨",
                price = upper,
                tone = BollTone.Sell
            )
        }

        // 当前价落点指示：用 fraction 表示 price 在 [lower, upper] 内的相对位置（0=下轨，1=上轨）
        val span = (upper - lower).takeIf { it > 0.0 } ?: 1.0
        val fraction = ((price - lower) / span).coerceIn(0.0, 1.0)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "现价",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "%.2f".format(price),
                style = MaterialTheme.typography.labelSmall,
                color = toneColor(tone),
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
            Text(
                text = "${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun toneColor(tone: BollTone) = when (tone) {
    BollTone.Buy -> FinanceGreen
    BollTone.Sell -> FinanceRed
    BollTone.Current -> MaterialTheme.colorScheme.primary
}

@Composable
private fun BollTick(
    label: String,
    price: Double,
    tone: BollTone,
    isOrigin: Boolean = false
) {
    val color = toneColor(tone)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isOrigin) color else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isOrigin) FontWeight.Bold else FontWeight.Normal,
            fontSize = 10.sp
        )
        Box(
            modifier = Modifier
                .width(if (isOrigin) 4.dp else 3.dp)
                .height(26.dp)
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
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        } else {
            Spacer(modifier = Modifier.size(5.dp))
        }
    }
}
