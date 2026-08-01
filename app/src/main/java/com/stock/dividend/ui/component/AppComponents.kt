package com.stock.dividend.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.data.repository.PercentFormatter
import com.stock.dividend.ui.theme.LocalExtendedColors
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
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            onClick = onClick,
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
 */
@Composable
fun AmountText(
    value: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    showSymbol: Boolean = true,
    colored: Boolean = true,
    signed: Boolean = false,
) {
    val symbol = if (showSymbol) "¥" else ""
    val text = if (signed) {
        MoneyFormatter.withSign(value, symbol = symbol)
    } else {
        MoneyFormatter.withSymbol(value, symbol = symbol)
    }

    val color = if (colored) {
        when {
            value > 0 -> LocalExtendedColors.current.positive
            value < 0 -> LocalExtendedColors.current.negative
            else -> MaterialTheme.colorScheme.onSurface
        }
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Text(
        text = text,
        modifier = modifier,
        style = style.merge(tabularNumberStyle),
        color = color,
        textAlign = TextAlign.End,
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
 */
@Composable
fun PercentText(
    value: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    decimals: Int = 2,
    colored: Boolean = false,
    signed: Boolean = false,
) {
    val text = if (signed) {
        PercentFormatter.withSign(value, decimals = decimals)
    } else {
        PercentFormatter.percent(value, decimals = decimals)
    }

    val color = if (colored) {
        when {
            value > 0 -> LocalExtendedColors.current.positive
            value < 0 -> LocalExtendedColors.current.negative
            else -> MaterialTheme.colorScheme.onSurface
        }
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Text(
        text = text,
        modifier = modifier,
        style = style.merge(tabularNumberStyle),
        color = color,
        textAlign = TextAlign.End,
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
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
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
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
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
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
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
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
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
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
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
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
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
 */
@Composable
fun FinanceMetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.merge(tabularNumberStyle),
            color = valueColor,
            textAlign = TextAlign.End,
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
