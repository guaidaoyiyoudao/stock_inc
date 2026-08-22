package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** mergeByReportDate 纯函数：不可变历史按报告期合并（远端覆盖同期/缓存独有旧期保留/升序）。 */
class HistoryCacheMergeTest {

    private data class P(val date: String, val v: Int)

    @Test
    fun `remote period overrides cached same date`() {
        val merged = mergeByReportDate(
            cached = listOf(P("2024-12-31", 1)),
            remote = listOf(P("2024-12-31", 2), P("2025-12-31", 3)),
            dateOf = { it.date }
        )

        assertThat(merged).containsExactly(P("2024-12-31", 2), P("2025-12-31", 3)).inOrder()
    }

    @Test
    fun `cached-only older periods preserved`() {
        val merged = mergeByReportDate(
            cached = listOf(P("2022-12-31", 1), P("2023-12-31", 2), P("2024-12-31", 3)),
            remote = listOf(P("2024-12-31", 30), P("2025-12-31", 4)),
            dateOf = { it.date }
        )

        assertThat(merged.map { it.date }).containsExactly(
            "2022-12-31", "2023-12-31", "2024-12-31", "2025-12-31"
        ).inOrder()
        // 远端窗口没返回的旧期从缓存续接，不随刷新丢失
        assertThat(merged.first { it.date == "2022-12-31" }.v).isEqualTo(1)
    }

    @Test
    fun `empty remote returns cached`() {
        val cached = listOf(P("2024-12-31", 1))

        assertThat(mergeByReportDate(cached, emptyList(), dateOf = { it.date })).isEqualTo(cached)
    }

    @Test
    fun `empty cached returns remote`() {
        val remote = listOf(P("2024-12-31", 1))

        assertThat(mergeByReportDate(emptyList(), remote, dateOf = { it.date })).isEqualTo(remote)
    }

    @Test
    fun `result sorted ascending by date`() {
        val merged = mergeByReportDate(
            cached = listOf(P("2024-12-31", 1)),
            remote = listOf(P("2023-12-31", 0), P("2025-12-31", 2)),
            dateOf = { it.date }
        )

        assertThat(merged.map { it.date }).containsExactly("2023-12-31", "2024-12-31", "2025-12-31").inOrder()
    }

    // ---------- 2026-08-20 审计 M5 修复：repairRemote 字段保底 ----------

    @Test
    fun `repairRemote falls back to cached fields when remote field is null`() {
        // 远端子接口失败（降级空表）时同期记录部分字段为 null——回退缓存已有值，防字段级回退被持久化
        data class F(val date: String, val a: Double?, val b: Double?)
        val merged = mergeByReportDate(
            cached = listOf(F("2024-12-31", 1.0, 2.0)),
            remote = listOf(F("2024-12-31", null, 20.0)),
            dateOf = { it.date },
            repairRemote = { r, c -> r.copy(a = r.a ?: c.a) }
        )
        val period = merged.single()
        assertThat(period.a).isEqualTo(1.0)   // 远端 null → 缓存值保底
        assertThat(period.b).isEqualTo(20.0)  // 远端有值 → 远端覆盖
    }

    @Test
    fun `repairRemote is not applied when no cached record of same period`() {
        // 缓存没有同期记录时（新期次），repairRemote 无从回退，直接用远端记录
        data class F(val date: String, val a: Double?)
        val merged = mergeByReportDate(
            cached = listOf(F("2023-12-31", 1.0)),
            remote = listOf(F("2024-12-31", null)),
            dateOf = { it.date },
            repairRemote = { r, c -> r.copy(a = r.a ?: c.a) }
        )
        assertThat(merged.single { it.date == "2024-12-31" }.a).isNull()
    }
}
