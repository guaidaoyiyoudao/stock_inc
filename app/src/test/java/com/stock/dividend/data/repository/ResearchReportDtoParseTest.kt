package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.stock.dividend.data.remote.dto.ResearchReportResponse
import org.junit.Test

/**
 * 研报 DTO 解析测试——锁定字段映射与单位。
 *
 * Fixture 取自 **实测**（2026-08-02 东方财富 reportapi，茅台 600519）：
 * EPS 单位「元/股」、PE 单位「倍」，均为字符串（可能空），由 Repository 转 Double。
 */
class ResearchReportDtoParseTest {

    private val gson = Gson()

    // ── 研报列表（实测茅台，已裁剪核心字段）──────────────────────────
    private val reportJson = """
        {"hits":214,"size":5,"data":[{
          "title":"需求根基稳固，市场化定价持续兑现",
          "stockName":"贵州茅台","stockCode":"600519",
          "orgCode":"80036717","orgName":"中邮证券有限责任公司","orgSName":"中邮证券",
          "publishDate":"2026-07-23 00:00:00.000","infoCode":"AP202607231827290069",
          "predictNextTwoYearEps":"73.96","predictNextTwoYearPe":"17.65",
          "predictNextYearEps":"69.76","predictNextYearPe":"18.71",
          "predictThisYearEps":"67.19","predictThisYearPe":"19.42",
          "emRatingName":"买入"
        }]}
    """.trimIndent()

    @Test
    fun `research report parses title org eps pe and rating`() {
        val resp = gson.fromJson(reportJson, ResearchReportResponse::class.java)
        val item = resp.data!!.first()
        assertThat(item.title).contains("需求根基稳固")
        assertThat(item.stockCode).isEqualTo("600519")
        assertThat(item.orgSName).isEqualTo("中邮证券")
        // EPS/PE 为字符串，Repository 转换前保持原样
        assertThat(item.predictThisYearEps).isEqualTo("67.19")
        assertThat(item.predictThisYearPe).isEqualTo("19.42")
        assertThat(item.emRatingName).isEqualTo("买入")
    }

    @Test
    fun `string eps converts to double via toDoubleOrNull`() {
        // 模拟 ResearchRepository.toResearchReport 的转换逻辑
        assertThat("67.19".toDoubleOrNull()).isEqualTo(67.19)
        assertThat("".toDoubleOrNull()).isNull()
        assertThat(null?.toDoubleOrNull()).isNull()
    }
}
