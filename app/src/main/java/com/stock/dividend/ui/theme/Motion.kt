package com.stock.dividend.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing

/**
 * 全 App 统一动效 token（Material Motion 节奏）。
 *
 * 所有新动效一律引用此处常量，禁止散落魔法数字，保证全局节奏一致：
 * - [DurationShort]：小型反馈（按压回弹、横轴切换）
 * - [DurationMedium]：常规过渡（数字滚动、导航转场、共享元素）
 * - [DurationLong]：大画幅入场（图表逐根浮现、甜甜圈扫开）
 */
object Motion {

    /** 常规过渡时长（ms）：数字滚动、导航转场。 */
    const val DurationMedium = 350

    /** 小反馈时长（ms）：按压缩放、横轴切换。 */
    const val DurationShort = 200

    /** 大画幅入场时长（ms）：K线逐根浮现、甜甜圈扫开。 */
    const val DurationLong = 600

    /** 标准「进场减速」曲线：快速出发、柔和停下（入场动画默认）。 */
    val EmphasizedDecelerate: Easing = LinearOutSlowInEasing

    /** 标准「离场加速」曲线：缓慢出发、快速离开（退场动画默认）。 */
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 标准过渡曲线（进出场共用）。 */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 线性（无限循环类动画专用：shimmer 高光、波浪滚动）。 */
    val Linear: Easing = LinearEasing
}
