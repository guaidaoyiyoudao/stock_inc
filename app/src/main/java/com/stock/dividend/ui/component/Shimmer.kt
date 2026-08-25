package com.stock.dividend.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 骨架屏占位（借鉴 facebook/shimmer 的模式，Compose 自绘实现，零依赖）。
 *
 * 高光从左向右无限扫过；基色/高光均取 onSurface 低透明度，深浅色主题自适应。
 * 仅用于加载占位，不承载任何数据，不存在数值准确性问题。
 *
 * 性能：同一骨架组（如 [SkeletonCard] 内的多条 [SkeletonLine] + icon）通过
 * [rememberShimmerProgress] 共享**一条**无限动画，各占位在绘制期读取进度——
 * 只触发 draw 失效，不逐帧重组；已 clip 的占位不再重复 clipPath。
 */

/** 骨架组共享的高光进度（-1f..2f 无限左→右扫过）。同一组传同一实例，避免各自起无限动画。 */
@Composable
fun rememberShimmerProgress(): State<Float> {
    val transition = rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )
}

/**
 * 共享进度的 shimmer 高光扫过效果。
 *
 * @param progress 高光进度的绘制期读取（传 [rememberShimmerProgress] 的 `.value` getter），
 *   状态读取发生在 draw 阶段 → 每帧只重绘、不重组。
 * @param shape 需要异形裁剪时传入（内部 clipPath 限定高光）；调用方已 `.clip(shape)`
 *   同形状裁剪时传 null（默认），避免重复 clipPath。
 */
fun Modifier.shimmer(progress: () -> Float, shape: Shape? = null): Modifier = composed {
    val baseColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val highlightColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)

    drawBehind {
        val skeleton: DrawScope.() -> Unit = {
            drawRect(color = baseColor)
            val start = size.width * progress()
            drawRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to baseColor,
                        0.5f to highlightColor,
                        1f to baseColor,
                    ),
                    start = Offset(start, 0f),
                    end = Offset(start + size.width * 0.6f, size.height),
                ),
            )
        }
        if (shape != null) {
            val path = when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                is Outline.Generic -> Path().apply { addPath(outline.path) }
            }
            clipPath(path = path, block = skeleton)
        } else {
            skeleton()
        }
    }
}

/**
 * 单条骨架占位线。
 *
 * @param shimmerProgress 所属骨架组共享的高光进度；缺省独立起一个（散用场景）。
 */
@Composable
fun SkeletonLine(
    widthFraction: Float = 1f,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    shimmerProgress: State<Float>? = null,
) {
    val shape = MaterialTheme.shapes.extraSmall
    val progress = shimmerProgress ?: rememberShimmerProgress()
    Box(
        modifier
            .fillMaxWidth(widthFraction.coerceIn(0.1f, 1f))
            .height(height)
            // 已 clip(shape) 裁剪，shimmer 不再重复 clipPath
            .clip(shape)
            .shimmer(progress = { progress.value }),
    )
}

/**
 * 骨架卡片：外形对齐 [AppCard]（shape.medium + surfaceVariant 底 + 16dp 内边距），
 * 内容为「圆形头像位 + 数条占位线」，加载期间 1:1 顶替真实卡片位置防跳动。
 * 整卡共享一次 shimmer 无限动画。
 */
@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier,
    lines: Int = 3,
    icon: Boolean = true,
) {
    val shimmerProgress = rememberShimmerProgress()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .shimmer(progress = { shimmerProgress.value }),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SkeletonLine(
                widthFraction = if (icon) 0.5f else 0.35f,
                height = 18.dp,
                shimmerProgress = shimmerProgress,
            )
            repeat((lines - 1).coerceAtLeast(1)) { index ->
                SkeletonLine(
                    widthFraction = if (index % 2 == 0) 1f else 0.62f,
                    shimmerProgress = shimmerProgress,
                )
            }
        }
    }
}

/** 骨架卡片列表（默认 3 张），首载/刷新占位用。 */
@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    cardCount: Int = 3,
    lines: Int = 3,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(cardCount) { SkeletonCard(lines = lines) }
    }
}
