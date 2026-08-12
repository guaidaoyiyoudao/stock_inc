# 今日页 UI 优化设计

**日期**：2026-08-12
**状态**：已 approve（preview 对比选定「重做三块视觉」）
**目标**：TodayScreen 当前裸 `AppCard`+`FinanceMetricRow` 堆砌，不符项目设计语言（用户反馈"太丑"）。重做三块视觉，对标 `IncomeSummaryCard` / `StockCard` 等成熟组件。

## 问题诊断（对比 IncomeSummaryCard）

1. **组合表现卡缺主视觉锚点**：市值/盈亏全是平等小行，无突出主数字。项目惯例：`headlineMedium` 大字 + 加粗 `¥`（`buildAnnotatedString`）+ ↑↓ 箭头 + 财务色
2. **盈亏缺 ↑↓ 箭头**：项目用 `↑+1.00%`/`↓-0.50%`，TodayScreen 只有裸正负色
3. **信号每条独立 `AppCard`**：占空间，应紧凑列表密度（对标 `StockCard`）
4. **缺分节头**：项目用 `SectionHeader`，TodayScreen 信号区只有一个 `Text`

## 优化设计（三块）

### ① AI 一句话总结（顶部，Summary tone）
- 加 `SmartToy` 图标 + "AI 今日解读" labelMedium
- 简报 titleMedium
- 保持 briefing=null 时不渲染

### ② 组合表现卡（对标 IncomeSummaryCard）
- `labelMedium` "组合表现" + `onSurfaceVariant`
- `headlineMedium` 大字总市值：`buildAnnotatedString { 加粗 "¥ " + MoneyFormatter.amount(marketValue) }` + `tabularNumberStyle`
- 今日盈亏：`labelSmall` + ↑↓ 箭头 + 财务色（positive/negative）+ `MoneyFormatter.withSign` + `PercentFormatter.withSign`
- 底部 Row：累计盈亏% / 跑赢沪深300pp（紧凑对照，SpaceBetween）
- dataStale 时 error 色"数据可能延迟"

### ③ 信号区（对标 StockCard 紧凑 + StatusPill）
- `SectionHeader` "今日信号(N)"（或空状态"今日无信号，组合平静"）
- 每条信号：`AppCard(tone=List)` 紧凑行——股票名 titleSmall + 信号类型 StatusPill（或 primary 色 title）+ 现价→阈值 bodySmall onSurfaceVariant
- 点击跳详情

## 复用的现有组件 / 模式
- `AppCard` / `AppCardTone`（Surface/Summary/List）
- `MoneyFormatter` / `PercentFormatter`（§4.5）
- `LocalExtendedColors` 财务色
- `buildAnnotatedString` + `tabularNumberStyle`（IncomeSummaryCard 模式）
- `SectionHeader`（分节，含标题+操作）
- `StatusPill`（信号类型标签）

## 不改
- 数据 / 逻辑 / schema（纯渲染层优化，TodayUiState 不变）
- 不新增单测（UI 渲染，靠模拟器视觉验证）

## 验证
模拟器 before/after 截图对比（用户已启动 Pixel_7_API_35）。
