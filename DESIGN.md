# 设计系统（Design System）

> 本文件描述 `stock_inc` 的 UI 设计系统，是所有 Compose 页面/组件的**唯一视觉真相来源**。
> AI agent 在动 UI 前**必读**本文件；`AGENTS.md` §4.5 引用此处。

设计语言：**Clear Sky Finance** —— Ocean Blue（信任）/ Cool Slate（结构）/ Warm Gold（财富）。
参考范本：[Now in Android](https://github.com/android/nowinandroid) `core/designsystem`。

---

## 1. 主题层（`ui/theme/`）

### 1.1 双主题（亮/暗）

- `StockDividendTheme(darkTheme = isSystemInDarkTheme())` 跟随系统。
- 亮色：`LightColorScheme`（`Color.kt`）—— 温润近白背景 `SurfaceBackground = 0xFFFBFCFE`（非纯白）。
- 暗色：`DarkColorScheme` —— 带蓝调近黑背景 `SurfaceBackgroundDark = 0xFF0F1419`（非纯黑，避免 OLED 疲劳）。
- 状态栏/导航栏图标外观随深浅色动态切换（`isAppearanceLightStatusBars = !darkTheme`）。

### 1.2 扩展主题（CompositionLocal，承载 M3 之外的信息）

`Theme.kt` 用 `CompositionLocalProvider` 暴露三个扩展，定义在 `Gradient.kt`：

| Local | 类型 | 用途 |
|---|---|---|
| `LocalGradientColors` | `GradientColors(top, bottom, container)` | 背景渐变色（比纯色高级） |
| `LocalBackgroundTheme` | `BackgroundTheme(color, tonalElevation)` | 背景基色 + 层次 |
| `LocalExtendedColors` | `ExtendedColors(positive, positiveContainer, negative, negativeContainer, neutral)` | **财务语义色（涨/跌）** |

> **财务色读法**：新代码用 `LocalExtendedColors.current.positive` / `.negative`，**不要**裸 `import FinanceGreen/FinanceRed`（那是历史兼容常量，不跟随深浅色）。

### 1.3 字体（Inter 可变字体）

- 单文件 `res/font/inter.ttf`（子集化 latin + 货币符号 + tnum，约 210KB），承载 wght 100-900 全字重。
- `Type.kt` 所有 `TextStyle` 绑定 `InterFontFamily`（通过 `FontVariation` 指定字重）。
- 全部样式应用 `PlatformTextStyle(includeFontPadding = false)`（去 Android 默认字体内边距）+ `LineHeightStyle(Center, None)`（多行对齐稳定）。
- 中文不在子集 → fallback 到系统字体（数字/英文用 Inter，中文用系统，这正是想要的效果）。

### 1.4 等宽数字（tabular figures，金融 App 必备）

```kotlin
import com.stock.dividend.ui.theme.tabularNumberStyle

// 在已有 style 上叠加 tnum（数字等宽，小数点对齐）
Text(style = MaterialTheme.typography.titleMedium.merge(tabularNumberStyle))
```

让 `1` 和 `8` 等宽，金额/百分比列的小数点垂直对齐。**所有金额/百分比展示必须加 tnum。**

### 1.5 形状（`Shape.kt`，勿硬编码）

| 角色 | 圆角 | 用途 |
|---|---|---|
| `extraSmall` | 6dp | Chip、小标签 |
| `small` | 10dp | 按钮、输入框 |
| `medium` | 14dp | **卡片（默认）** |
| `large` | 20dp | 顶层卡片、对话框 |
| `extraLarge` | 28dp | 全屏 sheet |

**禁止**在代码里写 `RoundedCornerShape(14.dp)`，一律走 `MaterialTheme.shapes.medium`。

#### 1.5.1 圆角例外（迁移收尾时按此判断）

| 场景 | 位置 | 处理 |
|---|---|---|
| 聊天气泡不对称角（16/16/4/16） | `AiChatScreen.kt` | token 无法表达不对称角，**保留硬编码** |
| 图表刻度/指示器微小几何（2dp 级） | `BollPriceScale` / `DividendPriceScale` / `ForecastComparisonCard` | 非组件表面，不进 token 刻度，**保留硬编码** |
| 刻度内历史值（6/10/14dp） | 各 Screen / 图表组件 | **必须**替换为 `MaterialTheme.shapes.extraSmall / small / medium` |
| 刻度外历史值（8/12dp） | `EditHoldingScreen` / `AddStockScreen` / `PortfolioEvaluationScreen` / `YearSelector` / `FireGoalSetupScreen` | 迁移期豁免；按使用频率决定补 token 或保留例外（12dp 高频，建议补档） |

> 现状快照（2026-08-01）：`Shape.kt` 外剩余 32 处硬编码 —— 可迁移 11 处（6/10/14dp）、刻度外遗留 14 处（8/12dp）、明确例外 7 处（聊天气泡 3 + 2dp 几何 4）。

---

## 2. 核心组件（`ui/component/AppComponents.kt`）

### 2.1 AppCard（替换裸 `Card`）

```kotlin
AppCard(
    tone = AppCardTone.Surface,  // Surface / List / Summary
    onClick = null,               // null=静态，非null=可点击
) { /* ColumnScope content */ }
```

- shape/colors/elevation 已统一，调用方不再重复写。
- `Summary` 态用 `primaryContainer`（品牌色调），用于汇总/强调卡。

### 2.2 AmountText（金额展示，金融专用）

```kotlin
AmountText(
    value = 12345.67,
    style = MaterialTheme.typography.headlineMedium,  // 默认大额
    showSymbol = true,    // ¥ 符号
    colored = true,       // 自动正负色（走 LocalExtendedColors）
    signed = false,       // true=盈亏场景加 +/-
)
```

- 格式化走 `MoneyFormatter`（千分位 + Locale.US 稳定）。
- 自动 tnum + 右对齐 + 正负色。

### 2.3 PercentText（百分比展示）

```kotlin
PercentText(value = 5.23, decimals = 2, colored = true, signed = true)
```

### 2.4 按钮族

| 组件 | 用途 |
|---|---|
| `AppButton` | 主操作（primary 色） |
| `AppOutlinedButton` | 次要操作（边框） |
| `AppTextButton` | 行内次要（纯文字） |

字号统一 `labelLarge`，颜色统一品牌色。

### 2.5 FinanceMetricRow（横向指标行）

```kotlin
FinanceMetricRow(label = "持仓市值", value = "¥1,234,567.89", valueColor = positive)
```

左标签（bodyMedium + onSurfaceVariant）+ 右值（titleMedium + tnum + 右对齐）。

### 2.6 AppTextField（统一输入框）

```kotlin
AppTextField(
    value = input,
    onValueChange = { input = it },
    label = { Text("股票代码") },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
)
```

- 薄封装 M3 `OutlinedTextField`，唯一差异：**强制 `shape = MaterialTheme.shapes.medium`**（14dp），消除各页输入框圆角不一致。
- 其余参数（`label` / `placeholder` / `leadingIcon` / `trailingIcon` / `supportingText` / `isError` / `keyboardOptions` / `keyboardActions` 等）全部透传，保持灵活性。
- 迁移方式：`OutlinedTextField(...)` → `AppTextField(...)`，删除原 `shape = ...` 参数。

### 2.7 历史组件（`DesignSystem.kt`，保留兼容）

`AppCardDefaults` / `SectionHeader` / `FinanceMetric`（纵向）/ `StatusPill` / `FinanceStatusTone` 继续可用，新代码优先用 §2 的新组件。

---

## 3. 格式化器（`data/repository/Formatters.kt`，纯函数）

**金额/百分比一律走这两个 object，禁止再写私有 `formatXxx`。**

### 3.1 MoneyFormatter

| 方法 | 示例 |
|---|---|
| `amount(1234.5)` | `"1,234.50"` |
| `withSymbol(1234.5)` | `"¥1,234.50"` |
| `withSymbol(-1234.5)` | `"-¥1,234.50"` |
| `withSign(1234.5)` | `"+¥1,234.50"`（零不加 +） |
| `compact(12345.0)` | `"¥1.23万"` |
| `compact(123_456_789.0)` | `"¥1.23亿"` |

全部 `Locale.US`（逗号/小数点不受系统 locale 影响），固定 2 位小数 + 千分位。

### 3.2 PercentFormatter

| 方法 | 示例 |
|---|---|
| `percent(3.456)` | `"3.46%"`（默认 2 位） |
| `percent(3.456, decimals = 1)` | `"3.5%"` |
| `fromRatio(0.0345, decimals = 1)` | `"3.5%"`（自动 ×100） |
| `withSign(3.4)` | `"+3.40%"` |

---

## 4. 迁移指南（从旧代码迁到新组件）

按以下优先级逐文件替换，每改一处编译+跑一次单测：

1. **金额格式化**：搜 `formatAmount` / `formatMoney` / `formatCurrency` / `"%.2f".format`，换成 `MoneyFormatter.xxx`。
2. **百分比格式化**：搜 `"%.2f%%"` / `"%.1f%%"`，换成 `PercentFormatter.xxx`。
3. **裸 `Card`** → `AppCard`（54 处），shape/colors 删掉只留 content。
4. **金额展示** → `AmountText`（替换 `buildAnnotatedString` 手写）。
5. **财务色裸 import** → `LocalExtendedColors.current.positive/negative`。

### 4.1 迁移进度（快照，会过期）

| 任务 | 进度 | 剩余 |
|---|---|---|
| 裸 `Card` → `AppCard` | 54 处已迁 49 处（`AppCard` 调用 44 处） | `EditHoldingScreen` 1、`AddStockScreen` 2、`BackupRestoreScreen` 1、`ExpenseCoverageScreen` 1 |
| 财务色裸 import | 已收敛 | 仅主题层自身定义/默认值 |
| 硬编码圆角 | 见 §1.5.1 | 32 处（可迁移 11 / 遗留 14 / 例外 7） |
| 展示级 `"%.Nf"` | 部分完成 | `DividendRateChart`（tooltip/买线文案）、`PortfolioEvaluationScreen`（汇总文案）、`MarketWidget`、`HoldingRecommender` |
| 私有 `formatXxx` | 展示别名均已委托 Formatters | 合规别名/豁免见 §5.3 |

> 迁移收尾时以 `rg` 实际结果为准，并回填本表。

---

## 5. 红线

1. **不要裸 `import FinanceGreen/FinanceRed`**（新代码）—— 用 `LocalExtendedColors`。（已收敛，仅主题层自身。）
2. **不要硬编码 `RoundedCornerShape(N.dp)`** —— 用 `MaterialTheme.shapes`。**例外**：聊天气泡不对称角、图表 2dp 级刻度几何（§1.5.1）；刻度外历史值（8/12dp）迁移期豁免。
3. **不要写私有 `formatXxx`** —— 用 `MoneyFormatter` / `PercentFormatter`。**豁免**：
   - 一行委托 Formatters 的私有别名（如 `private fun formatMoney(v: Double) = MoneyFormatter.withSymbol(v)`）允许保留，**禁止自实现格式逻辑**；
   - LLM prompt 文本（`LlmPromptBuilder` / `StockLlmPromptBuilder`）允许 `String.format`；
   - 输入框回填/解析值允许 `String.format`（如 `FireGoalViewModel` / `HomeScreen` / `StockDetailScreen` 的输入态），**展示路径仍必须走 Formatters**；
   - 非金额/百分比格式化（时间、月份、股票代码等）不受限。
4. **金额/百分比必须加 tnum** —— `.merge(tabularNumberStyle)` 或用 `AmountText`/`PercentText`。
5. **所有面向用户文本中文**（AGENTS.md §4.5）。
