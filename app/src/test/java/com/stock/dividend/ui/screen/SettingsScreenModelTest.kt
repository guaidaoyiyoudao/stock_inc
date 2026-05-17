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
    fun `settings page exposes notification and data management entries`() {
        assertThat(settingsEntries.map { it.title }).containsExactly(
            "通知设置",
            "数据管理"
        ).inOrder()
    }
}
