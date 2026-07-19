package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.text.font.FontWeight
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
import com.stock.dividend.ui.navigation.Routes

internal data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

internal val bottomNavItems = listOf(
    BottomNavItem("portfolio", "持仓", Icons.Filled.AccountBalance),
    BottomNavItem("watchlist", "自选", Icons.Filled.Star),
    BottomNavItem("income", "股息收入", Icons.AutoMirrored.Filled.TrendingUp),
    BottomNavItem("calendar", "日历", Icons.Filled.DateRange),
    BottomNavItem("achievements", "成就", Icons.Filled.EmojiEvents),
    BottomNavItem("settings", "设置", Icons.Filled.Settings)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(rootNavController: NavHostController) {
    val tabNavController = rememberNavController()
    val tabBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentTabRoute = tabBackStackEntry?.destination?.route

    val selectedTabIndex = bottomNavItems.indexOfFirst { it.route == currentTabRoute }.coerceAtLeast(0)

    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "股息追踪",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {}
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
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
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
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
        }
    ) { padding ->
        NavHost(
            navController = tabNavController,
            startDestination = "portfolio",
            modifier = Modifier.padding(padding)
        ) {
            composable("portfolio") {
                PortfolioScreen(
                    onStockClick = { code -> tabNavController.navigate("stockDetail/$code") },
                    onImportFromScreenshot = { tabNavController.navigate("portfolioImport") }
                )
            }
            composable("watchlist") {
                WatchlistScreen(
                    snackbarHostState = snackbarHostState,
                    onAddStockClick = { tabNavController.navigate("addStock") },
                    onStockClick = { code -> tabNavController.navigate("stockDetail/$code") },
                    onFireCardClick = { rootNavController.navigate(Routes.EXPENSE_COVERAGE) }
                )
            }
            composable("income") {
                IncomeScreen()
            }
            composable("calendar") {
                DividendCalendarScreen()
            }
            composable("achievements") {
                AchievementScreen()
            }
            composable("settings") {
                SettingsScreen(
                    onOpenDataManagement = { rootNavController.navigate(Routes.BACKUP_RESTORE) },
                    onOpenOcrDebug = { tabNavController.navigate("ocrDebug") }
                )
            }
            composable("addStock") {
                AddStockScreen(onBack = { tabNavController.popBackStack() })
            }
            composable("portfolioImport") {
                PortfolioImportScreen(onBack = { tabNavController.popBackStack() })
            }
            composable("ocrDebug") {
                OcrDebugScreen(onBack = { tabNavController.popBackStack() })
            }
            composable(
                route = "stockDetail/{code}",
                arguments = listOf(navArgument("code") { type = NavType.StringType })
            ) { entry ->
                val code = entry.arguments?.getString("code") ?: return@composable
                StockDetailScreen(
                    stockCode = code,
                    onBack = { tabNavController.popBackStack() },
                    onEditHolding = { c -> tabNavController.navigate("editHolding/$c") },
                    onOpenDividendValuation = { c -> tabNavController.navigate("dividendValuation/$c") },
                    onOpenNotificationSettings = { c -> tabNavController.navigate("stockNotificationSettings/$c") }
                )
            }
            composable(
                route = "dividendValuation/{code}",
                arguments = listOf(navArgument("code") { type = NavType.StringType })
            ) {
                DividendValuationScreen(onBack = { tabNavController.popBackStack() })
            }
            composable(
                route = "editHolding/{code}",
                arguments = listOf(navArgument("code") { type = NavType.StringType })
            ) {
                EditHoldingScreen(onBack = { tabNavController.popBackStack() })
            }
            composable(
                route = "stockNotificationSettings/{code}",
                arguments = listOf(navArgument("code") { type = NavType.StringType })
            ) {
                StockNotificationSettingsScreen(onBack = { tabNavController.popBackStack() })
            }
        }
    }
}
