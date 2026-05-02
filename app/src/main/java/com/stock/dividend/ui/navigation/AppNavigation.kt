package com.stock.dividend.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.stock.dividend.ui.screen.FireGoalSetupScreen
import com.stock.dividend.ui.screen.MainScaffold

object Routes {
    const val MAIN = "main"
    const val FIRE_GOAL_SETUP = "fireGoalSetup"
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
    }
}
