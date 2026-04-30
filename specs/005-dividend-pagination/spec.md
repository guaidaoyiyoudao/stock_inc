# Feature Specification: Dividend Record Pagination

**Feature Branch**: `005-dividend-pagination`
**Created**: 2026-04-30
**Status**: Draft
**Input**: User description: "股票详情列表中，分红记录使用分页展示，每页展示五个分红记录"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Browse Dividend History Page by Page (Priority: P1)

A user viewing a stock's detail page wants to browse its dividend history without being overwhelmed by a long list. Initially, only the most recent 5 dividend records are visible. The user can load additional records in batches of 5 by tapping a "load more" control at the bottom of the list.

**Why this priority**: This is the core value — transforming an unbounded list into a digestible, paginated experience. Without this, the feature doesn't exist.

**Independent Test**: Open stock detail page with 12+ dividend records. Verify only 5 are shown initially, then tap "load more" and verify 5 more appear, and tap again for the remainder.

**Acceptance Scenarios**:

1. **Given** a stock has 12 dividend records, **When** the user opens the stock detail page, **Then** only the most recent 5 records are displayed, and a "加载更多" (Load More) indicator is visible at the bottom
2. **Given** 5 of 12 records are displayed, **When** the user taps "加载更多", **Then** records 6-10 appear (total 10 visible), and "加载更多" remains visible
3. **Given** 10 of 12 records are displayed, **When** the user taps "加载更多", **Then** records 11-12 appear (total 12 visible), and "加载更多" is no longer shown
4. **Given** a stock has 3 dividend records (fewer than one page), **When** the user opens the stock detail page, **Then** all 3 records are displayed and no "加载更多" control is shown

---

### User Story 2 - See Total Dividend Count (Priority: P2)

While browsing paginated dividends, the user sees how many records exist in total and how many are currently displayed, giving them context about the full history.

**Why this priority**: Enhances the paginated experience by providing context, but the pagination itself works without it.

**Independent Test**: Open stock detail with 12 records, verify section header shows "12" count badge (existing behavior) and after loading all records the count matches.

**Acceptance Scenarios**:

1. **Given** a stock has 12 dividend records and 5 are displayed, **When** the user views the "分红记录" section header, **Then** the existing count badge shows "12" (total records, not displayed count)

---

### Edge Cases

- What happens when a stock has zero dividend records? The existing empty state ("暂无股息数据") should remain unchanged.
- What happens after refreshing dividends? The page count should reset to show only the first page of the updated data.
- What happens when navigating back and returning to the detail page? The pagination state should reset to the first page.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST display at most 5 dividend records when the stock detail page is first loaded
- **FR-002**: The system MUST show a "加载更多" (Load More) control at the bottom of the dividend list when there are more than 5 total records
- **FR-003**: When the user activates the "加载更多" control, the system MUST display the next batch of up to 5 additional records, appended below the currently visible ones
- **FR-004**: The "加载更多" control MUST NOT be shown when all dividend records are already displayed
- **FR-005**: The page size MUST be 5 records per batch
- **FR-006**: The existing "分红记录" section header count badge MUST continue to show the total number of dividend records (not just the displayed count)
- **FR-007**: The pagination state MUST reset to the first page when the user refreshes dividend data
- **FR-008**: The pagination state MUST reset to the first page when the user navigates away from and returns to the stock detail page

### Key Entities

- **Dividend List Page State**: Tracks how many records are currently displayed (starts at page size, grows by page size on each "load more" action). The full list of dividends is already loaded; pagination is purely a display concern.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users see at most 5 dividend records on initial load of the stock detail page
- **SC-002**: Users can access the full dividend history by tapping "加载更多" a maximum of N times, where N = ceil(total_records / 5) - 1
- **SC-003**: The "加载更多" action is instantaneous with no perceptible delay (data is already in memory)
- **SC-004**: Existing dividend display features (per-share amount, yield, total, date) remain unchanged for each record

## Assumptions

- The full dividend list is already loaded into memory (existing behavior). Pagination is a UI-only display concern — no lazy-loading from database or API is needed.
- Page size is fixed at 5 as specified by the user. No user-configurable page size is required.
- "加载更多" is a button-style control at the bottom of the list, not infinite scroll.
- When dividends are refreshed from API, the full list is replaced and pagination resets to page 1.
