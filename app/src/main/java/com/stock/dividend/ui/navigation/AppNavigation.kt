package com.stock.dividend.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.stock.dividend.ui.screen.BackupRestoreScreen
import com.stock.dividend.ui.screen.ExpenseCoverageScreen
import com.stock.dividend.ui.screen.FireGoalSetupScreen
import com.stock.dividend.ui.screen.MainScaffold
import com.stock.dividend.ui.screen.ScreenshotImportScreen
import com.stock.dividend.ui.screen.TradeStrategyListScreen
import com.stock.dividend.ui.screen.TransactionHistoryScreen
import com.stock.dividend.ui.screen.TransactionImportScreen
import com.stock.dividend.ui.screen.GridPlanScreen

object Routes {
    const val MAIN = "main"
    const val FIRE_GOAL_SETUP = "fireGoalSetup"
    const val EXPENSE_COVERAGE = "expenseCoverage"
    const val BACKUP_RESTORE = "backupRestore"
    const val TRADE_STRATEGY_LIST = "tradeStrategyList"
    const val SCREENSHOT_IMPORT = "screenshotImport"
    const val TRANSACTION_HISTORY = "transactionHistory"
    const val TRANSACTION_IMPORT = "transactionImport"
    const val GRID_PLAN = "gridPlan"
    const val GRID_PLAN_FOR_STOCK = "gridPlanFor/{code}"
}

@Composable
fun AppNavigation(
    pendingDeepLink: String?,
    onDeepLinkConsumed: () -> Unit,
) {
    val rootNavController = rememberNavController()

    NavHost(
        navController = rootNavController,
        startDestination = Routes.MAIN
    ) {
        composable(Routes.MAIN) {
            MainScaffold(
                rootNavController = rootNavController,
                pendingDeepLink = pendingDeepLink,
                onDeepLinkConsumed = onDeepLinkConsumed
            )
        }
        composable(Routes.FIRE_GOAL_SETUP) {
            FireGoalSetupScreen(
                onBack = { rootNavController.popBackStack() }
            )
        }
        composable(Routes.EXPENSE_COVERAGE) {
            ExpenseCoverageScreen(
                onBack = { rootNavController.popBackStack() },
                onGoSetup = { rootNavController.navigate(Routes.FIRE_GOAL_SETUP) }
            )
        }
        composable(Routes.BACKUP_RESTORE) {
            BackupRestoreScreen(
                onBack = { rootNavController.popBackStack() }
            )
        }
        composable(Routes.TRADE_STRATEGY_LIST) {
            TradeStrategyListScreen(
                onBack = { rootNavController.popBackStack() },
                onAddFromScreenshot = { rootNavController.navigate(Routes.SCREENSHOT_IMPORT) }
            )
        }
        composable(Routes.SCREENSHOT_IMPORT) {
            ScreenshotImportScreen(
                onBack = { rootNavController.popBackStack() },
                onViewList = { rootNavController.navigate(Routes.TRADE_STRATEGY_LIST) }
            )
        }
        composable(Routes.TRANSACTION_HISTORY) {
            TransactionHistoryScreen(
                onBack = { rootNavController.popBackStack() },
                onImportFromScreenshot = { rootNavController.navigate(Routes.TRANSACTION_IMPORT) }
            )
        }
        composable(Routes.TRANSACTION_IMPORT) {
            TransactionImportScreen(
                onBack = { rootNavController.popBackStack() }
            )
        }
        composable(Routes.GRID_PLAN) {
            GridPlanScreen(
                onBack = { rootNavController.popBackStack() }
            )
        }
    }
}
