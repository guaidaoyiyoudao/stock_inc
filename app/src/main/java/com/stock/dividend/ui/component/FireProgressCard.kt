package com.stock.dividend.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.data.repository.PercentFormatter
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.Motion
import com.stock.dividend.ui.theme.tabularNumberStyle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** 正弦波一个完整周期的相位（2π）。 */
private val TWO_PI = (2 * PI).toFloat()

@Composable
fun FireProgressCard(
    targetAmount: Double?,
    forecastTotal: Double,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = LocalExtendedColors.current
    val coveragePercent = (progress ?: 0f).coerceIn(0f, 999f)
    val coverageProgress = (coveragePercent / 100f).coerceIn(0f, 1f)
    val coveredAmount = (targetAmount ?: 0.0) * coverageProgress
    val gapAmount = ((targetAmount ?: 0.0) - coveredAmount).coerceAtLeast(0.0)
    val achieved = coveragePercent >= 100f
    val cardBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

    if (targetAmount == null || progress == null) {
        AppCard(
            modifier = modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            tone = AppCardTone.Surface,
            border = cardBorder,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "支出",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "生活支出",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "去设置",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }
        }
    } else {
        AppCard(
            modifier = modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            tone = AppCardTone.Surface,
            border = cardBorder,
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
                        text = "生活支出",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PercentText(
                        value = coveragePercent.toDouble(),
                        decimals = 1,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (achieved) ext.positive else MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 水波进度（借鉴 WaveLoading 模式）：波面精确停在 coverageProgress 处
                WaveProgress(
                    progress = coverageProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    color = if (achieved) ext.positive else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "已覆盖 ${MoneyFormatter.compact(coveredAmount)}",
                        style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (gapAmount > 0.0) "差 ${MoneyFormatter.compact(gapAmount)}" else "已覆盖",
                        style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                        color = if (gapAmount > 0.0) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            ext.positive
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "收入",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = MoneyFormatter.compact(forecastTotal),
                            style = MaterialTheme.typography.titleSmall.merge(tabularNumberStyle),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "支出",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = MoneyFormatter.compact(targetAmount),
                            style = MaterialTheme.typography.titleSmall.merge(tabularNumberStyle),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 水波进度条（借鉴 WaveLoading 模式）：**一次性动画**——水位上涨期间两条正弦波错相
 * 水平滚动（入场效果），水位到位后波浪定格、不再滚动（无常驻无限重绘）。
 *
 * 数据准确性：水位落点恒为传入的精确 [progress]；波动仅是入场期间的波面正弦扰动，
 * 不改变水位含义。
 */
@Composable
private fun WaveProgress(
    progress: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    // 一次性水位动画：目标变化时从当前水位继续过渡；波浪滚动作为子协程与上涨并行，
    // 水位 animateTo 返回（到位）后 LaunchedEffect 块结束，子协程随之取消 → phase 定格
    val waterLevel = remember { Animatable(0f) }
    val phase = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        launch {
            while (isActive) {
                phase.animateTo(
                    targetValue = TWO_PI,
                    animationSpec = tween(durationMillis = 2400, easing = Motion.Linear),
                )
                phase.snapTo(0f) // 周期衔接（2π ≡ 0），滚动无缝循环
            }
        }
        waterLevel.animateTo(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(Motion.DurationLong, easing = Motion.EmphasizedDecelerate),
        )
    }
    val dimColor = color.copy(alpha = 0.45f)
    // 复用同一 Path 对象（reset 重画），避免每帧新建
    val wavePath = remember { Path() }

    Canvas(
        modifier = modifier.clip(MaterialTheme.shapes.extraSmall),
    ) {
        drawRect(color = trackColor)

        val surfaceY = size.height * (1f - waterLevel.value)
        val amplitude = 2.5.dp.toPx()
        val wavelength = size.width / 1.6f
        val step = 8.dp.toPx()

        fun drawWave(phaseShift: Float, waveColor: Color) {
            wavePath.reset()
            wavePath.moveTo(-wavelength, surfaceY)
            var x = -wavelength
            while (x <= size.width + step) {
                val y = surfaceY + sin((x + phase.value + phaseShift) / wavelength * 2f * PI.toFloat()) * amplitude
                wavePath.lineTo(x, y)
                x += step
            }
            wavePath.lineTo(size.width, size.height)
            wavePath.lineTo(-wavelength, size.height)
            wavePath.close()
            drawPath(path = wavePath, color = waveColor)
        }

        drawWave(0f, dimColor)
        drawWave(wavelength / 2f, color)
    }
}
