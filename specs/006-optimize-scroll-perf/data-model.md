# Data Model: Optimize Scroll Performance

**Date**: 2026-04-30
**Feature**: 006-optimize-scroll-perf

## State Flow Architecture

### Current Architecture (Before)

```
HomeViewModel:
┌─────────────┐   ┌──────────────────┐   ┌──────────────┐
│ fireGoalFlow│   │ stocksFlow       │   │ refreshTrigger│
│ (Room Flow) │   │ (Room Flow)      │   │ (SharedFlow)  │
└──────┬──────┘   └────────┬─────────┘   └──────┬───────┘
       │                   │                     │
       │          ┌────────▼────────┐            │
       │          │ flatMapLatest   │            │
       │          │ (per-stock      │            │
       │          │  dividend obs)  │            │
       │          └────────┬────────┘            │
       │                   │                     │
       ▼                   ▼                     ▼
  Coroutine 1         Coroutine 2          Coroutine 3
  (fireGoal +         (stocks +            (quote fetch +
   fireProgress)       forecasts +          price enrichment)
                        fireProgress)         + marketValue)
       │                   │                     │
       └───────┬───────────┴─────────────────────┘
               ▼
        _uiState (MutableStateFlow)
        ← 2-3 emissions per data change →
```

### Proposed Architecture (After)

```
HomeViewModel:
┌─────────────┐
│ fireGoalFlow│
│ (Room Flow) │──────┐
└─────────────┘      │
                     │
┌─────────────┐      │     ┌──────────────────┐
│ stocksFlow  │──────┼─────│ combine()        │
│ (Room Flow) │      │     │ single emission  │
└─────────────┘      │     │ per data change  │
                     │     └────────┬─────────┘
┌─────────────┐      │              │
│ forecastMap │──────┘              │
│ (derived)   │                     ▼
└─────────────┘              _uiState
                             ← 1 emission →
                                     │
┌─────────────┐                      │
│ quote prices │──── mapValues ──────┘
│ (separate   │   (enrich existing
│  StateFlow) │    forecasts inline)
└─────────────┘
```

### StockDetailViewModel State Flow

```
Current:
┌──────────┐     ┌──────────────┐
│ stock    │─────│ recalculate  │ (emission 1)
│ (Room)   │     │              │
└──────────┘     └──────────────┘
┌──────────┐     ┌──────────────┐
│ dividends│─────│ recalculate  │ (emission 2)
│ (Room)   │     │              │
└──────────┘     └──────────────┘

Proposed:
┌──────────┐     ┌──────────────┐
│ stock    │──┐  │              │
│ (Room)   │  ├──│ combine() +  │──▶ _uiState (1 emission)
└──────────┘  │  │ recalculate  │
┌──────────┐  │  │              │
│ dividends│──┘  └──────────────┘
│ (Room)   │
└──────────┘
```

## Data Classes (No Schema Changes)

All entity and state classes remain structurally identical. Only stability annotations are added.

### Annotated Classes

| Class | Annotation | Location |
|-------|-----------|----------|
| `HomeUiState` | `@Stable` | HomeViewModel.kt |
| `StockDetailUiState` | `@Stable` | StockDetailViewModel.kt |
| `StockEntity` | `@Stable` | entity/StockEntity.kt |
| `DividendEntity` | `@Stable` | entity/DividendEntity.kt |
| `StockForecast` | `@Stable` | HomeViewModel.kt |
| `ForecastDetail` | `@Stable` | StockDetailViewModel.kt |

## Validation Rules

No new validation rules. All existing data constraints preserved.

## State Transitions

### HomeUiState Transition (Consolidated)

```
[any source change]
    │
    ├── stocks updated? ─── recompute forecasts ──┐
    ├── dividends updated? ── recompute forecasts ──┤
    ├── FIRE goal updated? ── recompute progress ──┤
    ├── quotes fetched? ─── enrich with prices ────┤
    │                                              │
    └──────────── combine into single state ───────┘
                         │
                    single emission
                    to _uiState
```
