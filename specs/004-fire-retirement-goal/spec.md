# Feature Specification: FIRE Retirement Goal Progress

**Feature Branch**: `004-fire-retirement-goal`
**Created**: 2026-04-16
**Status**: Draft
**Input**: User description: "支持设置FIRE退休目标进度，主页显示离退休目标金额的进度"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Set FIRE Retirement Target Amount (Priority: P1)

用户首次打开应用或尚未设置退休目标时，主页顶部显示一个引导入口（如"FIRE 目标"卡片），提示用户设置退休目标金额。用户点击后进入设置页面，输入期望的退休储蓄目标金额（如 500 万），确认后保存。

**Why this priority**: 这是整个功能的基础——没有目标金额就无法计算和展示进度。设置入口和保存逻辑是 MVP 的核心。

**Independent Test**: 可以通过点击主页的 FIRE 入口，输入一个目标金额并确认，验证金额被正确保存来独立测试。

**Acceptance Scenarios**:

1. **Given** 用户尚未设置 FIRE 目标金额, **When** 用户在主页看到 FIRE 卡片并点击, **Then** 进入目标设置页面，可输入目标金额
2. **Given** 用户在目标设置页面输入了 5,000,000, **When** 用户点击确认, **Then** 目标金额被保存，返回主页
3. **Given** 用户在目标设置页面, **When** 用户未输入金额直接确认, **Then** 提示用户必须输入有效金额
4. **Given** 用户输入了负数或零, **When** 用户点击确认, **Then** 提示金额必须大于零

---

### User Story 2 - View FIRE Progress on Home Page (Priority: P1)

用户在主页顶部可以看到 FIRE 退休进度卡片，显示当前预期年股息收入占目标金额的百分比进度。进度以线性进度条呈现，并显示具体金额对比（预期年股息收入 / 目标金额）。

**Why this priority**: 这是用户核心需求——在主页一眼看到自己距离退休目标还有多远。与设置目标并列为 P1，因为展示进度是功能的主要价值。

**Independent Test**: 可以通过设置一个目标金额，确认主页显示的进度百分比与当前预期年股息收入一致来独立测试。

**Acceptance Scenarios**:

1. **Given** 用户已设置 FIRE 目标为 200,000（年）且当前预期年股息收入为 50,000, **When** 用户查看主页 FIRE 卡片, **Then** 显示进度为 25%，并展示"¥50,000 / ¥200,000"
2. **Given** 用户尚未设置目标金额, **When** 用户查看主页, **Then** 显示引导设置的入口，不显示进度
3. **Given** 用户预期年股息收入为 0, **When** 用户查看主页 FIRE 卡片, **Then** 显示进度为 0%
4. **Given** 用户当前预期年股息收入已超过目标金额, **When** 用户查看主页 FIRE 卡片, **Then** 显示进度为 100%，并给出达标提示

---

### User Story 3 - Modify FIRE Target Amount (Priority: P2)

用户可以随时修改已设置的 FIRE 目标金额。通过点击主页 FIRE 卡片或进入设置页面，用户可以查看当前目标并修改为新金额。

**Why this priority**: 目标金额不是一成不变的，允许修改保证了功能的持续可用性，但优先级低于设置和展示。

**Independent Test**: 可以通过修改已设置的目标金额，验证主页进度更新来独立测试。

**Acceptance Scenarios**:

1. **Given** 用户已设置目标为 5,000,000, **When** 用户点击 FIRE 卡片进入设置页, **Then** 设置页显示当前目标金额 5,000,000
2. **Given** 用户在设置页将目标修改为 8,000,000 并确认, **When** 用户返回主页, **Then** FIRE 进度根据新目标重新计算显示
3. **Given** 用户已设置目标为 5,000,000, **When** 用户在设置页选择删除目标并确认, **Then** 目标被清除，主页恢复显示设置引导入口

---

### Edge Cases

- 用户未添加任何持仓时，FIRE 进度显示为 0%，但目标金额已保存
- 持仓的预期股息数据因行情或分红方案变动时，FIRE 进度应随之更新
- 目标金额非常大（如超过 10 亿）时，金额显示格式需合理（如使用"亿"单位）
- 用户清除应用数据后，FIRE 目标需重新设置
- 用户主动删除目标时，需确认操作以防误删

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统必须允许用户设置一个 FIRE 退休目标金额，以人民币为单位
- **FR-002**: 目标金额必须为正数，系统需验证输入有效性
- **FR-003**: 系统必须在主页顶部显示 FIRE 进度卡片，展示当前预期年股息收入占目标金额的百分比
- **FR-004**: FIRE 进度卡片必须同时显示预期年股息收入和目标金额的具体数值
- **FR-005**: 系统必须以线性进度条（linear progress bar）呈现进度
- **FR-006**: 当用户尚未设置目标时，FIRE 卡片区域显示设置引导入口
- **FR-007**: 用户必须能够随时修改已设置的 FIRE 目标金额
- **FR-007a**: 用户必须能够删除已设置的 FIRE 目标金额，删除后主页恢复显示设置引导入口
- **FR-008**: 进度达到或超过 100% 时，系统给出视觉上的达标提示
- **FR-009**: FIRE 目标金额必须持久化存储，应用重启后保留
- **FR-010**: 预期年股息收入由系统根据用户已有持仓的股息数据自动计算（基于各持仓的最新年度股息 × 持有股数求和），用户无需手动输入

### Key Entities

- **FireGoal**: FIRE 退休目标实体，包含目标年支出金额、创建时间、最后修改时间。每用户仅一条记录。
- **FireProgress**: FIRE 进度视图状态，包含预期年股息收入、目标金额、完成百分比。由系统实时计算，不持久化。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 用户可以在 30 秒内完成 FIRE 目标金额的设置
- **SC-002**: 主页打开后 FIRE 进度卡片在 1 秒内呈现
- **SC-003**: 修改目标金额后，主页进度在返回后立即更新
- **SC-004**: 进度百分比与实际预期年股息收入/目标金额的计算结果完全一致

## Clarifications

### Session 2026-04-16

- Q: Should users be able to delete/clear an already-set FIRE target? → A: Yes, users can delete the target and start over
- Q: Does "current portfolio value" include only stock holdings or also accumulated dividends? → A: Progress calculation is based on expected annual dividend income / target amount (NOT market value)
- Q: What visualization style for FIRE progress display? → A: Linear progress bar

## Assumptions

- 当前持仓总市值已在应用中可获取（来自现有持仓数据汇总）
- 预期年股息收入 = 各持仓的最新年度每股股息 × 持有股数，对所有持仓求和
- 金额以人民币（CNY）为默认货币单位
- 目标金额精度支持到分（小数点后两位）
- 主页已有足够空间容纳 FIRE 进度卡片，不影响现有功能布局
- FIRE 目标金额为单一数值，不支持多阶段目标
- 用户为单一用户场景（无多账户/多用户需求）
