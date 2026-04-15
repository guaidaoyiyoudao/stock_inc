# Implementation Plan: Dividend Lazy Loading

**Branch**: `003-dividend-lazy-load` | **Date**: 2026-04-15 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/003-dividend-lazy-load/spec.md`

## Summary

将股票详情页的分红记录从一次性全部加载改为按需分页加载：初始只显示最近5年的分红数据，用户向下滑动到列表底部时自动追加更早年份的数据。变更集中在 `StockDetailViewModel`（添加分页状态管理）和 `StockDetailScreen`（LazyColumn 无限滚动），不涉及数据库结构或 API 层修改。

## Technical Context

**Language/Version**: Kotlin 2.0
**Primary Dependencies**: Jetpack Compose, Material Design 3, Hilt, Room, Coroutines + Flow
**Storage**: Room (SQLite)，已有 `dividends` 表，无需修改 schema
**Testing**: JUnit, 手动验证（真机/模拟器）
**Target Platform**: Android (minSDK 24)
**Project Type**: Mobile app (Android)
**Performance Goals**: 初始加载 <1s，追加加载 <2s，60fps 滚动
**Constraints**: 离线可用（所有数据已在本地 Room 中），无网络请求
**Scale/Scope**: 单个股票详情页，典型分红记录 10-30 条

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Modern Android Development | PASS | 使用 Kotlin + Compose + MVVM + Hilt |
| II. 离线优先与数据持久化 | PASS | 所有分红数据已缓存在 Room，无网络请求 |
| III. 数据准确性 | PASS | 不修改原始数据，仅控制显示数量 |
| IV. 简洁与可维护性 | PASS | 最小化变更，不引入新抽象层 |
| V. 用户友好的错误处理 | PASS | 无外部交互，已有数据不会出错 |

所有原则通过，无违规项。

## Project Structure

### Documentation (this feature)

```text
specs/003-dividend-lazy-load/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
app/src/main/java/com/stock/dividend/
├── data/
│   └── local/
│       └── dao/
│           └── DividendDao.kt          # 添加按年份分页查询方法
├── viewmodel/
│   └── StockDetailViewModel.kt         # 添加分页状态和加载更多逻辑
└── ui/
    └── screen/
        └── StockDetailScreen.kt        # LazyColumn 无限滚动支持

app/src/test/java/com/stock/dividend/
└── viewmodel/
    └── StockDetailViewModelTest.kt     # 分页逻辑单元测试（如需要）
```

**Structure Decision**: 在现有项目结构内修改，不创建新目录。仅涉及 ViewModel、DAO 和 Screen 三个文件的核心变更。

## Complexity Tracking

无违规项，不需要记录。
