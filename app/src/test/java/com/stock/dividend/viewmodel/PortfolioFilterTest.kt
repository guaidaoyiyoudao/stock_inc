package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.StockEntity
import org.junit.Test

class PortfolioFilterTest {

    private fun item(code: String, industry: String) = PortfolioItem(
        code = code, name = code, marketCode = "1", shares = 100,
        costPerShare = 10.0, industry = industry, totalCost = 1000.0, targetWeight = 0.0
    )

    private fun watch(code: String, industry: String) = StockEntity(
        code = code, name = code, marketCode = "1", shares = 0, industry = industry
    )

    private val items = listOf(
        item("sh.600036", "银行"),
        item("sh.601318", "保险"),
        item("sh.600519", "")   // 未分类
    )

    private val watchlist = listOf(
        watch("sz.000001", "银行"),
        watch("sz.000002", "")
    )

    private val tagsByCode = mapOf(
        "sh.600036" to listOf("高息"),
        "sh.601318" to listOf("白马"),
        "sz.000001" to listOf("高息", "白马")
    )

    @Test
    fun `empty selections returns all`() {
        val (fi, fw) = applyPortfolioFilter(
            items, watchlist, tagsByCode,
            selectedIndustries = emptySet(), selectedTags = emptySet()
        )
        assertThat(fi).hasSize(3)
        assertThat(fw).hasSize(2)
    }

    @Test
    fun `industry filter narrows both lists, unclassified bucket`() {
        val (fi, fw) = applyPortfolioFilter(
            items, watchlist, tagsByCode,
            selectedIndustries = setOf("银行"), selectedTags = emptySet()
        )
        assertThat(fi.map { it.code }).containsExactly("sh.600036")
        assertThat(fw.map { it.code }).containsExactly("sz.000001")

        val (fi2, _) = applyPortfolioFilter(
            items, watchlist, tagsByCode,
            selectedIndustries = setOf("未分类"), selectedTags = emptySet()
        )
        assertThat(fi2.map { it.code }).containsExactly("sh.600519")
    }

    @Test
    fun `multiple industries are OR`() {
        val (fi, _) = applyPortfolioFilter(
            items, watchlist, tagsByCode,
            selectedIndustries = setOf("银行", "保险"), selectedTags = emptySet()
        )
        assertThat(fi.map { it.code }).containsExactly("sh.600036", "sh.601318")
    }

    @Test
    fun `tags filter is OR within tags`() {
        val (fi, fw) = applyPortfolioFilter(
            items, watchlist, tagsByCode,
            selectedIndustries = emptySet(), selectedTags = setOf("高息", "白马")
        )
        // sh.600036=高息 ✓ ; sh.601318=白马 ✓ ; sh.600519 无标签 ✗
        assertThat(fi.map { it.code }).containsExactly("sh.600036", "sh.601318")
        assertThat(fw.map { it.code }).containsExactly("sz.000001")
    }

    @Test
    fun `cross-dimension is AND`() {
        val (fi, fw) = applyPortfolioFilter(
            items, watchlist, tagsByCode,
            selectedIndustries = setOf("银行"), selectedTags = setOf("高息")
        )
        // 银行 ∩ 高息: sh.600036(银行+高息) ✓ ; sz.000001(银行+高息) ✓
        assertThat(fi.map { it.code }).containsExactly("sh.600036")
        assertThat(fw.map { it.code }).containsExactly("sz.000001")

        val (fi2, _) = applyPortfolioFilter(
            items, watchlist, tagsByCode,
            selectedIndustries = setOf("银行"), selectedTags = setOf("白马")
        )
        // 银行 ∩ 白马: sh.600036(银行但只高息) ✗
        assertThat(fi2).isEmpty()
    }
}
