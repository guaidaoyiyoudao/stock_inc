package com.stock.dividend.ui.component

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import com.stock.dividend.ui.theme.GlassColors
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries

@Composable
fun IncomeTrendChart(
    yearlyTotals: Map<Int, Double>,
    selectedYear: Int,
    onYearClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (yearlyTotals.size < 2) return

    val years = remember(yearlyTotals) { yearlyTotals.keys.sorted() }
    val amounts = remember(yearlyTotals) { years.map { yearlyTotals[it] ?: 0.0 } }
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(amounts) {
        modelProducer.runTransaction {
            columnSeries { series(*amounts.toTypedArray()) }
        }
    }

    val bottomAxisValueFormatter = remember(years) {
        CartesianValueFormatter { context: CartesianMeasuringContext, value: Double, position: Axis.Position.Vertical? ->
            val index = value.toInt()
            if (index in years.indices) years[index].toString() else ""
        }
    }

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder)
    ) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = bottomAxisValueFormatter
                ),
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .height(180.dp)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            scrollState = rememberVicoScrollState(scrollEnabled = false),
        )
    }
}
