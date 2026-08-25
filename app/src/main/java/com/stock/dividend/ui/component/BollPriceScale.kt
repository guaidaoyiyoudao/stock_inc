package com.stock.dividend.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.BollTone
import com.stock.dividend.data.repository.HoldingRecommender
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.ui.theme.LocalExtendedColors

/**
 * 周线 BOLL 带→价位 横轴。与 [DividendPriceScale] 视觉对等：一条横轴展示
 * 上轨 / 中轨(MA20) / 下轨 三档价位，**现价点按带内真实比例定位在横轴上**
 * （0=下轨、1=上轨；下轨→现价区间着色，贴边=破轨），并按当前价位置高亮 tone。
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
    val ext = LocalExtendedColors.current
    val textMeasurer = rememberTextMeasurer()  // 价签精确量宽（点上方居中 + 两端钳制）

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
                text = "超卖",
                style = MaterialTheme.typography.labelSmall,
                color = ext.positive,
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
                color = ext.negative,
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

        // 当前价落点：fraction 表示 price 在 [lower, upper] 内的相对位置（0=下轨，1=上轨）
        val span = (upper - lower).takeIf { it > 0.0 } ?: 1.0
        val fraction = ((price - lower) / span).coerceIn(0.0, 1.0)

        // 现价位置轴：横轴按比例定位现价点（0=下轨 … 1=上轨），**价签跟随点移动**（上下正对）；
        // 点贴到左/右边缘 = 已破下轨/上轨
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
        ) {
            val dotSize = 12.dp
            val usable = maxWidth - dotSize  // 点中心可移动范围（两端各让半个点，防裁切）
            val dotColor = toneColor(tone)
            val trackY = 32.dp  // 轨道 y（4dp 高，中心 34dp）
            val dotX = usable * fraction.toFloat()
            val dotCenterX = dotX + dotSize / 2

            // 价签精确量宽 → 在点正上方居中；靠近两端时钳制在边界内（不裁切）
            val labelStyle = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
            val labelText = MoneyFormatter.amount(price)
            val labelWidth = with(LocalDensity.current) {
                textMeasurer.measure(AnnotatedString(labelText), labelStyle).size.width.toDp()
            }
            Text(
                text = labelText,
                style = labelStyle,
                color = dotColor,
                modifier = Modifier.offset(
                    x = (dotCenterX - labelWidth / 2).coerceIn(0.dp, maxWidth - labelWidth),
                    y = 12.dp
                )
            )

            // 轨道
            Box(
                modifier = Modifier
                    .offset(x = dotSize / 2, y = trackY)
                    .width(maxWidth - dotSize)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            // 下轨→现价已走区间（tone 色半透明）
            Box(
                modifier = Modifier
                    .offset(x = dotSize / 2, y = trackY)
                    .width(dotX)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(dotColor.copy(alpha = 0.45f))
            )
            // 中轨参考刻痕（带正中；BOLL 上下轨对称于中轨）
            Box(
                modifier = Modifier
                    .offset(x = maxWidth / 2 - 1.dp, y = trackY - 3.dp)
                    .width(2.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            )
            // 现价点：按真实比例偏移（点中心与价签中心同 x）；表面色描边使其在轨道上突出
            Box(
                modifier = Modifier
                    .offset(x = dotX, y = trackY + 2.dp - dotSize / 2)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "现价落点（下轨 ↔ 上轨）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                // 破轨时明示（点此刻贴在轴边缘）；带内显示位置百分比
                text = when {
                    price > upper -> "破上轨 ↑"
                    price < lower -> "破下轨 ↓"
                    else -> "带内 ${(fraction * 100).toInt()}%"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    price > upper -> ext.negative
                    price < lower -> ext.positive
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun toneColor(tone: BollTone): Color {
    val ext = LocalExtendedColors.current
    return when (tone) {
        BollTone.Buy -> ext.positive
        BollTone.Sell -> ext.negative
        BollTone.Current -> MaterialTheme.colorScheme.primary
    }
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
            text = MoneyFormatter.amount(price),
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
