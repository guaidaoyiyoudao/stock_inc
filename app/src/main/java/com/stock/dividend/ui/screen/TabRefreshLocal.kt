package com.stock.dividend.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf

/**
 * 当前激活 Tab 注册的刷新入口，供悬浮刷新按钮读取。
 *
 * - 可刷新的 Tab（持仓 / 自选 / 日历）进入时通过 [registerTabRefresh] 把自己的
 *   刷新回调与 loading 状态写入由 MainScaffold 提供的 [MutableState]。
 * - 离开时 [registerTabRefresh] 的 DisposableEffect 自动清空，按钮随之隐藏。
 * - 无刷新能力的 Tab（成就 / 设置）不注册，按钮保持隐藏。
 *
 * 采用单个可变持有者 + CompositionLocal 注入，避免给每个 Screen 增加回调参数。
 */
internal data class RefreshHandle(
    val refresh: () -> Unit,
    val isRefreshing: Boolean
)

internal val LocalTabRefreshRegistrar = compositionLocalOf<MutableState<RefreshHandle?>> {
    error("LocalTabRefreshRegistrar not provided")
}

/**
 * 由可刷新 Tab 调用：注册刷新回调 + loading 状态。
 * 每次 recomposition 通过 SideEffect 同步最新值；离开时 DisposableEffect 自动清空。
 */
@Composable
internal fun registerTabRefresh(
    refresh: () -> Unit,
    isRefreshing: Boolean
) {
    val registrar = LocalTabRefreshRegistrar.current
    SideEffect {
        registrar.value = RefreshHandle(refresh, isRefreshing)
    }
    DisposableEffect(registrar) {
        onDispose {
            registrar.value = null
        }
    }
}
