# Implementation Plan: Holdings Dividend Forecast

**Branch**: `002-holdings-dividend-forecast` | **Date**: 2026-04-12 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/002-holdings-dividend-forecast/spec.md`

## Summary

在已有的股票股息追踪应用基础上，新增持仓数量管理和股息收入预测功能。用户可以在添加股票时填写持有股数，
系统根据历史股息数据计算1年/3年/5年平均每股派息金额，乘以持仓数量预测年度股息收入。主页汇总展示所有
股票的预测股息收入总和。股票详情页提供独立的编辑页面修改持仓数量和切换股息率档位（持久化），同时展示
多种情景下的预测收入对比。

## Technical Context

**Language/Version**: Kotlin 2.0
**Primary Dependencies**: Jetpack Compose, Material Design 3, Hilt, Room, Retrofit + OkHttp, Navigation Compose, Coroutines + Flow
**Storage**: Room (Android 本地 SQLite) — 数据库版本从 v1 升级到 v2
**Testing**: JUnit, MockK, kotlinx-coroutines-test
**Target Platform**: Android 7.0+ (Min SDK 24)
**Project Type**: mobile-app (纯客户端,无后端)
**Performance Goals**: 预测收入计算即时完成 (< 1s), 持仓输入 < 5s
**Constraints**: Room 数据库迁移 (v1→v2), 不引入新的外部数据源, 预测基于本地历史数据
**Scale/Scope**: 在现有 3 个 Screen 基础上新增 1 个 Screen (EditHoldingScreen), 修改多个现有文件

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Modern Android Development | PASS | Kotlin + Jetpack Compose + MVVM + Material Design 3, 新增 Compose Screen |
| II. 离线优先与数据持久化 | PASS | 持仓和股息率选择持久化至 Room, 预测计算基于本地缓存数据 |
| III. 数据准确性 | PASS | 不修改东方财富原始数据, 平均值计算为纯算术平均 (spec 明确要求) |
| IV. 简洁与可维护性 | PASS | 在现有 StockEntity 上新增字段 (而非新建 Entity), 最小化架构变动 |
| V. 用户友好的错误处理 | PASS | 无历史数据时展示友好提示, 不足年限时标注实际使用年数 |

**Post-Phase 1 Re-check**: PASS — 扩展现有 Entity + 新增计算逻辑，完全符合宪法原则。

## Project Structure

### Documentation (this feature)

```text
specs/002-holdings-dividend-forecast/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── api.md           # 内部数据流契约
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
app/src/main/java/com/stock/dividend/
├── MainActivity.kt               # 不变
├── StockDividendApp.kt           # 不变
├── di/
│   ├── DatabaseModule.kt         # 修改: Room 迁移 v1→v2
│   └── NetworkModule.kt          # 不变
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt        # 修改: version=2, 新增迁移, 注册新 Entity
│   │   ├── dao/
│   │   │   ├── StockDao.kt       # 修改: 新增 updateHolding/updateYieldPeriod 查询
│   │   │   └── DividendDao.kt    # 修改: 新增 observeByStockForForecast 查询
│   │   └── entity/
│   │       ├── StockEntity.kt    # 修改: 新增 shares/yieldPeriod 字段
│   │       └── DividendEntity.kt # 不变
│   ├── remote/                   # 不变
│   └── repository/
│       ├── StockRepository.kt    # 修改: addStock 支持 shares 参数, 新增 updateHolding
│       └── DividendRepository.kt # 修改: 新增 calculateForecast 方法
├── ui/
│   ├── theme/                    # 不变
│   ├── navigation/
│   │   └── AppNavigation.kt      # 修改: 新增 editHolding 路由
│   ├── screen/
│   │   ├── HomeScreen.kt         # 修改: 展示持仓数+预测收入, 替换汇总为预测汇总
│   │   ├── AddStockScreen.kt     # 修改: 新增持有股数输入框
│   │   ├── StockDetailScreen.kt  # 修改: 展示预测收入, 编辑持仓入口, 多情景对比
│   │   └── EditHoldingScreen.kt  # 新增: 独立的持仓编辑页面
│   └── component/
│       ├── StockCard.kt          # 修改: 展示持仓数量和预测股息收入
│       ├── DividendSummaryCard.kt # 修改: 展示预测股息收入汇总
│       └── EmptyStateView.kt     # 不变
└── viewmodel/
    ├── HomeViewModel.kt          # 修改: 计算预测汇总, 集成持仓+预测数据
    ├── AddStockViewModel.kt      # 修改: addStock 支持 shares 参数
    └── StockDetailViewModel.kt   # 修改: 计算预测, 持仓+股息率状态

app/src/main/res/values/
└── strings.xml                   # 修改: 新增持仓/预测相关中文字符串

app/src/test/java/com/stock/dividend/
├── data/repository/
│   ├── StockRepositoryTest.kt    # 修改: 测试新增方法
│   └── DividendRepositoryTest.kt # 修改: 测试预测计算逻辑
```

**Structure Decision**: 在现有单模块 Android 项目结构上扩展。不新增独立模块，通过修改现有 Entity/DAO/Repository/ViewModel/Screen 实现功能，仅新增一个 EditHoldingScreen。

## Complexity Tracking

> No constitution violations — table intentionally empty.
