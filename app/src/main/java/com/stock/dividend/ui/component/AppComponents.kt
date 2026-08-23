package com.stock.dividend.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skydoves.balloon.compose.Balloon
import com.skydoves.balloon.compose.rememberBalloonBuilder
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.data.repository.PercentFormatter
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.Motion
import com.stock.dividend.ui.theme.tabularNumberStyle

// ── AppCard：统一卡片封装 ────────────────────────────────────────────

/**
 * 卡片语义色调。
 *
 * 对应旧 [AppCardDefaults] 的 listCardColors / summaryCardColors，新增 Surface 态。
 * 通过 [AppCard] 的 [tone] 参数选择，消除 54 处裸 `Card` 重复写 shape/colors 的样板。
 */
enum class AppCardTone {
    /** 普通表面卡（containerColor = surface，最常用）。 */
    Surface,

    /** 列表行卡（containerColor = surface，elevation 略低）。 */
    List,

    /** 汇总/强调卡（containerColor = primaryContainer，带品牌色调）。 */
    Summary,
}

/**
 * 统一卡片组件（薄封装 M3 [Card]）。
 *
 * 解决的问题：旧代码 54 处裸用 `Card`，每处重复写
 * `shape = MaterialTheme.shapes.medium` + `CardDefaults.cardColors(...)`。
 * 本组件统一：
 * - shape 走 [MaterialTheme.shapes].medium（与设计系统一致）
 * - colors 按 [tone] 映射
 * - elevation 统一 1dp（旧代码默认 1dp，部分 0dp，本组件标准化）
 *
 * @param tone 卡片语义色调，见 [AppCardTone]。
 * @param containerColor 覆盖 tone 的容器色（用于 errorContainer/tertiaryContainer 等特殊语义，null 走 tone 映射）。
 * @param contentColor 覆盖 tone 的内容色（配合 containerColor 使用）。
 * @param onClick 若提供则渲染为可点击卡片（M3 自动加 ripple），否则为静态卡片。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    tone: AppCardTone = AppCardTone.Surface,
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    border: BorderStroke? = null,
    containerColor: Color? = null,
    contentColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedContainer = containerColor ?: when (tone) {
        AppCardTone.Surface -> MaterialTheme.colorScheme.surface
        AppCardTone.List -> MaterialTheme.colorScheme.surfaceVariant
        AppCardTone.Summary -> MaterialTheme.colorScheme.primaryContainer
    }
    val resolvedContent = contentColor ?: when (tone) {
        AppCardTone.Summary -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val colors = CardDefaults.cardColors(
        containerColor = resolvedContainer,
        contentColor = resolvedContent,
    )
    val shape = MaterialTheme.shapes.medium

    if (onClick != null) {
        val interactionSource = remember { MutableInteractionSource() }
        Card(
            modifier = modifier.pressScale(interactionSource),
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            onClick = onClick,
            interactionSource = interactionSource,
            content = content,
        )
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            content = content,
        )
    }
}

/** 按压缩放反馈（借鉴 ElasticViews 模式）：按下整体缩到 [pressedScale]，松手回弹。 */
@Composable
private fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(Motion.DurationShort, easing = Motion.EmphasizedDecelerate),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

// ── RollingText：数字滚动文本（tab-digit 模式） ──────────────────────

/**
 * 数字滚动文本：文本（已格式化的精确值）变化时新值按涨跌方向滑入，并短暂闪现涨跌色。
 *
 * 数据准确性：组件只消费 formatter 输出的字符串，滚动/闪色仅作用于过渡帧，
 * 动画落点恒为传入的精确文本。
 *
 * @param direction 相对上一次值的方向：1 涨（新值自上滑入）/ -1 跌（自下滑入）/ 0 无变化。
 */
@Composable
private fun RollingText(
    text: String,
    direction: Int,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    if (!animated) {
        Text(
            text = text,
            modifier = modifier,
            color = color,
            style = style,
            textAlign = TextAlign.End,
        )
        return
    }

    val flash = remember { Animatable(0f) }
    LaunchedEffect(text) {
        if (direction != 0) {
            flash.snapTo(1f)
            flash.animateTo(
                targetValue = 0f,
                animationSpec = tween(Motion.DurationMedium, easing = Motion.EmphasizedDecelerate),
            )
        }
    }
    val ext = LocalExtendedColors.current
    val flashColor = if (direction > 0) ext.positive else ext.negative
    val displayColor = lerp(color, flashColor, flash.value * 0.45f)

    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = {
            val rising = direction >= 0
            val enter = slideInVertically(
                animationSpec = tween(Motion.DurationMedium, easing = Motion.EmphasizedDecelerate),
                initialOffsetY = { full -> if (rising) -full / 2 else full / 2 },
            ) + fadeIn(tween(Motion.DurationMedium))
            val exit = slideOutVertically(
                animationSpec = tween(Motion.DurationMedium, easing = Motion.EmphasizedAccelerate),
                targetOffsetY = { full -> if (rising) full / 2 else -full / 2 },
            ) + fadeOut(tween(Motion.DurationMedium))
            ContentTransform(enter, exit, sizeTransform = SizeTransform(clip = false))
        },
        label = "rollingNumber",
    ) { target ->
        Text(
            text = target,
            color = displayColor,
            style = style,
            textAlign = TextAlign.End,
        )
    }
}

/** 记录值方向变化（1 涨 / -1 跌 / 0 无变化），供 [RollingText] 判定滚动方向。 */
@Composable
private fun rememberValueDirection(value: Double): Int {
    val previous = remember { mutableDoubleStateOf(value) }
    val direction = when {
        value > previous.doubleValue -> 1
        value < previous.doubleValue -> -1
        else -> 0
    }
    SideEffect { previous.doubleValue = value }
    return direction
}

// ── AmountText：金额展示（金融专用） ─────────────────────────────────

/**
 * 金额展示组件（金融专用）。
 *
 * 解决的问题：旧代码至少 8 处重复 `buildAnnotatedString { withStyle... }` + 手写格式化，
 * 且数字非等宽（小数点不对齐）、正负色逻辑散落。
 *
 * 本组件统一：
 * - 格式化走 [MoneyFormatter]（千分位 + Locale.US 稳定）
 * - 数字等宽：[tabularNumberStyle]（tnum，小数点垂直对齐）
 * - 正负色：自动走 [LocalExtendedColors]（positive/negative），跟随深浅色
 * - 货币符号：可选（如 `¥1,234.50` 或 `1,234.50`）
 *
 * @param value 金额数值。
 * @param style 文字样式，默认 [MaterialTheme.typography.headlineMedium]（大额展示）。
 * @param showSymbol 是否显示货币符号（默认 true）。
 * @param colored 是否自动着色（正数 positive/负数 negative/零 onSurface），默认 true。
 * @param signed 是否显示正负号（盈亏场景用），默认 false。
 * @param animated 是否启用数字滚动 + 涨跌闪色（高频刷新的列表行可关闭），默认 true。
 * @param color 显式颜色；非空时覆盖 [colored] 自动着色（如达标变绿等场景语义色）。
 */
@Composable
fun AmountText(
    value: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    showSymbol: Boolean = true,
    colored: Boolean = true,
    signed: Boolean = false,
    animated: Boolean = true,
    color: Color? = null,
) {
    val symbol = if (showSymbol) "¥" else ""
    val text = if (signed) {
        MoneyFormatter.withSign(value, symbol = symbol)
    } else {
        MoneyFormatter.withSymbol(value, symbol = symbol)
    }

    val resolvedColor = color ?: if (colored) {
        when {
            value > 0 -> LocalExtendedColors.current.positive
            value < 0 -> LocalExtendedColors.current.negative
            else -> MaterialTheme.colorScheme.onSurface
        }
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    RollingText(
        text = text,
        direction = rememberValueDirection(value),
        color = resolvedColor,
        style = style.merge(tabularNumberStyle),
        modifier = modifier,
        animated = animated,
    )
}

// ── PercentText：百分比展示 ──────────────────────────────────────────

/**
 * 百分比展示组件。
 *
 * @param value 百分比数值（如 `3.45` 表示 3.45%）。
 * @param decimals 小数位，默认 2（股息率），占比/趋势可用 1。
 * @param colored 是否自动着色（正/负），默认 false（百分比未必有涨跌语义）。
 * @param signed 是否显示正负号，默认 false。
 * @param animated 是否启用数字滚动 + 涨跌闪色，默认 true。
 * @param color 显式颜色；非空时覆盖 [colored] 自动着色。
 */
@Composable
fun PercentText(
    value: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    decimals: Int = 2,
    colored: Boolean = false,
    signed: Boolean = false,
    animated: Boolean = true,
    color: Color? = null,
) {
    val text = if (signed) {
        PercentFormatter.withSign(value, decimals = decimals)
    } else {
        PercentFormatter.percent(value, decimals = decimals)
    }

    val resolvedColor = color ?: if (colored) {
        when {
            value > 0 -> LocalExtendedColors.current.positive
            value < 0 -> LocalExtendedColors.current.negative
            else -> MaterialTheme.colorScheme.onSurface
        }
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    RollingText(
        text = text,
        direction = rememberValueDirection(value),
        color = resolvedColor,
        style = style.merge(tabularNumberStyle),
        modifier = modifier,
        animated = animated,
    )
}

// ── AppButton / AppOutlinedButton / AppTextButton ────────────────────
// 两层 API：
//  1. 便捷版：text + 可选 leadingIcon（覆盖 90% 场景）
//  2. 完整版：content: @Composable RowScope.() -> Unit（支持自定义内容，如多 Text/复杂 Icon 组合）

/**
 * 统一主按钮（薄封装 M3 [Button]）。
 *
 * - 统一品牌色（primary / onPrimary）
 * - 文字固定 labelLarge
 * - [leadingIcon] 非空时自动加图标到文字左侧（带标准间距）
 *
 * 复杂内容（多 Text / 自定义布局）用 [content] 重载。
 */
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String,
    leadingIcon: ImageVector? = null,
    containerColor: Color? = null,
    contentColor: Color? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.pressScale(interactionSource),
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor ?: MaterialTheme.colorScheme.primary,
            contentColor = contentColor ?: MaterialTheme.colorScheme.onPrimary,
        ),
        content = { AppButtonContent(text, leadingIcon) },
    )
}

/** AppButton 完整内容重载（自定义 content，如 Icon+多 Text 组合）。 */
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.pressScale(interactionSource),
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        content = content,
    )
}

/**
 * 统一边框按钮（薄封装 M3 [OutlinedButton]）。
 *
 * 用于次要操作（如「取消」「重置」「从截图导入」）。
 */
@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String,
    leadingIcon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.pressScale(interactionSource),
        enabled = enabled,
        interactionSource = interactionSource,
        content = { AppButtonContent(text, leadingIcon) },
    )
}

/** AppOutlinedButton 完整内容重载。 */
@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.pressScale(interactionSource),
        enabled = enabled,
        interactionSource = interactionSource,
        content = content,
    )
}

/**
 * 统一文字按钮（薄封装 M3 [TextButton]）。
 *
 * 用于行内次要操作（如「查看全部」「更多」「估值」）。
 */
@Composable
fun AppTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String,
    leadingIcon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        modifier = modifier.pressScale(interactionSource),
        enabled = enabled,
        interactionSource = interactionSource,
        content = { AppButtonContent(text, leadingIcon) },
    )
}

/** AppTextButton 完整内容重载。 */
@Composable
fun AppTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        modifier = modifier.pressScale(interactionSource),
        enabled = enabled,
        interactionSource = interactionSource,
        content = content,
    )
}

/** 按钮标准内容：可选图标 + 文字（labelLarge），图标与文字间 8dp 间距。 */
@Composable
private fun AppButtonContent(text: String, leadingIcon: ImageVector?) {
    if (leadingIcon != null) {
        Icon(imageVector = leadingIcon, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
    }
    Text(text = text, style = MaterialTheme.typography.labelLarge)
}

// ── FinanceMetricRow：财务指标行（标签 + 值） ────────────────────────

/**
 * 财务指标行（横向：左标签 + 右值）。
 *
 * 旧 [FinanceMetric] 是纵向（标签在上，值在下），本组件是横向变体，
 * 适合卡片内多指标并排展示（如「股息率 5.2%」「市值 ¥12,345」）。
 *
 * @param label 左侧标签。
 * @param value 右侧值（字符串，已格式化）。
 * @param valueColor 值的颜色，默认 onSurface（可用财务语义色）。
 * @param helpText 术语说明；非空时标签旁显示问号图标，点击弹出解释气泡（Balloon）。
 */
@Composable
fun FinanceMetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    helpText: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (helpText != null) {
                HelpTooltipIcon(helpText)
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.merge(tabularNumberStyle),
            color = valueColor,
            textAlign = TextAlign.End,
        )
    }
}

/** 术语帮助图标：图标即锚点，点击在图标下方弹出跟随主题的说明气泡（Balloon 1.6.x API：锚点作为 Balloon 尾lambda 内容）。 */
@Composable
internal fun HelpTooltipIcon(helpText: String) {
    val inverseSurface = MaterialTheme.colorScheme.inverseSurface
    val inverseOnSurface = MaterialTheme.colorScheme.inverseOnSurface
    val bodySmall = MaterialTheme.typography.bodySmall
    val balloonBuilder = rememberBalloonBuilder {
        setArrowSize(10)
        setArrowPosition(0.5f)
        setPadding(12)
        setMarginHorizontal(12)
        setCornerRadius(8f)
        setBackgroundColor(inverseSurface.toArgb())
        setTextColor(inverseOnSurface.toArgb())
    }
    Balloon(
        builder = balloonBuilder,
        balloonContent = {
            Text(
                text = helpText,
                style = bodySmall,
                color = inverseOnSurface,
            )
        },
    ) { balloonWindow ->
        Icon(
            imageVector = Icons.Outlined.HelpOutline,
            contentDescription = "术语说明",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(16.dp)
                .clickable { balloonWindow.showAlignBottom() },
        )
    }
}

// ── AppTextField：统一输入框 ─────────────────────────────────────────

/**
 * 统一输入框（薄封装 M3 [OutlinedTextField]）。
 *
 * 与裸 OutlinedTextField 的唯一差异：**强制 shape = [MaterialTheme.shapes].medium**，
 * 保证全 App 输入框圆角一致（14dp），消除部分调用方写死圆角、部分用默认值的不一致。
 *
 * 其余参数全部透传给 OutlinedTextField，保持灵活性（leadingIcon / supportingText /
 * keyboardOptions 等场景丰富，强封装反而难用）。
 *
 * 用法：把 `OutlinedTextField(...)` 改成 `AppTextField(...)`，删除 `shape = ...` 参数即可。
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation? = null,
    textStyle: androidx.compose.ui.text.TextStyle? = null,
    colors: androidx.compose.material3.TextFieldColors? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation ?: androidx.compose.ui.text.input.VisualTransformation.None,
        textStyle = textStyle ?: androidx.compose.material3.LocalTextStyle.current,
        colors = colors ?: androidx.compose.material3.OutlinedTextFieldDefaults.colors(),
        shape = MaterialTheme.shapes.medium,
    )
}
