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

        assertThat(mergeByReportDate(cached, emptyList()) { it.date }).isEqualTo(cached)
    }

    @Test
    fun `empty cached returns remote`() {
        val remote = listOf(P("2024-12-31", 1))

        assertThat(mergeByReportDate(emptyList(), remote) { it.date }).isEqualTo(remote)
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
}
