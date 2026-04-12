# Implementation Plan: Stock Dividend Tracker

**Branch**: `001-stock-dividend-tracker` | **Date**: 2026-04-12 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-stock-dividend-tracker/spec.md`

## Summary

构建一个纯 Android 应用,允许用户手动录入股票名称,通过东方财富公开 HTTP API 直接查询
股息数据,在主页展示所有关注股票的股息收入总和,并以列表形式展示每只股票的逐年股息收入明细。
无后端服务器,Android 应用通过 Retrofit 直接调用东方财富 API。

## Technical Context

**Language/Version**: Kotlin 2.0
**Primary Dependencies**: Jetpack Compose, Material Design 3, Hilt, Room, Retrofit + OkHttp,
Navigation Compose
**Storage**: Room (Android 本地 SQLite)
**Testing**: JUnit, Android Instrumentation Tests, MockWebServer
**Target Platform**: Android 7.0+ (Min SDK 24)
**Project Type**: mobile-app (纯客户端,无后端)
**Performance Goals**: 股票搜索 < 2s, 股息数据加载 < 3s, 汇总计算即时完成
**Constraints**: 离线可浏览缓存数据, 东方财富 API 为非官方公开接口, 金额单位为"每10股"
**Scale/Scope**: 50+ 关注股票, 3个 Screen, 2个外部 API endpoint

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Modern Android Development | PASS | Kotlin + Jetpack Compose + MVVM + Material Design 3 |
| II. 离线优先与数据持久化 | PASS | Room 本地缓存, 网络仅用于刷新/新增数据 |
| III. 数据准确性 | PASS | 直接透传东方财富原始数据, 仅做单位换算 (每10股→每股) |
| IV. 简洁与可维护性 | PASS | 纯客户端架构, 无后端, Hilt 仅用于核心依赖 |
| V. 用户友好的错误处理 | PASS | 网络错误展示缓存+重试, 无技术性错误信息 |

**Post-Phase 1 Re-check**: PASS — 纯客户端架构更简洁, 完全符合宪法原则。

## Project Structure

### Documentation (this feature)

```text
specs/001-stock-dividend-tracker/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── api.md           # 外部 API contracts
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
app/                              # Android 主模块
├── src/main/
│   ├── java/com/stock/dividend/
│   │   ├── MainActivity.kt           # 单 Activity 入口 + NavHost
│   │   ├── StockDividendApp.kt       # Application 类 (Hilt 入口)
│   │   ├── di/                       # Hilt 依赖注入模块
│   │   │   ├── DatabaseModule.kt     # Room 数据库提供
│   │   │   └── NetworkModule.kt      # Retrofit 实例提供
│   │   ├── data/
│   │   │   ├── local/
│   │   │   │   ├── AppDatabase.kt    # Room 数据库定义
│   │   │   │   ├── dao/
│   │   │   │   │   ├── StockDao.kt
│   │   │   │   │   └── DividendDao.kt
│   │   │   │   └── entity/
│   │   │   │       ├── StockEntity.kt
│   │   │   │       └── DividendEntity.kt
│   │   │   ├── remote/
│   │   │   │   ├── EastMoneyApi.kt   # Retrofit 接口 (东方财富)
│   │   │   │   └── dto/
│   │   │   │       ├── StockSearchResponse.kt
│   │   │   │       └── DividendResponse.kt
│   │   │   └── repository/
│   │   │       ├── StockRepository.kt
│   │   │       └── DividendRepository.kt
│   │   ├── ui/
│   │   │   ├── theme/                # Material Design 3 主题
│   │   │   │   ├── Theme.kt
│   │   │   │   ├── Color.kt
│   │   │   │   └── Type.kt
│   │   │   ├── navigation/
│   │   │   │   └── AppNavigation.kt  # 导航图定义
│   │   │   ├── screen/
│   │   │   │   ├── HomeScreen.kt     # 主页: 汇总 + 股票列表
│   │   │   │   ├── AddStockScreen.kt # 搜索添加股票
│   │   │   │   └── StockDetailScreen.kt # 股票详情 (逐年股息)
│   │   │   └── component/
│   │   │       ├── DividendSummaryCard.kt  # 汇总卡片
│   │   │       ├── StockCard.kt           # 股票列表项
│   │   │       └── EmptyStateView.kt      # 空状态提示
│   │   └── viewmodel/
│   │       ├── HomeViewModel.kt
│   │       ├── AddStockViewModel.kt
│   │       └── StockDetailViewModel.kt
│   └── res/
│       ├── values/
│       │   └── strings.xml           # 中文字符串资源
│       └── values-night/             # 深色模式资源
├── build.gradle.kts
build.gradle.kts                      # 项目级构建配置
settings.gradle.kts
gradle/
└── libs.versions.toml                # Version Catalog
```

**Structure Decision**: 采用单模块 Android 项目结构。无后端服务器,应用通过 Retrofit
直接调用东方财富公开 HTTP API 获取股票搜索和股息数据。

## Complexity Tracking

> No constitution violations — table intentionally empty.
