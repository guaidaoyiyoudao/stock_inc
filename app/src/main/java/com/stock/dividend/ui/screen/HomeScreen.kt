package com.stock.dividend.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.ui.component.DividendSummaryCard
import com.stock.dividend.ui.component.EmptyStateView
import com.stock.dividend.ui.component.FireProgressCard
import com.stock.dividend.ui.component.StockCard
import com.stock.dividend.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
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
                title = {
                    Text(
                        text = "我的股息",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = uiState.stocks.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = onAddStockClick,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("添加股票", style = MaterialTheme.typography.labelLarge) }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.stocks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                EmptyStateView(onAddClick = onAddStockClick)
            }
        } else {
            PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = { viewModel.refreshQuotes() },
                modifier = Modifier
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 88.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        FireProgressCard(
                            targetAmount = uiState.fireGoal?.targetAmount,
                            forecastTotal = uiState.forecastTotal,
                            progress = uiState.fireProgress,
                            onClick = onFireCardClick
                        )
                    }

                    item {
                        DividendSummaryCard(
                            totalAmount = uiState.forecastTotal,
                            totalMarketValue = uiState.totalMarketValue
                        )
                    }

                    item {
                        Text(
                            text = "关注列表",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )
                    }

                    items(
                        items = uiState.stocks,
                        key = { it.code }
                    ) { stock ->
                        SwipeToDismissStockItem(
                            stock = stock,
                            forecastIncome = uiState.stockForecasts[stock.code]?.forecastIncome,
                            marketValue = uiState.stockForecasts[stock.code]?.marketValue,
                            onDismiss = { viewModel.deleteStock(stock) },
                            onClick = { onStockClick(stock.code) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissStockItem(
    stock: StockEntity,
    forecastIncome: Double? = null,
    marketValue: Double? = null,
    onDismiss: () -> Unit,
    onClick: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                onDismiss()
                true
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                    else -> Color.Transparent
                }, label = "bg"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = "删除",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        enableDismissFromStartToEnd = false
    ) {
        StockCard(
            name = stock.name,
            code = stock.code,
            shares = stock.shares,
            forecastIncome = forecastIncome?.let { "¥${"%.2f".format(it)}" },
            marketValue = marketValue?.let { "¥${"%,.2f".format(it)}" },
            lastUpdated = stock.lastUpdated,
            onClick = onClick
        )
    }
}
