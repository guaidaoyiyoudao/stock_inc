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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.core.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.stock.dividend.data.repository.KlineBar
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.tabularNumberStyle

/**
 * 近 N 日「价量」图：上方成交量柱状（Vico column）+ 上方一行收盘价摘要（最新价/区间高低/区间涨跌）。
 *
 * 价格与成交量量纲差异大（价几十元 vs 量百万手），双 Y 轴在 Vico 里配置繁且易压扁其中一条。
 * 故采用「柱图 + 摘要」折中：成交量柱直观反映活跃度，价格摘要提供绝对水位，留白干净。
 *
 * 数据来自 [KlineRepository.fetchKlines]（前复权 OHLCV）。空数据不渲染图（调用方判空）。
 *
 * @param bars K 线（升序，旧→新），取末 N 根展示
 */
@Composable
fun PriceVolumeChart(
    bars: List<KlineBar>,
    modifier: Modifier = Modifier
) {
    if (bars.isEmpty()) return
    val ext = LocalExtendedColors.current
    val display = remember(bars) { bars.takeLast(VISIBLE_BARS) }
    val volumes = remember(display) { display.map { it.volume } }

    // 区间价格摘要
    val last = display.last()
    val first = display.first()
    val high = display.maxOf { it.high }
    val low = display.minOf { it.low }
    val periodChangePct = runCatching {
        (last.close - first.open) / first.open * 100.0
    }.getOrDefault(0.0)

    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(volumes) {
        modelProducer.runTransaction {
            columnSeries { series(*volumes.toTypedArray()) }
        }
    }
    // X 轴：仅首末两根柱标日期，中间不标，避免拥挤。
    // 标签位置由 ItemPlacer.aligned 控制；Vico 禁止 formatter 返回空串（formatForAxis 会
    // 直接抛 IllegalStateException），所以这里对任何取值都给出非空文本。
    val bottomFormatter = remember(display) {
        CartesianValueFormatter { _: CartesianMeasuringContext, value: Double, _: Axis.Position.Vertical? ->
            formatAxisDateLabel(display, value)
        }
    }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AppCardDefaults.ListPadding)) {
            // 价格摘要行：最新价 + 区间涨跌（红涨绿跌）
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
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Text(
                        text = "区间涨跌",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${if (periodChangePct >= 0) "+" else ""}${"%.2f".format(periodChangePct)}%",
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

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "成交量（手）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberColumnCartesianLayer(),
                    startAxis = VerticalAxis.rememberStart(),
                    bottomAxis = HorizontalAxis.rememberBottom(
                        itemPlacer = HorizontalAxis.ItemPlacer.aligned(
                            spacing = { display.lastIndex.coerceAtLeast(1) },
                            offset = { 0 },
                        ),
                        valueFormatter = bottomFormatter,
                    ),
                ),
                modelProducer = modelProducer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(top = 4.dp),
                scrollState = rememberVicoScrollState(scrollEnabled = false),
            )
        }
    }
}

private const val VISIBLE_BARS = 30

/**
 * X 轴首末日期标签：按柱下标取 `MM-dd`。
 *
 * Vico 的 `CartesianValueFormatter.formatForAxis` 对空串直接抛 `IllegalStateException`
 * （详见其 `check(it.isNotEmpty())`），因此越界/缺失日期一律回退到非空占位符，绝不返回空串。
 * 纯函数，无 Android 依赖，便于单测（与 StockCardTest 同目录约定）。
 *
 * @param bars 展示中的 K 线列表（升序）；[x] 为 Vico 柱下标（0..lastIndex）。
 */
internal fun formatAxisDateLabel(bars: List<KlineBar>, x: Double): String =
    bars.getOrNull(x.toInt())?.date?.substring(5) ?: "—"
