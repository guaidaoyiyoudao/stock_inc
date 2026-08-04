package com.stock.dividend.ui.screen

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsScreenModelTest {

    @Test
    fun `bottom navigation exposes settings instead of notifications`() {
        val settingsItem = bottomNavItems.last()

        assertThat(settingsItem.route).isEqualTo("settings")
        assertThat(settingsItem.label).isEqualTo("设置")
    }

    @Test
    fun `settings page groups entries by function in fixed order`() {
        // 按渲染顺序：提醒与评估 / AI 与策略 / 数据 / 交易记录 / 网格交易
        assertThat(settingsGroupTitles).containsExactly(
            "提醒与评估",
            "AI 与策略",
            "数据",
            "交易记录",
            "网格交易",
        ).inOrder()
    }
}
