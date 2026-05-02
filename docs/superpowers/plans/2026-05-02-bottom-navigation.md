# Bottom Navigation Implementation Plan

**Goal:** Replace top TabRow with bottom NavigationBar using nested NavHost pattern.

**Architecture:** Root NavHost ("main") → MainScaffold (NavigationBar + nested NavHost for 3 tabs). Detail pages push inside tab nav graph.

**Tech Stack:** Kotlin 2.0.21, Compose BOM 2024.12.01, Navigation Compose 2.8.5, Material3 1.3.1

---

### Task 1: Create MainScaffold.kt

**Files:**
- Create: `app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt`

```kotlin
package com.stock.dividend.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.TrendingUp
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
    BottomNavItem("income", "股息收入", Icons.Filled.TrendingUp),
    BottomNavItem("achievements", "成就", Icons.Filled.EmojiEvents)
)

@Composable
fun MainScaffold(rootNavController: NavHostController) {
    val tabNavController = rememberNavController()
    val tabBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentTabRoute = tabBackStackEntry?.destination?.route

    val selectedTabIndex = bottomNavItems.indexOfFirst { it.route == currentTabRoute }.coerceAtLeast(0)

    val fabVisible = currentTabRoute in listOf("watchlist", "income")
    val fabLabel = if (currentTabRoute == "watchlist") "添加股票" else "添加收入"

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = if (isSystemInDarkTheme())
                    GlassColors.DarkSurface else GlassColors.LightSurface,
                tonalElevation = 0.dp,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder
                    else GlassColors.LightSurfaceBorder
                )
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
                        if (currentTabRoute == "watchlist") {
                            tabNavController.navigate("addStock")
                        } else {
                            // income tab - handled by IncomeScreen internally
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(fabLabel, style = MaterialTheme.typography.labelLarge) }
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
                IncomeScreen()
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
```

---

### Task 2: Split HomeScreen.kt into 3 screens

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt`

Convert HomeScreen to contain only WatchlistScreen, IncomeScreen, AchievementScreen composables.

WatchlistScreen contains: WatchlistContent + its ViewModel + the SwipeToDismissStockItem
IncomeScreen contains: IncomeTabContent + DividendIncomeViewModel  
AchievementScreen contains: AchievementTabContent + AchievementViewModel

Code for the three extracted composables (replacing the HomeScreen composable entirely):

```kotlin
@Composable
fun WatchlistScreen(
    onAddStockClick: () -> Unit,
    onStockClick: (String) -> Unit,
    onFireCardClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(uiState.deletedStock) {
        val deleted = uiState.deletedStock ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "已删除 ${deleted.name}",
            actionLabel = "撤销",
            duration = SnackbarDuration.Short
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.undoDelete()
            SnackbarResult.Dismissed -> viewModel.clearDeleted()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的持仓", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        GradientBackground(modifier = Modifier.fillMaxSize()) {
            if (uiState.stocks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    EmptyStateView(onAddClick = onAddStockClick)
                }
            } else {
                WatchlistContent(
                    uiState = uiState,
                    onStockClick = onStockClick,
                    onFireCardClick = onFireCardClick,
                    onDeleteStock = { viewModel.deleteStock(it) },
                    onRefresh = { viewModel.refreshQuotes() },
                    scrollBehavior = scrollBehavior,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
fun IncomeScreen(
    viewModel: DividendIncomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddIncomeDialog by remember { mutableStateOf(false) }
    var showCorrectDialog by remember { mutableStateOf(false) }
    var correctAmount by remember { mutableStateOf("") }
    var correctNote by remember { mutableStateOf("") }
    var addAmount by remember { mutableStateOf("") }
    var addNote by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("股息收入", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddIncomeDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.large,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("添加收入", style = MaterialTheme.typography.labelLarge) }
            )
        }
    ) { padding ->
        GradientBackground(modifier = Modifier.fillMaxSize()) {
            IncomeTabContent(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
        }

        if (showAddIncomeDialog) {
            // ... AddIncomeDialog (same as before)
        }
        if (state.showCorrectDialog) {
            // ... Correct dialog (same as before)
        }
    }
}

@Composable
fun AchievementScreen(
    viewModel: AchievementViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成就", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        GradientBackground(modifier = Modifier.fillMaxSize()) {
            AchievementTabContent(state = state, modifier = Modifier.padding(padding))
        }
    }
}
```

Then keep (modify WatchlistContent to accept modifier param, keep IncomeTabContent (add modifier param), keep AchievementTabContent (add modifier param), keep SwipeToDismissStockItem, keep AddIncomeDialog).

---

### Task 3: Rewrite AppNavigation.kt

```kotlin
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
            FireGoalSetupScreen(onBack = { rootNavController.popBackStack() })
        }
    }
}
```

---

### Task 4: Update MainActivity.kt

Remove the Surface wrapper since Scaffold handles its own background now. Replace:

```kotlin
StockDividendTheme {
    GradientBackground(modifier = Modifier.fillMaxSize()) {
        AppNavigation()
    }
}
```

Actually, keep StockDividendTheme but remove Surface since GradientBackground exists in each screen. The AppNavigation now contains Scaffold-based screens.

```kotlin
setContent {
    StockDividendTheme {
        AppNavigation()
    }
}
```

---

### Task 5: Verify compilation

Run: `./gradlew assembleDebug`
