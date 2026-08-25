package com.stock.dividend.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
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
import com.stock.dividend.ui.theme.Motion
import com.stock.dividend.ui.theme.tabularNumberStyle

/**
 * 近 N 日 K 线（蜡烛图）+ 股息率网格水平线。
 *
 * 纯 Compose Canvas 自绘（Vico 开源版无蜡烛图层、MPAndroidChart 为遗留技术且不适配双主题）：
 * - **价格面板**：蜡烛（影线 + 实体），涨=[LocalExtendedColors] positive（绿）、跌=negative（红），
 *   与全 App 财务色约定一致；十字星实体最矮 1dp 防消失。
 * - **股息率网格**：金色（tertiary，财富/分红语义色）虚线横贯，右侧标签同时标注股息率与对应价
 *   （`6.5% ¥9.23`）。档位由 [DividendYieldGridCalculator] 计算：价 = 年度每股分红 ÷ 股息率，
 *   围绕现价隐含股息率按 0.5% 步长取整档，区间内全保留且**最低保证 3 档**（最近档 + 上下各一档，
 *   可落在蜡烛区间外，Y 轴自动扩展容纳）。[dps] 缺失时不画线（图例降级说明）。
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
 * @param maSeries 均线序列（与 [bars] 等长对齐、前 period−1 个为 null；由调用方按全量
 *   收盘价滚动计算后传入，如年线定投策略的 MA250）。空表不画。
 * @param maLabel 均线图例名（如 "MA250"）；非空且序列有效时追加到图例与线端标签。
 */
@Composable
fun KlineYieldChart(
    bars: List<KlineBar>,
    dps: Double?,
    currentPrice: Double?,
    modifier: Modifier = Modifier,
    maSeries: List<Double?> = emptyList(),
    maLabel: String? = null,
) {
    if (bars.isEmpty()) return
    val ext = LocalExtendedColors.current
    val textMeasurer = rememberTextMeasurer()
    val display = remember(bars) { bars.takeLast(VISIBLE_BARS) }
    // 均线切片：与可见蜡烛同长度对齐（序列与全量 bars 等长，取末段；多余/缺失安全截断）
    val maDisplay = remember(bars, maSeries) { maSeries.takeLast(display.size) }
    val hasMa = maDisplay.isNotEmpty() && maDisplay.any { it != null }

    // 入场动画：蜡烛自左向右浮现（绘制进度 0→1，动画结束 = 完整数据集，数值不变）
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(display) {
        reveal.snapTo(0f)
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = Motion.DurationLong, easing = Motion.EmphasizedDecelerate),
        )
    }

    // 区间价格摘要：最新价 + 区间涨跌/高低
    val last = display.last()
    val first = display.first()
    val high = display.maxOf { it.high }
    val low = display.minOf { it.low }
    // 区间涨跌：起点开盘价无效（≤0）时无意义（Double/0.0 得 Infinity 不抛异常，原 runCatching 是死代码）
    val periodChangePct = first.open.takeIf { it > 0.0 }
        ?.let { (last.close - it) / it * 100.0 }
        ?: 0.0

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
    val maColor = MaterialTheme.colorScheme.primary
    val upColor = ext.positive
    val downColor = ext.negative
    val yieldLabelStyle = MaterialTheme.typography.labelSmall
        .merge(tabularNumberStyle)
        .copy(fontSize = 10.sp, color = yieldColor)
    val maLabelStyle = MaterialTheme.typography.labelSmall
        .merge(tabularNumberStyle)
        .copy(fontSize = 10.sp, color = maColor)
    val dateStyle = MaterialTheme.typography.labelSmall
        .merge(tabularNumberStyle)
        .copy(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val dateBaseColor = MaterialTheme.colorScheme.onSurfaceVariant

    // 绘制期文本测量提升到组合期 remember（按数据/样式键失效），避免逐帧 textMeasurer.measure
    val measuredYieldLabels = remember(yieldLines, yieldLabelStyle) {
        yieldLines.map { textMeasurer.measure(yieldLineLabelText(it), yieldLabelStyle) }
    }
    val measuredMaEndLabel = remember(hasMa, maLabel, maDisplay, maLabelStyle) {
        if (hasMa && maLabel != null) {
            maDisplay.lastNotNullOrNull()
                ?.let { textMeasurer.measure(maLineLabelText(maLabel, it), maLabelStyle) }
        } else {
            null
        }
    }
    val measuredDateLabels = remember(display, dateStyle) {
        textMeasurer.measure(klineDateLabel(display.first().date), dateStyle) to
            textMeasurer.measure(klineDateLabel(display.last().date), dateStyle)
    }
    // 均线 Path 复用（绘制期 reset 重画，几何依赖画布尺寸无法整体前移）；股息率虚线
    // dashPattern 只依赖密度，组合期算好，避免每帧新建 PathEffect
    val maPath = remember { Path() }
    val density = LocalDensity.current
    val yieldDashEffect = remember(density) {
        with(density) {
            PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx()))
        }
    }

    // 图例：股息率网格线非空才解释「虚线」含义；dps 缺失才提示降级；
    // 退化区间（dps 有效但无档位）两段都不加，不误导「虚线 = 股息率价位」
    val legendText = buildList {
        if (yieldLines.isNotEmpty() && dps != null) {
            add("虚线 = 股息率价位（年分红 ${MoneyFormatter.withSymbol(dps, decimals = 4)} ÷ 股息率）")
        } else if (dps == null || dps <= 0.0) {
            add("暂无分红数据，未画股息率线")
        }
        if (hasMa && maLabel != null) {
            maDisplay.lastNotNullOrNull()?.let { latest ->
                add("实线 = $maLabel ${MoneyFormatter.amount(latest)}")
            }
        }
        add("底部柱为成交量（手）")
    }.joinToString(" · ")

    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AppCardDefaults.ListPadding)) {
            // 价格摘要行：最新价 + 区间涨跌（涨绿跌红；数字滚动）
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
                    AmountText(
                        value = last.close,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        colored = false,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "区间涨跌",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PercentText(
                        value = periodChangePct,
                        signed = true,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        colored = false,
                        color = if (periodChangePct > 0) ext.positive
                        else if (periodChangePct < 0) ext.negative
                        else MaterialTheme.colorScheme.onSurface,
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
                val progress = reveal.value
                val priceH = PRICE_PANEL_HEIGHT.toPx()
                val volTop = priceH + PANEL_GAP.toPx()
                val volH = VOLUME_PANEL_HEIGHT.toPx()
                val dateY = volTop + volH + 2.dp.toPx()

                // Y 轴范围：蜡烛区间 ∪ 股息线价 ∪ 均线值（最低 3 档保证可能落在蜡烛区间外，扩轴容纳），
                // 各留 6% 余量防贴边；退化区间给 2% 防除零
                val lineLowest = yieldLines.minOfOrNull { it.price }
                val lineHighest = yieldLines.maxOfOrNull { it.price }
                val maValues = maDisplay.mapNotNull { it }
                val rangeLow = minOf(low, lineLowest ?: low, maValues.minOrNull() ?: low)
                val rangeHigh = maxOf(high, lineHighest ?: high, maValues.maxOrNull() ?: high)
                var range = rangeHigh - rangeLow
                if (range <= 0.0) range = rangeHigh.coerceAtLeast(1.0) * 0.02
                val yMax = rangeHigh + range * 0.06
                val yMin = rangeLow - range * 0.06

                fun yFor(price: Double): Float = ((yMax - price) / (yMax - yMin) * priceH).toFloat()

                // 右侧 gutter：按最宽标签自适应（股息率档位 / 均线值；两者皆无时为 0，蜡烛占满全宽）
                val maEndLabel = measuredMaEndLabel
                val measuredLabels = measuredYieldLabels
                val gutter = (measuredLabels.maxOfOrNull { it.size.width } ?: 0)
                    .coerceAtLeast(maEndLabel?.size?.width ?: 0)
                    .takeIf { it > 0 }?.plus(8.dp.roundToPx()) ?: 0
                val plotW = (size.width - gutter).coerceAtLeast(40.dp.toPx())

                // 逐根浮现的透明度：progress*n - i（最右一根渐入，其余 0/1）
                fun alphaAt(i: Int): Float = (progress * n - i).coerceIn(0f, 1f)

                // 1) 股息率水平虚线 + 右侧标签（随入场进度淡入）
                if (measuredLabels.isNotEmpty()) {
                    val dash = yieldDashEffect
                    yieldLines.zip(measuredLabels).forEach { (line, label) ->
                        val y = yFor(line.price)
                        drawLine(
                            color = yieldColor.copy(alpha = progress),
                            start = Offset(0f, y),
                            end = Offset(plotW, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = dash
                        )
                        val labelY = (y - label.size.height / 2f)
                            .coerceIn(0f, (priceH - label.size.height).coerceAtLeast(0f))
                        drawText(
                            label,
                            color = yieldColor.copy(alpha = progress),
                            topLeft = Offset(plotW + 4.dp.toPx(), labelY),
                        )
                    }
                }

                // 2) 蜡烛：影线（最高-最低）+ 实体（开-收），自左向右逐根浮现
                val slot = plotW / n
                val bodyW = (slot * 0.6f).coerceAtLeast(2.dp.toPx())
                val wickW = 1.dp.toPx()
                display.forEachIndexed { i, bar ->
                    val alpha = alphaAt(i)
                    if (alpha <= 0f) return@forEachIndexed
                    val cx = slot * i + slot / 2f
                    val color = if (bar.close >= bar.open) upColor else downColor
                    drawLine(
                        color = color.copy(alpha = alpha),
                        start = Offset(cx, yFor(bar.high)),
                        end = Offset(cx, yFor(bar.low)),
                        strokeWidth = wickW
                    )
                    val bodyTop = yFor(maxOf(bar.open, bar.close))
                    val bodyBottom = yFor(minOf(bar.open, bar.close))
                    val bodyH = (bodyBottom - bodyTop).coerceAtLeast(1.dp.toPx())
                    drawRect(
                        color = color.copy(alpha = alpha),
                        topLeft = Offset(cx - bodyW / 2f, bodyTop),
                        size = Size(bodyW, bodyH)
                    )
                }

                // 3) 均线：实线折线（null 断开），与蜡烛同步浮现——年线定投策略的锚定基准
                if (hasMa) {
                    val slotMa = plotW / n
                    maPath.reset() // 复用组合期 Path 对象，逐帧重画
                    var started = false
                    maDisplay.forEachIndexed { i, v ->
                        if (v != null) {
                            val x = slotMa * i + slotMa / 2f
                            val y = yFor(v)
                            if (started) maPath.lineTo(x, y) else {
                                maPath.moveTo(x, y)
                                started = true
                            }
                        } else {
                            started = false
                        }
                    }
                    drawPath(
                        path = maPath,
                        color = maColor.copy(alpha = progress),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    if (maEndLabel != null) {
                        maDisplay.indexOfLast { it != null }.takeIf { it >= 0 }?.let { lastIdx ->
                            val y = yFor(maDisplay[lastIdx]!!)
                            val labelY = (y - maEndLabel.size.height / 2f)
                                .coerceIn(0f, (priceH - maEndLabel.size.height).coerceAtLeast(0f))
                            drawText(
                                maEndLabel,
                                color = maColor.copy(alpha = progress),
                                topLeft = Offset(plotW + 4.dp.toPx(), labelY),
                            )
                        }
                    }
                }

                // 4) 成交量面条：高度归一化，按当日涨跌着色（减淡），与蜡烛同步浮现
                val maxVol = display.maxOf { it.volume }.coerceAtLeast(1.0)
                display.forEachIndexed { i, bar ->
                    val alpha = alphaAt(i)
                    if (alpha <= 0f) return@forEachIndexed
                    val cx = slot * i + slot / 2f
                    val vh = (bar.volume / maxVol * volH).toFloat()
                    if (vh > 0f) {
                        drawRect(
                            color = (if (bar.close >= bar.open) upColor else downColor)
                                .copy(alpha = 0.45f * alpha),
                            topLeft = Offset(cx - bodyW / 2f, volTop + volH - vh),
                            size = Size(bodyW, vh)
                        )
                    }
                }

                // 5) 首末日期标签（MM-dd，对齐绘图区两端；入场尾声淡入；标签为组合期测量缓存）
                val dateAlpha = ((progress - 0.6f) / 0.4f).coerceIn(0f, 1f)
                if (dateAlpha > 0f) {
                    val firstLabel = measuredDateLabels.first
                    val lastLabel = measuredDateLabels.second
                    val dateColor = dateBaseColor.copy(alpha = dateAlpha)
                    drawText(firstLabel, color = dateColor, topLeft = Offset(0f, dateY))
                    drawText(
                        lastLabel,
                        color = dateColor,
                        topLeft = Offset((plotW - lastLabel.size.width).coerceAtLeast(0f), dateY)
                    )
                }
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

/** 序列末尾最近的非空值（均线标签用）。 */
private fun List<Double?>.lastNotNullOrNull(): Double? =
    asReversed().firstOrNull { it != null }

/** 均线线端标签文本：`"MA250 3.46"`。internal 纯函数，无 Android 依赖，便于单测。 */
internal fun maLineLabelText(label: String, value: Double): String =
    "$label ${MoneyFormatter.amount(value)}"
