# Lightweight Design System Design

## Overview

Create a lightweight visual design system for the Stock Dividend Tracker Android app. The goal is to keep the product in a clean, professional finance-tool style while reducing duplicated one-off UI styling across screens.

This design system is intentionally small. It should standardize the repeated visual patterns that already exist in the app: page spacing, section headers, cards, metric text, status labels, and action placement.

## Product Direction

The visual direction is clean professional finance. The app should feel practical, trustworthy, readable, and calm.

The design should avoid:

- Heavy glassmorphism or decorative visual effects.
- Oversized marketing-style hero sections.
- Dense dashboard layouts that make mobile scanning difficult.
- Page-specific styling that creates inconsistent card radius, spacing, button placement, or text hierarchy.

The existing blue, slate, and gold finance palette remains the visual foundation.

## Goals

- Establish shared UI patterns for common screen structure.
- Make primary user actions easier to find without relying on floating action buttons.
- Reduce repeated hard-coded shapes and spacing in page implementations.
- Keep financial data readable and scannable on mobile.
- Provide a migration path that can be applied page by page.

## Non-Goals

- Redesign the entire app in one pass.
- Change business logic, repositories, ViewModels, or database schema.
- Replace Material Design 3 components.
- Introduce a full token architecture beyond the current app needs.
- Revisit chart rendering libraries or data visualization internals.

## Design Principles

### Consistency Over Decoration

Shared components should make common patterns look the same across pages. Visual polish comes from consistent spacing, type hierarchy, color roles, and predictable actions rather than decorative effects.

### Information First

Financial data should remain the focus. Cards, buttons, and section headers should support scanning and comparison.

### Mobile Ergonomics

Touch targets should remain comfortable. Important actions should be visible where users are already reading, and destructive or low-frequency actions should not compete with primary content.

### Incremental Adoption

The system should be small enough to migrate screen by screen. The first implementation should prove the components on the watchlist and income pages before broader adoption.

## First-Phase Scope

### 1. Page Structure

Define reusable spacing expectations for standard content screens:

- Horizontal content padding: 16dp.
- Primary vertical list spacing: 10dp or 12dp depending on content density.
- Section title spacing: enough separation from the preceding card without creating large blank areas.
- Bottom content padding: enough to avoid bottom navigation overlap.
- Secondary screens using `CompactTopAppBar` should respect the status bar safe area.

The first phase should document these rules and apply them through helper components where practical.

### 2. Section Header Row

Introduce a reusable section header pattern for rows such as:

- `持仓列表` + `添加股票`
- `收入记录` + `添加收入`

The section header should support:

- A left-aligned title.
- An optional right-aligned action.
- An optional icon for the action.
- Consistent typography and spacing.
- A minimum touch target for the action.

This pattern replaces repeated custom `Text` plus `TextButton` rows.

### 3. Card Baseline

Define default expectations for app cards:

- Use the theme shape where possible, especially `MaterialTheme.shapes.medium` for standard cards.
- Use a consistent container color for ordinary list cards.
- Use a distinct but restrained treatment for summary cards.
- Keep padding predictable: list cards around 14-16dp, summary cards around 16-20dp.
- Avoid one-off `RoundedCornerShape(12.dp)` or `RoundedCornerShape(14.dp)` when a theme shape communicates the same intent.

The first implementation can add a small wrapper or helper modifier if it meaningfully reduces repeated code.

### 4. Metric Text

Define a shared way to present financial values:

- Primary money values use a prominent but not oversized style.
- Secondary money values use a smaller label/value pairing.
- Percentages use consistent weight and color treatment.
- Positive, negative, warning, and neutral states use semantic color roles consistently.
- Amount formatting stays owned by existing feature logic unless a shared formatter already exists.

The design system should not change financial calculations.

### 5. Action Placement

Standardize common action placement:

- High-frequency add actions appear in the relevant section header.
- Low-frequency item actions can live in menus or secondary action rows.
- Destructive actions should not be visually equal to primary positive actions.
- Icon-only buttons must have clear `contentDescription`.

This matches the recent direction of moving add-stock and add-income actions into section headers.

## Suggested Components

The first phase can introduce a small set of UI helpers under `app/src/main/java/com/stock/dividend/ui/component/`:

- `SectionHeader`: shared title plus optional trailing action.
- `FinanceMetric`: compact label/value metric display.
- `StatusPill`: compact status label with semantic color.
- `AppCardDefaults`: shared card shape, colors, and padding constants if the implementation benefits from centralizing them.

These should remain simple Compose functions or objects. Avoid adding unnecessary abstraction or a broad token system before repeated usage justifies it.

## First Migration Targets

### Watchlist Page

Use the new section header for `持仓列表` and `添加股票`.

Keep existing stock card behavior:

- Tap stock to open details.
- Swipe to delete.
- Empty state continues to offer adding the first stock.

### Income Page

Use the new section header for `收入记录` and `添加收入`.

Keep existing income behavior:

- Add income opens the existing add-income dialog.
- Correct/edit/delete manual income records continue to work.
- Charts and yearly summary remain unchanged.

## Later Migration Targets

After the first phase is stable, migrate:

- Living expense coverage cards and action menu styling.
- Stock detail forecast and dividend record cards.
- Add-stock result cards and holding input sections.
- Edit-holding transaction cards.
- Achievement cards and category spacing.

## Testing and Validation

Implementation should verify:

- Existing unit tests continue to pass.
- Watchlist add-stock flow still opens the add-stock screen.
- Income add-income action still opens the add-income dialog.
- Top bars still respect safe areas.
- Section action buttons have clear accessible labels or visible text.
- No new horizontal overflow appears on small screens.

## Success Criteria

- The watchlist and income pages use a shared section header component.
- Add actions are visually consistent across the two primary tabs.
- Common card shape and spacing rules are documented and applied where touched.
- The implementation avoids business logic changes.
- Future page migrations have clear rules to follow.
