# Dividend Income Timeline Feature Design

**Date:** 2026-05-01
**Branch:** 006-optimize-scroll-perf
**Priority:** First iteration of the "motivation maintenance" feature set

## Problem

Users who buy dividend stocks for long-term compounding easily lose sight of their progress. The app currently shows *forecasted* income but has no way to record or review *actual* dividend income received over time. Without a tangible history of real income, users lack the positive feedback loop needed to stay committed to their compounding strategy.

## Goal

Build a dividend income timeline that automatically estimates historical income from existing data and lets users manually correct or add records. This timeline serves as the data foundation for future visualization and achievement features.

## Design Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| Record source | Hybrid: auto-generated + manual | Auto gives zero-cost baseline; manual covers corrections and external sources |
| UI location | New tab in HomeScreen | Keeps the feature front-and-center without adding navigation depth |
| Auto record generation | On data load, diff-based, coroutine-scoped (`viewModelScope`) | Non-blocking, only generates missing records; runs off main thread |
| Auto record updates | Never auto-update existing | Current shares may differ from historical; first generation is the best estimate |
| Correction model | In-place mutation (auto -> manual) | Correction changes `source` on the existing record; ID prefix is decorative and set at creation, never changed |
| Manual record deletion | Allowed for manual, blocked for auto | Auto records regenerate on next load; blocking deletion avoids confusion |
| Stock deletion | CASCADE deletes all income records | Acceptable: if user removes a stock, its income history is no longer relevant |

---

## 1. Data Layer

### 1.1 New Room Table: `dividend_income_records`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | TEXT | PRIMARY KEY | `auto_{stockCode}_{exDividendDate}` or `manual_{epochMillis}` |
| `stockCode` | TEXT | FK -> stocks(code) ON DELETE CASCADE, NULLABLE | NULL for external income sources |
| `year` | INTEGER | NOT NULL | Income attribution year |
| `date` | TEXT | NOT NULL | Full date for display and sorting (ISO-8601: `yyyy-MM-dd`). For auto records: `exDividendDate`; for manual records: user-selected date |
| `amount` | REAL | NOT NULL | Actual received amount in yuan |
| `exDividendDate` | TEXT | NULLABLE | Original ex-dividend date (set for auto records, null for manual) |
| `source` | TEXT | NOT NULL, DEFAULT 'auto' | `auto` or `manual` |
| `note` | TEXT | NULLABLE | Optional user note |
| `createdAt` | INTEGER | NOT NULL | Record creation timestamp (epoch millis) |
| `updatedAt` | INTEGER | NOT NULL | Last modification timestamp (epoch millis), equals `createdAt` on creation |

**ID format details:**
- Auto records: `auto_{stockCode}_{exDividendDate}` (e.g. `auto_sh.600000_2024-07-10`). The prefix is set at creation and never changes, even if `source` is later mutated to `manual` via correction.
- Manual records: `manual_{epochMillis}` (e.g. `manual_1718000000000`). Epoch millis ensures uniqueness and sortability.

**Index:** `index_dividend_income_records_year` on `year`
**Index:** `index_dividend_income_records_stock_code` on `stockCode`

### 1.2 Data Display Logic

When loading income records for display:

1. Fetch all records for the selected year, sorted by `date` descending
2. Each record is displayed as-is — there is no merge/dedup logic
3. Auto records that have been corrected have `source='manual'` and show the green "实际" indicator
4. Manual records with no stock association (`stockCode` is null) display as "其他收入"

### 1.3 Auto Record Generation

Triggered in `DividendIncomeRepository` when HomeScreen loads income data:

1. Query all dividends with non-null `exDividendDate`
2. Query all existing auto records
3. Diff: find dividends with no corresponding auto record
4. For each missing dividend: generate `amount = cashPerShare * stock.shares`
5. Batch insert new auto records

**Edge cases:**
- If `stock.shares == 0`, still generate the record with `amount = 0.0` (user may add shares later; the record exists as a marker)
- If a dividend's `exDividendDate` is null, skip it (can't determine attribution year)
- Multiple dividends per stock per year (e.g. mid-year + year-end): handled naturally because the ID includes `exDividendDate`, producing distinct records
- Year attribution uses `exDividendDate`. For Chinese A-shares where ex-date and payment date may span year boundaries (e.g. ex-date in Dec, payment in Jan), the record attributes to the ex-date's year. This is acceptable for this iteration

### 1.4 Database Migration

Version 4 -> 5:

```sql
CREATE TABLE IF NOT EXISTS `dividend_income_records` (
    `id` TEXT NOT NULL PRIMARY KEY,
    `stockCode` TEXT NULLABLE,
    `year` INTEGER NOT NULL,
    `date` TEXT NOT NULL,
    `amount` REAL NOT NULL,
    `exDividendDate` TEXT NULLABLE,
    `source` TEXT NOT NULL DEFAULT 'auto',
    `note` TEXT NULLABLE,
    `createdAt` INTEGER NOT NULL,
    `updatedAt` INTEGER NOT NULL,
    FOREIGN KEY (`stockCode`) REFERENCES `stocks`(`code`) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS `index_dividend_income_records_year`
    ON `dividend_income_records`(`year`);

CREATE INDEX IF NOT EXISTS `index_dividend_income_records_stock_code`
    ON `dividend_income_records`(`stockCode`);
```

---

## 2. UI Design

### 2.1 HomeScreen Tab Layout

HomeScreen gains a top tab row with two tabs:

- **Tab 1: "关注列表"** — existing stock list (current HomeScreen content)
- **Tab 2: "股息收入"** — new income timeline

Default tab is "关注列表". Tab state persists during the session (not across app restarts).

### 2.2 "股息收入" Tab Content

Layout (top to bottom):

#### Year Selector

Horizontal scrolling year chips. Shows years that have income records, plus the current year. Default: current year. Selected year highlighted with primary color.

#### Income Summary Card

- Title: "{year}年股息收入"
- Large amount display (yuan, same formatting as DividendSummaryCard)
- Year-over-year comparison: "较去年 ↑{percentage}" or "较去年 ↓{percentage}" or "首年记录"
- Source breakdown: "{X} 笔实际记录 / {Y} 笔推算"

#### Income Timeline List

Scrollable list, sorted by date descending within the selected year.

Each `IncomeTimelineCard`:

**Collapsed view (default):**
- Left: date (MM-dd format), stock name or "其他收入"
- Right: amount in yuan
- Source indicator: green "实际" chip for manual, gray "推算" chip for auto

**Expanded view (on tap):**
- Date (full `date` field)
- Ex-dividend date (if available, from `exDividendDate` field)
- Per-share dividend amount (looked up from the original `dividends` table by matching `stockCode` + `exDividendDate`; shows "—" if source data no longer exists, e.g. stock was removed)
- Shares held at generation time (looked up from the original `dividends` table for context; shows "—" if unavailable)
- Note (if any)
- Actions:
  - "修正金额" button (for `source=auto` records) — opens edit dialog
  - "编辑" button (for `source=manual` records) — opens edit dialog
  - "删除" button (for `source=manual` records only; auto records cannot be deleted)

**Empty state:** "暂无股息收入记录" with subtitle "分红到账后会自动记录"

#### Add Income Button

Bottom FAB or inline button: "添加收入". Opens a dialog/form with:
- Date picker (default: today)
- Amount input (yuan)
- Optional stock selector (dropdown from watchlist, or "无关联股票")
- Optional note text field

---

## 3. Interaction Flows

### 3.1 Auto Record Generation Flow

```
HomeScreen loads "股息收入" tab
  -> DividendIncomeViewModel.init()
    -> DividendIncomeRepository.generateMissingAutoRecords()
      -> Diff dividends vs existing auto records
      -> Insert missing records
    -> Emit combined records for selected year
```

### 3.2 Manual Correction Flow

Corrections mutate the existing auto record in-place, changing its `source` to `manual`. The record ID stays the same (e.g. `auto_sh.600000_2024-07-10` even though `source` is now `manual`). No separate manual record is created.

```
User taps auto record -> Expanded view
  -> User taps "修正金额"
    -> Dialog: amount input (pre-filled with auto amount), note input
    -> User edits and confirms
      -> UPDATE record: source='manual', amount=user input, note=user input, updatedAt=now()
      -> Timeline refreshes
```

### 3.3 Manual Add Flow

```
User taps "添加收入"
  -> Dialog: date picker, amount, optional stock, optional note
  -> User fills and confirms
    -> Insert new record: source='manual'
    -> Timeline refreshes
```

### 3.4 Delete Flow (manual records only)

```
User taps manual record -> Expanded view
  -> User taps "删除"
    -> Confirmation dialog
    -> Delete record
    -> Timeline refreshes
```

---

## 4. Architecture

### 4.1 New Files

| File | Responsibility |
|------|---------------|
| `data/local/entity/DividendIncomeRecordEntity.kt` | Room entity with converter mappings |
| `data/local/dao/DividendIncomeRecordDao.kt` | CRUD queries: insert, query by year, query by stock+exDate, delete manual |
| `data/repository/DividendIncomeRepository.kt` | Auto generation logic, merge logic, manual record CRUD |
| `viewmodel/DividendIncomeViewModel.kt` | UI state: selected year, records list, summary stats, dialog state |
| `ui/component/IncomeTimelineCard.kt` | Collapsible timeline item component |
| `ui/component/IncomeSummaryCard.kt` | Year summary with YoY comparison |
| `ui/component/YearSelector.kt` | Horizontal year chip selector |

### 4.2 Modified Files

| File | Change |
|------|--------|
| `data/local/AppDatabase.kt` | Add entity, bump version 4->5, add migration |
| `di/RepositoryModule.kt` (or equivalent Hilt module) | Provide `DividendIncomeRepository` and `DividendIncomeRecordDao` |
| `ui/screen/HomeScreen.kt` | Add TabRow, wrap existing content in tab, add income tab content |
| `viewmodel/HomeViewModel.kt` | Add selected tab state |

### 4.3 Dependency Graph

```
HomeScreen
  ├─ Tab: "关注列表" -> existing HomeViewModel
  └─ Tab: "股息收入" -> DividendIncomeViewModel
       └─ DividendIncomeRepository
            ├─ DividendIncomeRecordDao
            ├─ DividendDao (for auto generation)
            └─ StockDao (for shares data)
```

---

## 5. Scope Boundaries

### In Scope (This Iteration)

- Dividend income record data model and storage
- Auto generation from existing dividend data
- Manual correction and addition of income records
- Year-based timeline view with summary card
- YoY comparison in summary

### Out of Scope (Future Iterations)

- Dividend growth trend charts (iteration 2: visualization)
- Achievement/milestone system (iteration 2: visualization)
- Compound interest simulator (iteration 3)
- Notifications/reminders
- Export (CSV/PDF)
- Multi-currency support

---

## 6. Testing Strategy

### Unit Tests

**DividendIncomeRepository:**
- Auto-generation correctly diffs and inserts missing records
- Auto-generation skips dividends with null `exDividendDate`
- Auto-generation handles `shares == 0` (amount = 0.0)
- Correction mutates record in-place (source -> manual, amount updated, updatedAt changed)
- Manual add creates record with correct fields
- Delete only works on manual records
- YoY calculation handles: first year (no prior year), increase, decrease, zero income

**DividendIncomeViewModel:**
- Year selection updates displayed records
- Auto-generation triggers on init
- Dialog state transitions (correct/add/delete)

**ForecastCalculator (existing):**
- No changes expected, but regression test to ensure auto-generation doesn't interfere

### Migration Test

- v4 -> v5 migration creates `dividend_income_records` table with correct schema
- Existing data in `stocks`, `dividends`, `fire_goal` tables is preserved

### Compose Tests

- `IncomeTimelineCard`: collapsed/expanded state toggle
- `IncomeSummaryCard`: renders YoY comparison correctly
- `YearSelector`: year chip selection updates state
