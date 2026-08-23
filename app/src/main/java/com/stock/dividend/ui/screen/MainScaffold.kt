package com.stock.dividend.ui.screen

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
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
import androidx.compose.material.icons.filled.Home
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
import com.stock.dividend.ui.navigation.LocalNavAnimatedVisibilityScope
import com.stock.dividend.ui.navigation.LocalSharedTransitionScope
import com.stock.dividend.ui.navigation.NavTransitions
import com.stock.dividend.ui.navigation.TabEnterTransition
import com.stock.dividend.ui.navigation.TabExitTransition
import com.stock.dividend.ui.navigation.Routes

internal data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

internal val bottomNavItems = listOf(
    BottomNavItem("today", "今日", Icons.Filled.Home),
    BottomNavItem("portfolio", "持仓", Icons.Filled.AccountBalance),
    BottomNavItem("income", "股息收入", Icons.AutoMirrored.Filled.TrendingUp),
    BottomNavItem("ai", "AI", Icons.Filled.SmartToy),
    BottomNavItem("settings", "设置", Icons.Filled.Settings)
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
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

    // 子页面（个股详情/设置二级页等）不属于任何 Tab → -1，不高亮任何 Tab。
    // ⚠️ 不能 coerceAtLeast(0)：会把「今日」误判为已选中，导致子页面点「今日」Tab 被
    // onClick 的 index 判断吞掉（只能靠返回键回去的 bug 根因）。
    val selectedTabIndex = bottomNavItems.indexOfFirst { it.route == currentTabRoute }

    val snackbarHostState = remember { SnackbarHostState() }

    // 全局刷新：当前 Tab 通过 registerTabRefresh 写入；悬浮刷新按钮读取此状态
    val refreshHandleState = remember { mutableStateOf<RefreshHandle?>(null) }
    val refreshHandle by refreshHandleState

    CompositionLocalProvider(LocalTabRefreshRegistrar provides refreshHandleState) {
        // SharedTransitionLayout：Tab 级 NavHost 的共享元素容器（列表卡 ↔ 详情页头部容器变换）。
        // 必须用 Layout 版而非裸 SharedTransitionScope——后者要求 content 手动使用其 Modifier，
        // 否则 "Uninitialized LayoutCoordinates" 崩溃。
        SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
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
                                // 按路由精确比较（而非 index）：子页面（非 Tab 路由）点任何
                                // Tab 都要正常导航，特别是点「今日」返回起始页。
                                if (item.route != currentTabRoute) {
                                    // ⚠️ 起始 Tab（今日）必须关闭 restoreState：
                                    // NavController 里 popUpTo(saveState) 会把刚弹出的栈
                                    // 同时注册到 backStackMap[popUpTo目标id]，而 restoreState
                                    // 检查在其后执行——目标==起始页时会立刻把刚弹掉的栈恢复
                                    // 回来，导航自我抵消成 no-op（点「今日」无响应的根因）。
                                    val isStartTab =
                                        item.route == tabNavController.graph.startDestinationRoute
                                    tabNavController.navigate(item.route) {
                                        popUpTo(tabNavController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = !isStartTab
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
            startDestination = "today",
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
            enterTransition = { NavTransitions.enter() },
            exitTransition = { NavTransitions.exit() },
            popEnterTransition = { NavTransitions.popEnter() },
            popExitTransition = { NavTransitions.popExit() },
        ) {
            composable(
                route = "today",
                enterTransition = TabEnterTransition,
                exitTransition = TabExitTransition,
                popEnterTransition = TabEnterTransition,
                popExitTransition = TabExitTransition,
            ) {
                CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                TodayScreen(
                    onOpenPortfolio = { tabNavController.navigate("portfolio") },
                    onOpenStock = { code -> tabNavController.navigate("stockDetail/$code") },
                    onOpenAddStock = { tabNavController.navigate("addStock") },
                    onOpenIncome = { tabNavController.navigate("income") },
                    onOpenGridPlan = { code -> tabNavController.navigate("gridPlanFor/$code") },
                )
                }
            }
            composable(
                route = "portfolio",
                enterTransition = TabEnterTransition,
                exitTransition = TabExitTransition,
                popEnterTransition = TabEnterTransition,
                popExitTransition = TabExitTransition,
            ) {
                CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
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
            composable(
                route = "income",
                enterTransition = TabEnterTransition,
                exitTransition = TabExitTransition,
                popEnterTransition = TabEnterTransition,
                popExitTransition = TabExitTransition,
            ) {
                IncomeScreen()
            }
            composable(
                route = "ai",
                enterTransition = TabEnterTransition,
                exitTransition = TabExitTransition,
                popEnterTransition = TabEnterTransition,
                popExitTransition = TabExitTransition,
            ) {
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
            composable(
                route = "settings",
                enterTransition = TabEnterTransition,
                exitTransition = TabExitTransition,
                popEnterTransition = TabEnterTransition,
                popExitTransition = TabExitTransition,
            ) {
                SettingsScreen(
                    onOpenAlertEvalSettings = { tabNavController.navigate("alertEvalSettings") },
                    onOpenLlmStrategySettings = { tabNavController.navigate("llmStrategySettings") },
                    onOpenDataSettings = { tabNavController.navigate("dataSettings") },
                    onOpenTransactionHistory = { rootNavController.navigate(Routes.TRANSACTION_HISTORY) },
                    onOpenGridPlan = { rootNavController.navigate(Routes.GRID_PLAN) },
                    onOpenAchievements = { tabNavController.navigate("achievements") }
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
                    onOpenCacheManagement = { tabNavController.navigate("cacheManagement") },
                    onOpenErrorLogs = { tabNavController.navigate("errorLogs") },
                    onOpenOcrDebug = { tabNavController.navigate("ocrDebug") }
                )
            }
            composable("cacheManagement") {
                CacheManagementScreen(onBack = { tabNavController.popBackStack() })
            }
            composable("errorLogs") {
                ErrorLogScreen(onBack = { tabNavController.popBackStack() })
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
                CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                StockDetailScreen(
                    stockCode = code,
                    onBack = { tabNavController.popBackStack() },
                    onEditHolding = { c -> tabNavController.navigate("editHolding/$c") },
                    onOpenDripSimulation = { c -> tabNavController.navigate("dripSimulation/$c") },
                    onOpenGridPlan = { c -> tabNavController.navigate("gridPlanFor/$c") },
                    onOpenNotificationSettings = { c -> tabNavController.navigate("stockNotificationSettings/$c") }
                )
                }
            }
            composable(
                route = "dripSimulation/{code}",
                arguments = listOf(navArgument("code") { type = NavType.StringType })
            ) {
                DripSimulationScreen(onBack = { tabNavController.popBackStack() })
            }
            composable(
                route = "gridPlanFor/{code}",
                arguments = listOf(navArgument("code") { type = NavType.StringType })
            ) {
                GridPlanScreen(
                    onBack = { tabNavController.popBackStack() },
                    onAddTransaction = { code, price, shares ->
                        tabNavController.navigate(
                            "editHolding/$code?buyPrice=${"%.2f".format(price)}&buyShares=$shares"
                        )
                    }
                )
            }
            composable(
                route = "editHolding/{code}?buyPrice={buyPrice}&buyShares={buyShares}",
                arguments = listOf(
                    navArgument("code") { type = NavType.StringType },
                    navArgument("buyPrice") { type = NavType.StringType; defaultValue = "" },
                    navArgument("buyShares") { type = NavType.StringType; defaultValue = "" }
                )
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
