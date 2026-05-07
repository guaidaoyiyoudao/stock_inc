# Quickstart: Dividend Rate Chart

**Feature**: 007-dividend-rate-chart  
**Date**: 2026-05-07

## Prerequisites

- Android Studio or command-line Gradle environment configured for this project.
- Emulator or device available for manual verification.
- App has at least one stock with cached dividend records.

## Implementation Verification

1. Run unit tests for stock detail state derivation:

   ```bash
   ./gradlew testDebugUnitTest --tests com.stock.dividend.viewmodel.StockDetailViewModelTest
   ```

2. Build the debug app:

   ```bash
   ./gradlew assembleDebug
   ```

3. Launch the app on an emulator or device.

4. Open a stock detail page with at least two dividend records that include dividend rates.

5. Verify the dividend section shows a "分红率趋势" line chart above the dividend record list.

6. Verify the chart plots points from older periods on the left to newer periods on the right.

7. If a stock has multiple dividend records in the same year, verify the chart shows one annual point whose percentage is the sum of that year's valid dividend rates.

8. Inspect the chart point detail chips and confirm each annual period and summed percentage value are identifiable.

9. Open or create a stock with dividend records but no valid dividend rate values.

10. Verify the dividend section shows a clear Chinese fallback instead of an empty chart.

11. Open or create a stock with exactly one valid dividend rate year.

12. Verify the page displays that single annual percentage and explains that there is not enough history for a trend.

13. Repeat the chart view in dark theme and on a narrow screen size; verify labels and values remain readable.

## Expected Outcome

- Valid dividend rate histories display as a line chart.
- Missing or insufficient dividend rate histories are explained clearly.
- Existing dividend record list, forecast cards, refresh behavior, and cached offline browsing continue to work.
