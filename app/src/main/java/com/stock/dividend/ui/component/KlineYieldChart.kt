package com.stock.dividend.ui.component

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stock.dividend.data.repository.DividendYieldGridCalculator
import com.stock.dividend.data.repository.DividendYieldLine
import com.stock.dividend.data.repository.KlineBar
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.data.repository.PercentFormatter
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.tabularNumberStyle

/**
 * 近 N 日 K 线（蜡烛图）+ 股息率网格水平线。
 *
 * 纯 Compose Canvas 自绘（Vico 开源版无蜡烛图层、MPAndroidChart 为遗留技术且不适配双主题）：
 * - **价格面板**：蜡烛（影线 + 实体），涨=[LocalExtendedColors] positive（绿）、跌=negative（红），
 *   与全 App 财务色约定一致；十字星实体最矮 1dp 防消失。
 * - **股息率网格**：金色（tertiary，财富/分红语义色）虚线横贯，右侧标签同时标注股息率与对应价
 *   （`6.5% ¥9.23`）。档位由 [DividendYieldGridCalculator] 计算：价 = 年度每股分红 ÷ 股息率，
 *   围绕现价隐含股息率按 0.5% 步长取整档，仅保留区间内档位。[dps] 缺失时不画线（图例降级说明）。
 * - **成交量面条**：底部迷你柱按当日涨跌着色（减淡），保留旧价量图的成交量信息。
 * - **日期**：首/末两根 MM-dd，中间不标避免拥挤。
 *
 * 口径说明：K 线为腾讯前复权价（缓存按除权日全量重建，尾部即真实价）；股息率线的 DPS 与
 * `MarketDataPlane.getCurrentDividendYield` 全 App 唯一口径同源，不做任何原始数据换算。
 * 数据来自 [KlineBar]（升序，旧→新），空数据不渲染（调用方判空）。
 *
 * @param bars K 线（升序），取末 [VISIBLE_BARS] 根展示
 * @param dps 年度每股现金分红（null/≤0 → 不画股息率线）
 * @param currentPrice 现价（股息率档位锚定参考；缺失时由计算器退到区间中点）
 */
@Composable
fun KlineYieldChart(
    bars: List<KlineBar>,
    dps: Double?,
    currentPrice: Double?,
    modifier: Modifier = Modifier
) {
    if (bars.isEmpty()) return
    val ext = LocalExtendedColors.current
    val textMeasurer = rememberTextMeasurer()
    val display = remember(bars) { bars.takeLast(VISIBLE_BARS) }

    // 区间价格摘要：最新价 + 区间涨跌/高低
    val last = display.last()
    val first = display.first()
    val high = display.maxOf { it.high }
    val low = display.minOf { it.low }
    val periodChangePct = runCatching {
        (last.close - first.open) / first.open * 100.0
    }.getOrDefault(0.0)

    // 股息率网格线（区间内整档；dps 无效降级空表，蜡烛图照常渲染）
    val yieldLines = remember(display, dps, currentPrice) {
        if (dps == null || dps <= 0.0) {
            emptyList()
        } else {
            DividendYieldGridCalculator.computeLines(
                dps = dps, lowPrice = low, highPrice = high, currentPrice = currentPrice
            )
        }
    }

    // 绘制用颜色/样式（Compose 侧取主题，传入 Canvas）
    val yieldColor = MaterialTheme.colorScheme.tertiary
    val upColor = ext.positive
    val downColor = ext.negative
    val yieldLabelStyle = MaterialTheme.typography.labelSmall
        .merge(tabularNumberStyle)
        .copy(fontSize = 10.sp, color = yieldColor)
    val dateStyle = MaterialTheme.typography.labelSmall
        .merge(tabularNumberStyle)
        .copy(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

    val legendText = when {
        dps == null || dps <= 0.0 -> "暂无分红数据，未画股息率线 · 底部柱为成交量（手）"
        yieldLines.isEmpty() -> "区间内无整档股息率价位（股息率过低） · 底部柱为成交量（手）"
        else -> "虚线 = 股息率价位（年分红 ${MoneyFormatter.withSymbol(dps, decimals = 4)} ÷ 股息率） · 底部柱为成交量（手）"
    }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AppCardDefaults.ListPadding)) {
            // 价格摘要行：最新价 + 区间涨跌（涨绿跌红）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "最新价",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = MoneyFormatter.withSymbol(last.close),
                        style = MaterialTheme.typography.titleMedium.merge(tabularNumberStyle),
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "区间涨跌",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = PercentFormatter.withSign(periodChangePct),
                        style = MaterialTheme.typography.titleMedium.merge(tabularNumberStyle),
                        fontWeight = FontWeight.Bold,
                        color = if (periodChangePct > 0) ext.positive
                        else if (periodChangePct < 0) ext.negative
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "高 ${MoneyFormatter.withSymbol(high)} · 低 ${MoneyFormatter.withSymbol(low)}",
                style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
                val n = display.size
                val priceH = PRICE_PANEL_HEIGHT.toPx()
                val volTop = priceH + PANEL_GAP.toPx()
                val volH = VOLUME_PANEL_HEIGHT.toPx()
                val dateY = volTop + volH + 2.dp.toPx()

                // Y 轴范围：区间高低各留 6% 余量，蜡烛不满贴边；一字区间给 2% 防退化
                var range = high - low
                if (range <= 0.0) range = high.coerceAtLeast(1.0) * 0.02
                val yMax = high + range * 0.06
                val yMin = low - range * 0.06

                fun yFor(price: Double): Float = ((yMax - price) / (yMax - yMin) * priceH).toFloat()

                // 右侧 gutter：按最宽股息率标签自适应（无档位时为 0，蜡烛占满全宽）
                val measuredLabels = yieldLines.map {
                    textMeasurer.measure(yieldLineLabelText(it), yieldLabelStyle)
                }
                val gutter = measuredLabels.maxOfOrNull { it.size.width }?.plus(8.dp.roundToPx()) ?: 0
                val plotW = (size.width - gutter).coerceAtLeast(40.dp.toPx())

                // 1) 股息率水平虚线 + 右侧标签（股息率% + 对应价）
                if (measuredLabels.isNotEmpty()) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx()))
                    yieldLines.zip(measuredLabels).forEach { (line, label) ->
                        val y = yFor(line.price)
                        drawLine(
                            color = yieldColor,
                            start = Offset(0f, y),
                            end = Offset(plotW, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = dash
                        )
                        val labelY = (y - label.size.height / 2f)
                            .coerceIn(0f, (priceH - label.size.height).coerceAtLeast(0f))
                        drawText(label, topLeft = Offset(plotW + 4.dp.toPx(), labelY))
                    }
                }

                // 2) 蜡烛：影线（最高-最低）+ 实体（开-收）
                val slot = plotW / n
                val bodyW = (slot * 0.6f).coerceAtLeast(2.dp.toPx())
                val wickW = 1.dp.toPx()
                display.forEachIndexed { i, bar ->
                    val cx = slot * i + slot / 2f
                    val color = if (bar.close >= bar.open) upColor else downColor
                    drawLine(
                        color = color,
                        start = Offset(cx, yFor(bar.high)),
                        end = Offset(cx, yFor(bar.low)),
                        strokeWidth = wickW
                    )
                    val bodyTop = yFor(maxOf(bar.open, bar.close))
                    val bodyBottom = yFor(minOf(bar.open, bar.close))
                    val bodyH = (bodyBottom - bodyTop).coerceAtLeast(1.dp.toPx())
                    drawRect(
                        color = color,
                        topLeft = Offset(cx - bodyW / 2f, bodyTop),
                        size = Size(bodyW, bodyH)
                    )
                }

                // 3) 成交量面条：高度归一化，按当日涨跌着色（减淡）
                val maxVol = display.maxOf { it.volume }.coerceAtLeast(1.0)
                display.forEachIndexed { i, bar ->
                    val cx = slot * i + slot / 2f
                    val vh = (bar.volume / maxVol * volH).toFloat()
                    if (vh > 0f) {
                        drawRect(
                            color = (if (bar.close >= bar.open) upColor else downColor).copy(alpha = 0.45f),
                            topLeft = Offset(cx - bodyW / 2f, volTop + volH - vh),
                            size = Size(bodyW, vh)
                        )
                    }
                }

                // 4) 首末日期标签（MM-dd，对齐绘图区两端）
                val firstLabel = textMeasurer.measure(klineDateLabel(display.first().date), dateStyle)
                val lastLabel = textMeasurer.measure(klineDateLabel(display.last().date), dateStyle)
                drawText(firstLabel, topLeft = Offset(0f, dateY))
                drawText(
                    lastLabel,
                    topLeft = Offset((plotW - lastLabel.size.width).coerceAtLeast(0f), dateY)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = legendText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private const val VISIBLE_BARS = 30
private val PRICE_PANEL_HEIGHT = 190.dp
private val VOLUME_PANEL_HEIGHT = 36.dp
private val PANEL_GAP = 10.dp
private val DATE_ROW_HEIGHT = 16.dp
private val CHART_HEIGHT = PRICE_PANEL_HEIGHT + PANEL_GAP + VOLUME_PANEL_HEIGHT + DATE_ROW_HEIGHT

/**
 * 股息率线右侧标签文本：`"6.5% ¥9.23"`（股息率 1 位小数 + 价格 2 位 + 千分位）。
 * internal 纯函数，无 Android 依赖，便于单测（格式统一走 [PercentFormatter]/[MoneyFormatter]）。
 */
internal fun yieldLineLabelText(line: DividendYieldLine): String =
    "${PercentFormatter.percent(line.yieldPercent, decimals = 1)} ${MoneyFormatter.withSymbol(line.price)}"

/**
 * K 线日期标签：取 `MM-dd`（date 为 `YYYY-MM-DD`）；长度异常回退非空占位符
 * （空串在 Canvas 绘制无意义，与全 App 日期轴「永不返回空串」约定一致）。
 */
internal fun klineDateLabel(date: String): String =
    date.takeIf { it.length >= 6 }?.substring(5) ?: "—"
