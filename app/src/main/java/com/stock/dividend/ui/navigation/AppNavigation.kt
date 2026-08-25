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
import com.stock.dividend.ui.screen.StrategyPlanScreen
import com.stock.dividend.ui.screen.EditHoldingScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
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
    const val STRATEGY_PLAN = "strategyPlan"
}

@Composable
fun AppNavigation(
    pendingDeepLink: String?,
    onDeepLinkConsumed: () -> Unit,
) {
    val rootNavController = rememberNavController()

    NavHost(
        navController = rootNavController,
        startDestination = Routes.MAIN,
        enterTransition = { NavTransitions.enter() },
        exitTransition = { NavTransitions.exit() },
        popEnterTransition = { NavTransitions.popEnter() },
        popExitTransition = { NavTransitions.popExit() },
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
                onBack = { rootNavController.popBackStack() },
                onAddTransaction = { code, price, shares, isBuy ->
                    // 与 MainScaffold tab 路由同款「一键记账」闭环：按方向拼预填参数
                    // （2026-08-24 评审修复：根路由漏传时按钮静默 no-op）
                    val direction = if (isBuy) "buy" else "sell"
                    rootNavController.navigate(
                        "editHolding/$code?${direction}Price=${"%.2f".format(price)}&${direction}Shares=$shares"
                    )
                }
            )
        }
        composable(Routes.STRATEGY_PLAN) {
            StrategyPlanScreen(
                onBack = { rootNavController.popBackStack() },
                onAddTransaction = { code, price, shares, isBuy ->
                    // 与 MainScaffold tab 路由同款「一键记账」闭环：按方向拼预填参数
                    // （2026-08-24 评审修复：根路由漏传时「买入/卖出 N 股」按钮点击无反应）
                    val direction = if (isBuy) "buy" else "sell"
                    rootNavController.navigate(
                        "editHolding/$code?${direction}Price=${"%.2f".format(price)}&${direction}Shares=$shares"
                    )
                }
            )
        }
        // 根层 editHolding（MainScaffold tab 层已有同名路由，此处供根路由页面的一键记账闭环；
        // 路由形态/参数与 tab 层完全一致）
        composable(
            route = "editHolding/{code}?buyPrice={buyPrice}&buyShares={buyShares}" +
                "&sellPrice={sellPrice}&sellShares={sellShares}",
            arguments = listOf(
                navArgument("code") { type = NavType.StringType },
                navArgument("buyPrice") { type = NavType.StringType; defaultValue = "" },
                navArgument("buyShares") { type = NavType.StringType; defaultValue = "" },
                navArgument("sellPrice") { type = NavType.StringType; defaultValue = "" },
                navArgument("sellShares") { type = NavType.StringType; defaultValue = "" }
            )
        ) {
            EditHoldingScreen(onBack = { rootNavController.popBackStack() })
        }
    }
}
