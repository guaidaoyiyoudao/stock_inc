@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.stock.dividend.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 跨页面共享元素动画的 scope 注入（借鉴 TransformationLayout 模式，Compose 1.7 内置实现）。
 *
 * MainScaffold 的 Tab 级 NavHost 外层提供 [LocalSharedTransitionScope]，
 * 各 composable 路由内容提供 [LocalNavAnimatedVisibilityScope]；
 * 两个 scope 都存在时 [stockCardSharedBounds] 才生效，否则原样返回（可独立预览/测试）。
 */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

val LocalNavAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 个股卡 ↔ 个股详情页头部横幅的容器变换；key 统一 `stock-card-$code`。
 * 列表侧（StockCard/持仓卡/今日页行情卡）与详情侧调用同一 key 即成对。
 */
@Composable
fun Modifier.stockCardSharedBounds(code: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(shared) {
        sharedBounds(
            rememberSharedContentState(key = "stock-card-$code"),
            animatedVisibilityScope = animated,
        )
    }
}
