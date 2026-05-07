# Implementation Plan: Dividend Rate Chart

**Branch**: `007-dividend-rate-chart` | **Date**: 2026-05-07 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/007-dividend-rate-chart/spec.md`

## Summary

在股票详情页的分红记录上方新增分红率折线图，使用已有 `DividendEntity.dividendYield` 作为数据源，将有效分红率记录按报告期年份聚合、同一年多次分红求和后，按年份升序绘制成趋势线。变更集中在 `StockDetailViewModel` 的展示模型派生、`StockDetailScreen` 的分红区布局，以及新增可复用 Compose 图表组件；不修改 Room schema、不改变东方财富原始数据、不新增网络接口。

## Technical Context

**Language/Version**: Kotlin 2.0.21, Java 17 toolchain  
**Primary Dependencies**: Jetpack Compose, Material Design 3, Hilt, Room, Coroutines + Flow, MPAndroidChart for the dividend rate chart; existing Vico remains used by the income trend chart  
**Storage**: Room (SQLite), existing `dividends` table; no schema change required  
**Testing**: JUnit, Truth, MockK, Coroutines Test; manual verification on emulator/device  
**Target Platform**: Android mobile app, minSDK 24, targetSDK 35  
**Project Type**: Mobile app (single Android app module)  
**Performance Goals**: Stock detail screen remains responsive at 60fps for typical dividend history (10-30 records); chart renders from cached local data without additional network wait  
**Constraints**: Offline-first; must preserve source dividend values; Chinese UI text; Material Design 3; dark theme compatible  
**Scale/Scope**: One stock detail screen and dividend section, using existing cached dividend history for a single stock at a time

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Modern Android Development | PASS | Uses Kotlin, Jetpack Compose, MVVM/ViewModel, existing single Activity app structure |
| II. 离线优先与数据持久化 | PASS | Chart derives from cached Room dividend data; no new network dependency |
| III. 数据准确性 | PASS | Uses stored `dividendYield` values as-is except display formatting as percent |
| IV. 简洁与可维护性 | PASS | Adds a small chart component and derived UI model; no new architecture layer |
| V. 用户友好的错误处理 | PASS | Handles no/insufficient/partial dividend yield data with Chinese fallback text |

All gates pass. No justified violations required.

## Project Structure

### Documentation (this feature)

```text
specs/007-dividend-rate-chart/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── ui-contract.md
└── tasks.md              # Phase 2 output from /speckit.tasks
```

### Source Code (repository root)

```text
app/src/main/java/com/stock/dividend/
├── data/
│   └── local/
│       └── entity/
│           └── DividendEntity.kt          # Existing source fields; unchanged
├── viewmodel/
│   └── StockDetailViewModel.kt            # Derive chart-ready annual dividend rate points
└── ui/
    ├── component/
    │   └── DividendRateChart.kt           # New reusable line chart component
    └── screen/
        └── StockDetailScreen.kt           # Place chart/fallback in dividend section

app/src/test/java/com/stock/dividend/
└── viewmodel/
    └── StockDetailViewModelTest.kt        # Unit coverage for chart point derivation/fallback inputs
```

**Structure Decision**: Keep implementation inside the existing Android app module. Add one focused Compose component for the line chart, derive chart data in `StockDetailViewModel`, and reuse existing `DividendEntity.dividendYield` without schema or repository changes.

## Complexity Tracking

No constitution violations. No added complexity exceptions.

## Phase 0: Research

Research completed in [research.md](./research.md). Key decisions:

- Use existing `DividendEntity.dividendYield` as the authoritative dividend rate source.
- Derive annual chart points in `StockDetailViewModel` so UI receives display-ready, sorted, same-year-summed data.
- Use MPAndroidChart for the dividend rate chart after visual review; keep existing Vico usage unchanged for other charts.
- Treat fewer than two valid yield records as insufficient for a trend and show an explanatory fallback.

## Phase 1: Design & Contracts

Design artifacts completed:

- [data-model.md](./data-model.md)
- [contracts/ui-contract.md](./contracts/ui-contract.md)
- [quickstart.md](./quickstart.md)

## Post-Design Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Modern Android Development | PASS | Design remains Kotlin + Compose and follows current ViewModel state pattern |
| II. 离线优先与数据持久化 | PASS | Data flow uses existing Room-backed repository observation |
| III. 数据准确性 | PASS | No recalculation of dividend rate beyond sorting/filtering invalid values |
| IV. 简洁与可维护性 | PASS | Small component plus UI state extension; no extra dependency or schema change |
| V. 用户友好的错误处理 | PASS | Empty and insufficient states are explicit and non-technical |

All gates still pass after design.
