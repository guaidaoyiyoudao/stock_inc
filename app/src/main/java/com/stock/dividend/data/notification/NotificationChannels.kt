package com.stock.dividend.data.notification

import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_ABOVE
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_BELOW

/** 通知 channel id 与用户可见名称。按规则类型路由，支持用户在系统设置里分类调权。 */
object NotificationChannels {
    const val PRICE_EVENTS = "price_events"        // 价格事件：PRICE_ABOVE / PRICE_BELOW / BOLL_WEEKLY_UPPER
    const val DIVIDEND_EVENTS = "dividend_events"  // 股息率事件：DIVIDEND_YIELD_THRESHOLD / BELOW
    const val DIVIDEND_PAYOUTS = "dividend_payouts"// 分红事件（预留：除权除息精确提醒）

    /** 已弃用的旧 channel，保留以免已发布设置丢失；新规则一律用上面三个 */
    const val LEGACY_DIVIDEND_ALERTS = "dividend_alerts"

    /** 用户可见名称（createChannel 时用），明示分红事件为预留状态以管理预期 */
    val CHANNEL_NAMES = mapOf(
        PRICE_EVENTS to "价格事件",
        DIVIDEND_EVENTS to "股息率事件",
        DIVIDEND_PAYOUTS to "分红事件（即将开放）",
        LEGACY_DIVIDEND_ALERTS to "股息率提醒（旧）",
    )
}

/** 按 ruleType 路由到对应 channel；未知类型兜底价格事件。 */
fun channelFor(ruleType: String): String = when (ruleType) {
    NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD,
    NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD -> NotificationChannels.DIVIDEND_EVENTS
    else -> NotificationChannels.PRICE_EVENTS
}

/**
 * 网格到档提醒的「规则类型」标识。**不落 notification_rules 表**（提醒去重状态存在
 * grid_plans.lastNotifiedLevelPrice），仅用于复用 sendNotificationRuleAlert 的
 * 文案/渠道路由。
 */
const val GRID_NEXT_LEVEL_ALERT = "GRID_NEXT_LEVEL"

/**
 * 网格**卖出档**到档提醒的「规则类型」标识（波段模式）。同样不落表——去重状态存
 * grid_plans.lastNotifiedSellLevelPrice。仅用于文案/渠道路由。
 */
const val GRID_SELL_LEVEL_ALERT = "GRID_SELL_LEVEL"
