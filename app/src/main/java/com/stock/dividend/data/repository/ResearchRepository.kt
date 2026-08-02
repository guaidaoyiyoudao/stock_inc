package com.stock.dividend.data.repository

import com.stock.dividend.data.remote.AnnouncementApi
import com.stock.dividend.data.remote.ResearchApi
import com.stock.dividend.data.remote.dto.ResearchReportResponse
import javax.inject.Inject
import javax.inject.Singleton

/** 单条研报摘要（已解析，缺失字段为 null）。 */
data class ResearchReport(
    val title: String?,
    val stockCode: String?,
    val stockName: String?,
    val orgName: String?,            // 研究机构简称
    val publishDate: String?,        // "2026-07-23"
    val predictThisYearEps: Double?,
    val predictThisYearPe: Double?,
    val predictNextYearEps: Double?,
    val predictNextYearPe: Double?,
    val rating: String?              // 评级（如「买入」）
)

/** 单条公告摘要。 */
data class StockAnnouncement(
    val title: String?,
    val noticeDate: String?,         // "2026-07-18"
    val artCode: String?
)

/**
 * 研报与公告数据（reportapi + np-anotice-stock）。网络失败一律返回空（红线 #2）。
 * 注意：两个接口按「6 位代码」查询，非 sh./sz. 前缀格式。
 */
@Singleton
class ResearchRepository @Inject constructor(
    private val researchApi: ResearchApi,
    private val announcementApi: AnnouncementApi,
) {
    /**
     * 个股研报。取近 [recentDays] 天、最多 [limit] 条。
     * @param code6 6 位股票代码
     */
    suspend fun fetchReports(code6: String, limit: Int = 10, recentDays: Int = 1095): List<ResearchReport> {
        val today = java.time.LocalDate.now()
        val begin = today.minusDays(recentDays.toLong()).toString()
        val end = today.plusDays(1).toString()
        return runCatching {
            researchApi.getReports(code = code6, pageSize = limit.toString(), beginTime = begin, endTime = end)
                .data.orEmpty()
                .map { it.toResearchReport() }
        }.getOrDefault(emptyList())
    }

    /** 个股公告。 */
    suspend fun fetchAnnouncements(code6: String, limit: Int = 10): List<StockAnnouncement> =
        runCatching {
            announcementApi.getAnnouncements(stockList = code6, pageSize = limit.toString())
                .data?.list.orEmpty()
                .map {
                    StockAnnouncement(
                        title = it.title,
                        noticeDate = it.noticeDate?.substringBefore(" "),
                        artCode = it.artCode
                    )
                }
        }.getOrDefault(emptyList())

    private fun ResearchReportResponse.Item.toResearchReport() = ResearchReport(
        title = title,
        stockCode = stockCode,
        stockName = stockName,
        orgName = orgSName,
        publishDate = publishDate?.substringBefore(" "),
        predictThisYearEps = predictThisYearEps?.toDoubleOrNull(),
        predictThisYearPe = predictThisYearPe?.toDoubleOrNull(),
        predictNextYearEps = predictNextYearEps?.toDoubleOrNull(),
        predictNextYearPe = predictNextYearPe?.toDoubleOrNull(),
        rating = emRatingName
    )
}
