package com.stock.dividend.ui.screen

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 缓存管理页 internal 纯函数单测（Composable 本体不进 JVM 单测，项目惯例同 KlineYieldChartTest）。 */
class CacheManagementScreenTest {

    @Test
    fun `formatEntryCount uses thousands separator`() {
        assertThat(formatEntryCount(0L)).isEqualTo("0")
        assertThat(formatEntryCount(640L)).isEqualTo("640")
        assertThat(formatEntryCount(12345L)).isEqualTo("12,345")
    }
}
