package com.stock.dividend.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.stock.dividend.ui.screen.BackupRestoreScreen
import com.stock.dividend.ui.screen.ExpenseCoverageScreen
import com.stock.dividend.ui.screen.FireGoalSetupScreen
import com.stock.dividend.ui.screen.MainScaffold

object Routes {
    const val MAIN = "main"
    const val FIRE_GOAL_SETUP = "fireGoalSetup"
    const val EXPENSE_COVERAGE = "expenseCoverage"
    const val BACKUP_RESTORE = "backupRestore"
}

@Composable
fun AppNavigation() {
    val rootNavController = rememberNavController()

    NavHost(
        navController = rootNavController,
        startDestination = Routes.MAIN
    ) {
        composable(Routes.MAIN) {
            MainScaffold(rootNavController = rootNavController)
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
    }
}
