package com.stock.dividend.data.notification

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_ABOVE
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_BELOW
import org.junit.Test

class NotificationChannelsTest {

    @Test
    fun priceRules_route_to_price_events() {
        assertThat(channelFor(NOTIFICATION_RULE_TYPE_PRICE_ABOVE)).isEqualTo(NotificationChannels.PRICE_EVENTS)
        assertThat(channelFor(NOTIFICATION_RULE_TYPE_PRICE_BELOW)).isEqualTo(NotificationChannels.PRICE_EVENTS)
        assertThat(channelFor(NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER)).isEqualTo(NotificationChannels.PRICE_EVENTS)
    }

    @Test
    fun dividendYieldRules_route_to_dividend_events() {
        assertThat(channelFor(NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD))
            .isEqualTo(NotificationChannels.DIVIDEND_EVENTS)
        assertThat(channelFor(NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD))
            .isEqualTo(NotificationChannels.DIVIDEND_EVENTS)
    }

    @Test
    fun unknownRuleType_falls_back_to_price_events() {
        assertThat(channelFor("some_unknown_type")).isEqualTo(NotificationChannels.PRICE_EVENTS)
    }

    @Test
    fun channel_names_cover_all_four_channels() {
        assertThat(NotificationChannels.CHANNEL_NAMES).hasSize(4)
        assertThat(NotificationChannels.CHANNEL_NAMES[NotificationChannels.PRICE_EVENTS]).isEqualTo("价格事件")
        assertThat(NotificationChannels.CHANNEL_NAMES[NotificationChannels.DIVIDEND_EVENTS]).isEqualTo("股息率事件")
        assertThat(NotificationChannels.CHANNEL_NAMES[NotificationChannels.DIVIDEND_PAYOUTS])
            .isEqualTo("分红事件（即将开放）")
        assertThat(NotificationChannels.CHANNEL_NAMES[NotificationChannels.LEGACY_DIVIDEND_ALERTS])
            .isEqualTo("股息率提醒（旧）")
    }
}
