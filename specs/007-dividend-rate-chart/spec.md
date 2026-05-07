# Feature Specification: Dividend Rate Chart

**Feature Branch**: `007-dividend-rate-chart`  
**Created**: 2026-05-07  
**Status**: Draft  
**Input**: User description: "股票详情中的分红，采用折线图的形式展示分红率"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - View dividend rate trend (Priority: P1)

As an investor viewing a stock detail page, I want the dividend section to show dividend rate as a line chart so that I can quickly understand how the stock's dividend yield has changed over time.

**Why this priority**: This is the core requested value: converting dividend rate information into a trend view that supports faster comparison and decision-making.

**Independent Test**: Can be fully tested by opening a stock detail page with historical dividend data and confirming that the dividend section presents dividend rate points connected over time with clear labels and values.

**Acceptance Scenarios**:

1. **Given** a stock has multiple historical dividend records with dividend rates, **When** the user opens the stock detail page and views the dividend section, **Then** the user sees a line chart showing dividend rate changes across the available periods.
2. **Given** the user inspects a point on the dividend rate trend, **When** the point is selected or hovered, **Then** the user can identify the corresponding period and dividend rate value.

---

### User Story 2 - Understand sparse or missing dividend data (Priority: P2)

As an investor viewing a stock with limited dividend history, I want the dividend section to clearly communicate when trend data is insufficient so that I do not mistake missing data for poor dividend performance.

**Why this priority**: Financial trend displays must avoid misleading users when historical data is sparse, absent, or partially unavailable.

**Independent Test**: Can be fully tested by opening stock detail pages with zero, one, or incomplete dividend records and confirming the page shows an understandable fallback rather than an empty or misleading chart.

**Acceptance Scenarios**:

1. **Given** a stock has no dividend rate records, **When** the user views the dividend section, **Then** the section explains that dividend rate trend data is currently unavailable.
2. **Given** a stock has only one dividend rate record, **When** the user views the dividend section, **Then** the section shows the available value and indicates that there is not enough history to form a trend.
3. **Given** some dividend records are missing dividend rates, **When** the chart is shown, **Then** only valid dividend rate values are plotted and the user can still understand which periods are represented.

---

### User Story 3 - Compare recent dividend rate direction (Priority: P3)

As an investor, I want the dividend rate chart to make recent changes easy to scan so that I can quickly judge whether the dividend rate is rising, falling, or stable.

**Why this priority**: Once the core trend is visible, scanability improves the user's ability to make practical use of the data.

**Independent Test**: Can be tested by reviewing stocks with rising, falling, and flat dividend rate histories and confirming the chart makes the direction apparent without requiring manual calculation.

**Acceptance Scenarios**:

1. **Given** a stock has dividend rate records across several periods, **When** the user views the chart, **Then** the periods are ordered chronologically and the overall direction is visually apparent.
2. **Given** dividend rates vary significantly between periods, **When** the chart is displayed, **Then** the scale allows users to compare relative changes without clipping or hiding values.

### Edge Cases

- Stock has no dividend records.
- Stock has exactly one valid dividend rate record.
- Some records contain dividend events but no dividend rate value.
- Dividend rate values include zero or unusually high values.
- Records arrive out of chronological order.
- Dividend rate labels or period labels are long enough to risk crowding on smaller screens.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The stock detail page MUST include a dividend rate line chart within the dividend section when at least two valid dividend rate records are available.
- **FR-002**: The chart MUST plot dividend rate values against their associated dividend periods or dates in chronological order.
- **FR-003**: Users MUST be able to identify the dividend rate and associated period for each plotted point.
- **FR-004**: The dividend section MUST show a clear fallback message when no dividend rate trend can be displayed.
- **FR-005**: When only one valid dividend rate record exists, the dividend section MUST display that value and communicate that trend history is insufficient.
- **FR-006**: Records without valid dividend rate values MUST NOT be plotted as numeric trend points.
- **FR-007**: The chart MUST preserve access to the dividend context already available on the stock detail page, so users can understand the rate trend alongside the existing dividend information.
- **FR-008**: The chart labels and values MUST remain readable on supported desktop and mobile viewing sizes.
- **FR-009**: Dividend rate values MUST be presented with an explicit percent indicator or equivalent wording so users do not confuse them with cash dividend amounts.
- **FR-010**: When multiple dividend records exist in the same year, the chart MUST display one annual point whose dividend rate is the sum of that year's valid dividend rate values.

### Key Entities

- **Stock**: The company or security shown on the stock detail page; provides the context for dividend information.
- **Dividend Record**: A historical dividend entry associated with a stock, including period or date and optional dividend rate.
- **Dividend Rate Point**: A valid plotted annual value derived from one or more dividend records in the same year, consisting of a year and a summed dividend rate percentage.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: At least 95% of users reviewing a stock with two or more dividend rate records can determine whether the recent dividend rate trend is rising, falling, or stable within 10 seconds.
- **SC-002**: 100% of plotted dividend rate points expose their period/date and percentage value to the user.
- **SC-003**: For stocks with no or insufficient dividend rate history, users see an explanatory fallback within the dividend section instead of an empty chart in 100% of cases.
- **SC-004**: The dividend section remains readable without overlapping labels or values across supported desktop and mobile screen sizes.

## Assumptions

- The existing stock detail page already has a dividend section and dividend history data source.
- "分红率" refers to dividend rate or dividend yield expressed as a percentage for each dividend period/date.
- Historical dividend rate records are the source of the trend; records in the same year are summed into one annual trend point, and forecasted future dividend rates are outside the scope of this feature.
- The feature focuses on visualization and comprehension of dividend rate trends, not changing dividend calculation rules.
