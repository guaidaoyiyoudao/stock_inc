# Feature Specification: Optimize Scroll Performance

**Feature Branch**: `006-optimize-scroll-perf`
**Created**: 2026-04-30
**Status**: Draft
**Input**: User description: "优化APP的执行性能，当前滑动不丝滑，略微卡顿"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Smooth Stock List Scrolling (Priority: P1)

As a user viewing my stock holdings on the home screen, I want to scroll through my stock list smoothly without any stuttering or frame drops, so that the app feels responsive and native.

**Why this priority**: The home screen stock list is the primary screen users interact with. Any stuttering here is the most visible and impactful performance issue.

**Independent Test**: Can be fully tested by adding 20+ stocks to the portfolio and rapidly scrolling through the list — the scroll should remain at consistent 60fps with no visible jank.

**Acceptance Scenarios**:

1. **Given** a portfolio with 20+ stocks, **When** the user rapidly scrolls up and down the stock list, **Then** the scroll motion remains smooth with no visible stuttering or frame drops.
2. **Given** the home screen is displaying stock cards with price data, **When** the user scrolls through the list, **Then** each card renders immediately when it appears on screen without delay or blank flashes.
3. **Given** the home screen is displaying, **When** a background data refresh completes (e.g., new quotes fetched), **Then** the scroll position and motion are not interrupted by a full list re-render.

---

### User Story 2 - Smooth Dividend History Scrolling (Priority: P2)

As a user viewing a stock's detail page, I want to scroll through the dividend history and forecast cards smoothly, so that reviewing my dividend income data is a pleasant experience.

**Why this priority**: The stock detail page contains the densest content — forecast cards, comparison charts, and potentially long dividend history lists. Performance issues here are noticeable during data review.

**Independent Test**: Can be tested by opening a stock with 50+ dividend records and scrolling through the entire detail page — scroll should be smooth throughout.

**Acceptance Scenarios**:

1. **Given** a stock detail page with 50+ dividend records, **When** the user scrolls through the full page, **Then** the scroll is smooth with no stuttering at any section.
2. **Given** the stock detail page is loading forecast data, **When** the data arrives, **Then** the UI updates without causing a visible jank or layout jump.

---

### User Story 3 - Smooth Search Results Scrolling (Priority: P3)

As a user searching for a stock to add, I want the search results list to scroll smoothly as I browse through matching stocks, even as the results update while I type.

**Why this priority**: The search screen is used less frequently and has fewer items, but smooth interaction here reinforces overall app quality.

**Independent Test**: Can be tested by typing a search query and scrolling through results simultaneously — list should update and scroll without lag.

**Acceptance Scenarios**:

1. **Given** the user is typing a stock search query, **When** search results update, **Then** the results list transitions smoothly without a full re-render flicker.
2. **Given** a list of 10+ search results, **When** the user scrolls through them, **Then** each result card appears instantly without delay.

---

### User Story 4 - Efficient Background Behavior (Priority: P4)

As a user navigating between screens, I want the app to not waste resources on screens I've left, so that the overall app remains responsive and doesn't drain battery.

**Why this priority**: Background resource waste indirectly affects foreground performance — unnecessary recompositions on inactive screens compete for CPU time with the active screen.

**Independent Test**: Can be tested by navigating between home screen, detail screen, and back — the app should not exhibit any lag when returning to a previously visited screen.

**Acceptance Scenarios**:

1. **Given** the user navigates from the home screen to a stock detail screen, **When** background data updates occur on the home screen, **Then** those updates do not consume CPU or cause jank on the detail screen.
2. **Given** the user returns to the home screen after visiting other screens, **When** the home screen resumes, **Then** it displays current data promptly without a noticeable reload delay.

---

### Edge Cases

- What happens when the user scrolls during an active data refresh (quotes being fetched)?
- How does the app perform with an extremely large stock portfolio (50+ stocks)?
- What happens when network is slow and quote data arrives in bursts?
- How does the app handle rapid screen switching (quickly tapping between stocks)?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The app MUST maintain consistent 60fps scroll performance on the home screen stock list under normal conditions (up to 50 stocks).
- **FR-002**: The app MUST avoid creating unnecessary objects (formatters, temporary collections) during each visible item's rendering while scrolling.
- **FR-003**: The app MUST prevent redundant data processing — a single data change should not trigger multiple cascading UI updates for the same screen.
- **FR-004**: The app MUST ensure that state changes only affect the UI components that actually need to update, not trigger a full screen recomposition.
- **FR-005**: The app MUST stop processing UI state updates for screens that are not currently visible to the user.
- **FR-006**: The app MUST use unique identifiers for list items to enable efficient recycling during scroll.
- **FR-007**: The app MUST precompute display-ready values (formatted text, dates, currency) so that scroll rendering does not perform formatting work.
- **FR-008**: The app MUST not run continuous animations on screens when those animations are not visually needed.
- **FR-009**: The app MUST handle concurrent data source emissions by consolidating them to avoid rapid successive UI updates within a single frame.
- **FR-010**: All performance optimizations MUST preserve existing functionality — no visual changes, data loss, or behavioral regressions.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Scrolling through a list of 50 stocks on the home screen maintains 60fps with no frames exceeding 16ms render time, as measured by Android frame metrics.
- **SC-002**: The number of full-screen recompositions triggered by a single background data update is reduced to at most 1 (down from the current 2-3).
- **SC-003**: Navigating away from a screen stops all unnecessary state processing on that screen — no CPU activity from inactive screen state observers.
- **SC-004**: No new object allocations occur per scrolled item during steady-state scrolling (after initial render), as verified by memory profiling.
- **SC-005**: The app's perceived responsiveness (time from touch to visual response) remains under 100ms for all scroll interactions.

## Assumptions

- The performance issues are primarily caused by excessive recompositions and redundant state emissions, not by data volume — the app handles relatively small datasets.
- Target devices are mid-range Android phones (not low-end devices), so 60fps is a reasonable target.
- The existing data layer (Room database, network calls) is already properly asynchronous and not blocking the main thread.
- No new features or visual changes are expected as part of this optimization — this is purely a performance refinement.
- The app does not load or display images, so image-related optimizations are out of scope.
