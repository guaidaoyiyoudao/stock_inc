package com.stock.dividend.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stock.dividend.ui.screen.AddStockScreen
import com.stock.dividend.ui.screen.EditHoldingScreen
import com.stock.dividend.ui.screen.HomeScreen
import com.stock.dividend.ui.screen.StockDetailScreen

object Routes {
    const val HOME = "home"
    const val ADD_STOCK = "addStock"
    const val STOCK_DETAIL = "stockDetail/{code}"
    const val EDIT_HOLDING = "editHolding/{code}"

    fun stockDetail(code: String) = "stockDetail/$code"
    fun editHolding(code: String) = "editHolding/$code"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onAddStockClick = { navController.navigate(Routes.ADD_STOCK) },
                onStockClick = { code -> navController.navigate(Routes.stockDetail(code)) }
            )
        }

        composable(Routes.ADD_STOCK) {
            AddStockScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.STOCK_DETAIL,
            arguments = listOf(navArgument("code") { type = NavType.StringType })
        ) { entry ->
            val code = entry.arguments?.getString("code") ?: return@composable
            StockDetailScreen(
                stockCode = code,
                onBack = { navController.popBackStack() },
                onEditHolding = { stockCode -> navController.navigate(Routes.editHolding(stockCode)) }
            )
        }

        composable(
            route = Routes.EDIT_HOLDING,
            arguments = listOf(navArgument("code") { type = NavType.StringType })
        ) { entry ->
            val code = entry.arguments?.getString("code") ?: return@composable
            EditHoldingScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
