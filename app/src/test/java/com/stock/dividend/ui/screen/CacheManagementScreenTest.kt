package com.stock.dividend.ui.screen

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.repository.CacheKind
import com.stock.dividend.viewmodel.CacheEntry
import org.junit.Test

/** 缓存管理页 internal 纯函数单测（Composable 本体不进 JVM 单测，项目惯例同 KlineYieldChartTest）。 */
class CacheManagementScreenTest {

    @Test
    fun `formatEntryCount uses thousands separator`() {
        assertThat(formatEntryCount(0L)).isEqualTo("0")
        assertThat(formatEntryCount(640L)).isEqualTo("640")
        assertThat(formatEntryCount(12345L)).isEqualTo("12,345")
    }

    @Test
    fun `cacheSegmentFractions returns empty when all zero`() {
        val entries = CacheKind.entries.map { CacheEntry(it, 0L, it.permanent) }
        assertThat(cacheSegmentFractions(entries)).isEmpty()
    }

    @Test
    fun `cacheSegmentFractions drops zero kinds and keeps declaration order`() {
        val entries = listOf(
            CacheEntry(CacheKind.PRICE, 0L, permanent = false),
            CacheEntry(CacheKind.SEARCH, 10L, permanent = false),
            CacheEntry(CacheKind.KLINE, 30L, permanent = true),
            CacheEntry(CacheKind.DIVIDENDS, 60L, permanent = true),
        )
        val segments = cacheSegmentFractions(entries)

        assertThat(segments.map { it.kind })
            .containsExactly(CacheKind.SEARCH, CacheKind.KLINE, CacheKind.DIVIDENDS)
            .inOrder()
        assertThat(segments[0].fraction).isWithin(1e-9).of(0.1)
        assertThat(segments[1].fraction).isWithin(1e-9).of(0.3)
        assertThat(segments[2].fraction).isWithin(1e-9).of(0.6)
    }

    @Test
    fun `cacheSegmentFractions single kind fills whole bar`() {
        val segments = cacheSegmentFractions(listOf(CacheEntry(CacheKind.KLINE, 640L, permanent = true)))

        assertThat(segments).hasSize(1)
        assertThat(segments.single().kind).isEqualTo(CacheKind.KLINE)
        assertThat(segments.single().fraction).isWithin(1e-9).of(1.0)
    }
}
