package com.stock.dividend.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import com.stock.dividend.ui.theme.Motion

/**
 * 全局导航转场（两级 NavHost 共用）。
 *
 * - 二级页（详情/设置等）：新页自右侧 1/2 屏滑入 + fade；下层页向左轻移 1/4 屏形成视差；
 *   返回时反向，符合平台「卡片式前进/后退」心智。
 * - 五个主 Tab 之间：纯 fade（Tab 切换语义，不做水平位移）。
 */
object NavTransitions {

    fun enter(): EnterTransition = slideInHorizontally(
        animationSpec = tween(Motion.DurationMedium, easing = Motion.EmphasizedDecelerate),
        initialOffsetX = { it / 2 },
    ) + fadeIn(tween(Motion.DurationMedium))

    fun exit(): ExitTransition = slideOutHorizontally(
        animationSpec = tween(Motion.DurationMedium, easing = Motion.EmphasizedAccelerate),
        targetOffsetX = { -it / 4 },
    ) + fadeOut(tween(Motion.DurationMedium))

    fun popEnter(): EnterTransition = slideInHorizontally(
        animationSpec = tween(Motion.DurationMedium, easing = Motion.EmphasizedDecelerate),
        initialOffsetX = { -it / 4 },
    ) + fadeIn(tween(Motion.DurationMedium))

    fun popExit(): ExitTransition = slideOutHorizontally(
        animationSpec = tween(Motion.DurationMedium, easing = Motion.EmphasizedAccelerate),
        targetOffsetX = { it / 2 },
    ) + fadeOut(tween(Motion.DurationMedium))
}

/** 主 Tab 路由（today/portfolio/income/ai/settings）切换用纯 fade。 */
val TabEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(Motion.DurationMedium, easing = Motion.Standard))
}

val TabExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(Motion.DurationMedium, easing = Motion.Standard))
}
