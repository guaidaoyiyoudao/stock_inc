package com.stock.dividend.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stock.dividend.ui.theme.GlassColors

private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val bottomNavItems = listOf(
    BottomNavItem("watchlist", "持仓", Icons.Filled.AccountBalance),
    BottomNavItem("income", "股息收入", Icons.AutoMirrored.Filled.TrendingUp),
    BottomNavItem("achievements", "成就", Icons.Filled.EmojiEvents)
)

@Composable
fun MainScaffold(rootNavController: NavHostController) {
    val tabNavController = rememberNavController()
    val tabBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentTabRoute = tabBackStackEntry?.destination?.route

    val selectedTabIndex = bottomNavItems.indexOfFirst { it.route == currentTabRoute }.coerceAtLeast(0)

    val fabVisible = currentTabRoute in listOf("watchlist", "income")
    val fabOnWatchlist = currentTabRoute == "watchlist"

    var incomeFabTrigger by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = if (isSystemInDarkTheme())
                    GlassColors.DarkSurface else GlassColors.LightSurface,
                tonalElevation = 0.dp
            ) {
                bottomNavItems.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = index == selectedTabIndex,
                        onClick = {
                            if (index != selectedTabIndex) {
                                tabNavController.navigate(item.route) {
                                    popUpTo(tabNavController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(imageVector = item.icon, contentDescription = item.label)
                        },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = fabVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (fabOnWatchlist) tabNavController.navigate("addStock")
                        else incomeFabTrigger++
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = {
                        Text(
                            if (fabOnWatchlist) "添加股票" else "添加收入",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = tabNavController,
            startDestination = "watchlist",
            modifier = Modifier.padding(padding)
        ) {
            composable("watchlist") {
                WatchlistScreen(
                    onAddStockClick = { tabNavController.navigate("addStock") },
                    onStockClick = { code -> tabNavController.navigate("stockDetail/$code") },
                    onFireCardClick = { rootNavController.navigate("fireGoalSetup") }
                )
            }
            composable("income") {
                IncomeScreen(fabTrigger = incomeFabTrigger)
            }
            composable("achievements") {
                AchievementScreen()
            }
            composable("addStock") {
                AddStockScreen(onBack = { tabNavController.popBackStack() })
            }
            composable(
                route = "stockDetail/{code}",
                arguments = listOf(navArgument("code") { type = NavType.StringType })
            ) { entry ->
                val code = entry.arguments?.getString("code") ?: return@composable
                StockDetailScreen(
                    stockCode = code,
                    onBack = { tabNavController.popBackStack() },
                    onEditHolding = { c -> tabNavController.navigate("editHolding/$c") }
                )
            }
            composable(
                route = "editHolding/{code}",
                arguments = listOf(navArgument("code") { type = NavType.StringType })
            ) {
                EditHoldingScreen(onBack = { tabNavController.popBackStack() })
            }
        }
    }
}
