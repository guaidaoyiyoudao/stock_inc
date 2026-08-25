package com.stock.dividend.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 通用空状态占位。
 *
 * 缺省（[title]/[subtitle] 均不传）保持持仓引导形态：渐变「+」图标 + 引导文案 + 添加按钮；
 * 传入自定义文案时切换为通用形态（隐藏「+」装饰与按钮，仅展示文案），
 * 供失败日志等非持仓场景复用。
 *
 * @param title 主标题；自定义模式必传其一（缺省形态下为「开始你的股息追踪之旅」）
 * @param subtitle 副标题；仅缺省形态补默认持仓引导文案，自定义模式不传则不显示
 */
@Composable
fun EmptyStateView(
    onAddClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
) {
    // 自定义文案模式：任一传入即启用（「+」装饰与添加按钮是持仓引导专属，不再展示）
    val isCustom = title != null || subtitle != null
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn()
    ) {
        Column(
            modifier = modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!isCustom) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
                    )
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    )
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            if (!isCustom) {
                Spacer(modifier = Modifier.height(28.dp))
            }

            // 主标题：自定义模式只展示传入项；缺省模式用持仓引导文案
            if (!isCustom || title != null) {
                Text(
                    text = title ?: "开始你的股息追踪之旅",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 副标题：同上，按传入情况展示
            if (!isCustom || subtitle != null) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = subtitle ?: "添加关注的股票，自动计算股息收入\n迈向财务自由的第一步",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            if (!isCustom && onAddClick != {}) {
                Spacer(modifier = Modifier.height(24.dp))
                AppButton(
                    onClick = onAddClick,
                    text = "添加股票",
                )
            }
        }
    }
}
