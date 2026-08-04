package com.stock.dividend.ui.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import com.stock.dividend.ui.navigation.Routes

internal data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

internal val bottomNavItems = listOf(
    BottomNavItem("portfolio", "持仓", Icons.Filled.AccountBalance),
    BottomNavItem("income", "股息收入", Icons.AutoMirrored.Filled.TrendingUp),
    BottomNavItem("ai", "AI", Icons.Filled.SmartToy),
    BottomNavItem("achievements", "成就", Icons.Filled.EmojiEvents),
    BottomNavItem("settings", "设置", Icons.Filled.Settings)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    rootNavController: NavHostController,
    pendingDeepLink: String?,
    onDeepLinkConsumed: () -> Unit,
) {
    val tabNavController = rememberNavController()

    // deep link 消费：通知点击携带的 stockCode → 跳个股详情
    LaunchedEffect(pendingDeepLink) {
        val code = pendingDeepLink ?: return@LaunchedEffect
        tabNavController.navigate("stockDetail/$code") {
            launchSingleTop = true
        }
        onDeepLinkConsumed()
    }

    val tabBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentTabRoute = tabBackStackEntry?.destination?.route

    val selectedTabIndex = bottomNavItems.indexOfFirst { it.route == currentTabRoute }.coerceAtLeast(0)

    val snackbarHostState = remember { SnackbarHostState() }

    // 全局刷新：当前 Tab 通过 registerTabRefresh 写入；悬浮刷新按钮读取此状态
    val refreshHandleState = remember { mutableStateOf<RefreshHandle?>(null) }
    val refreshHandle by refreshHandleState

    CompositionLocalProvider(LocalTabRefreshRegistrar provides refreshHandleState) {
        Scaffold(
            // 移除顶部「股息追踪」标题栏：Scaffold 默认仍会把状态栏 inset 计入 content
            // 的 innerPadding（contentWindowInsets），保留与系统状态栏的间距。
            floatingActionButton = {
                // 仅在当前 Tab 注册了刷新回调时显示
                val handle = refreshHandle
                if (handle != null) {
                    RefreshFloatingButton(
                        onClick = handle.refresh,
                        isRefreshing = handle.isRefreshing
                    )
                }
            },
            floatingActionButtonPosition = FabPosition.End,
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
        // consumeWindowInsets：告知子树状态栏 inset 已被本层 padding 消费，
        // 否则嵌套子页面的 CompactTopAppBar.statusBarsPadding() 会再次叠加，
        // 造成股票详情等子页面顶部与状态栏间距过大（状态栏 inset 被应用两次）。
        NavHost(
            navController = tabNavController,
            startDestination = "portfolio",
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            composable("portfolio") {
                PortfolioScreen(
                    snackbarHostState = snackbarHostState,
                    onAddStockClick = { tabNavController.navigate("addStock") },
                    onStockClick = { code -> tabNavController.navigate("stockDetail/$code") },
                    onEditStock = { code -> tabNavController.navigate("editHolding/$code") },
                    onImportFromScreenshot = { tabNavController.navigate("portfolioImport") },
                    onFireCardClick = { rootNavController.navigate(Routes.EXPENSE_COVERAGE) },
                    onNavigateToEvaluation = { tabNavController.navigate("portfolioEvaluation") }
                )
            }
            composable("portfolioEvaluation") {
                val parentEntry = remember(it) {
                    tabNavController.getBackStackEntry("portfolio")
                }
                PortfolioEvaluationScreen(
                    onBack = { tabNavController.popBackStack() },
                    viewModel = hiltViewModel(parentEntry)
                )
            }
            composable("income") {
                IncomeScreen()
            }
            composable("ai") {
                AiChatScreen(
                    onGoSettings = {
                        tabNavController.navigate("settings") {
                            popUpTo(tabNavController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenAiSettings = { tabNavController.navigate("aiSettings") }
                )
            }
            composable("aiSettings") {
                AiSettingsScreen(onBack = { tabNavController.popBackStack() })
            }
            composable("achievements") {
                AchievementScreen()
            }
            composable("settings") {
                SettingsScreen(
                    onOpenAlertEvalSettings = { tabNavController.navigate("alertEvalSettings") },
                    onOpenLlmStrategySettings = { tabNavController.navigate("llmStrategySettings") },
                    onOpenDataSettings = { tabNavController.navigate("dataSettings") },
                    onOpenTransactionHistory = { rootNavController.navigate(Routes.TRANSACTION_HISTORY) },
                    onOpenGridPlan = { rootNavController.navigate(Routes.GRID_PLAN) }
                )
            }
            composable("alertEvalSettings") {
                AlertEvalSettingsScreen(
                    onBack = { tabNavController.popBackStack() },
                    onOpenNotificationReliability = { tabNavController.navigate("notificationReliability") }
                )
            }
            composable("llmStrategySettings") {
                LlmStrategySettingsScreen(
                    onBack = { tabNavController.popBackStack() },
                    onOpenStrategyLibrary = { rootNavController.navigate(Routes.TRADE_STRATEGY_LIST) }
                )
            }
            composable("dataSettings") {
                DataSettingsScreen(
                    onBack = { tabNavController.popBackStack() },
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
            composable("notificationReliability") {
                NotificationReliabilityScreen(onBack = { tabNavController.popBackStack() })
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
                    onOpenDripSimulation = { c -> tabNavController.navigate("dripSimulation/$c") },
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
                route = "dripSimulation/{code}",
                arguments = listOf(navArgument("code") { type = NavType.StringType })
            ) {
                DripSimulationScreen(onBack = { tabNavController.popBackStack() })
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
}

/**
 * 右下角悬浮刷新按钮。刷新中旋转图标表示 loading，期间禁用点击。
 */
@Composable
private fun RefreshFloatingButton(
    onClick: () -> Unit,
    isRefreshing: Boolean
) {
    val rotation by if (isRefreshing) {
        val transition = rememberInfiniteTransition(label = "refresh")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "refreshRotation"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    FloatingActionButton(
        // 刷新中忽略重复点击，避免叠加多次请求
        onClick = if (isRefreshing) ({ }) else onClick
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = "刷新",
            modifier = Modifier.rotate(rotation)
        )
    }
}
