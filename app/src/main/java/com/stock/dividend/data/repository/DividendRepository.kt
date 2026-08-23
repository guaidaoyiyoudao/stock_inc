package com.stock.dividend.data.repository

import com.google.gson.Gson
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.remote.DividendApi
import com.stock.dividend.data.remote.FundDividendApi
import com.stock.dividend.data.remote.TencentDividendApi
import com.stock.dividend.data.remote.dto.DividendResponse
import com.stock.dividend.data.remote.dto.TencentDividendItem
import com.stock.dividend.data.remote.dto.fuyaoMsToDateStringOrNull
import com.stock.dividend.data.remote.dto.toFuyaoThscodeOrNull
import com.stock.dividend.di.EastMoneyDividendApi
import com.stock.dividend.di.TencentDividendSource
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DividendRepository @Inject constructor(
    @TencentDividendSource private val tencentApi: TencentDividendApi,
    @EastMoneyDividendApi private val eastMoneyApi: DividendApi,
    private val fundDividendApi: FundDividendApi,
    private val fuyaoApi: com.stock.dividend.data.remote.FuyaoApi,
    private val fuyaoConfig: FuyaoConfig,
    private val dividendDao: DividendDao
) {
    /**
     * 拉取并写入分红记录。
     *
     * **主源分层**（2026-08-23 起）：同花顺扶摇为权威主源（股票=分红事件流一次拉全部已除权历史，
     * 替代腾讯三块分块；ETF/LOF=结构化分红记录，替代基金 f10 页 HTML 解析）；
     * 东财始终做 enrich（回填准确报告期/历史股息率快照/已排期未除权合并）；
     * 扶摇失败时降级腾讯（股票）/f10 HTML（基金）。
     *
     * **历史保留式写入**（历史分红不可变）：只定点删除本次结果覆盖到的行（同 id 或同除权日），
     * 拉取窗口之外的历史行永续累积，不随窗口滑动丢失；双源均无数据时不清库
     * （多为网络/反爬抖动，清空不可再生的历史记录比暂时不更新更糟）。
     */
    suspend fun fetchAndCacheDividends(stockCode: String, securityCode: String): Result<Unit> {
        return try {
            var usedEastMoneyFallback = false
            val entities = if (FundDividendParser.isExchangeTradedFund(stockCode)) {
                // ETF/LOF：扶摇结构化分红主源，失败降级基金 f10 页 HTML 解析
                val fromFuyao = if (fuyaoConfig.enabled) {
                    runCatching { fetchFundDividendsFromFuyao(stockCode) }.getOrNull()
                } else null
                fromFuyao ?: fetchFundDividends(stockCode)
            } else {
                val fromFuyao = if (fuyaoConfig.enabled) {
                    runCatching { fetchDividendsFromFuyao(stockCode) }.getOrNull()
                } else null
                if (fromFuyao != null) {
                    // 扶摇主源成功：东财仍按除权日对齐 enrich（报告期/股息率/已排期合并，见函数头）
                    enrichAndMergeFromEastMoney(stockCode, securityCode, fromFuyao)
                } else {
                    val fromTencent = fetchFromTencent(stockCode, securityCode)
                    usedEastMoneyFallback = fromTencent.isEmpty()
                    if (usedEastMoneyFallback) {
                        fetchFromEastMoney(stockCode, securityCode)
                    } else {
                        enrichAndMergeFromEastMoney(stockCode, securityCode, fromTencent)
                    }
                }
            }

            if (entities.isEmpty()) {
                return Result.success(Unit)
            }

            dividendDao.deleteByIds(stockCode, entities.map { it.id }.distinct())
            entities.mapNotNull { it.exDividendDate }.distinct().takeIf { it.isNotEmpty() }?.let {
                // 腾讯(id=code_exDate)与东财(id=code_reportDate)两种 id 方案按除权日跨源去重
                dividendDao.deleteByStockAndExDates(stockCode, it)
            }
            if (usedEastMoneyFallback) {
                // 东财全量路径携带预案信息：清洗已取消/失效的预案行（exDate=null 且不在本次结果中）。
                // 腾讯只返回已实施分红、不携带预案，不能据其清洗。
                dividendDao.deleteStalePendingByStock(stockCode, entities.map { it.id })
            }
            dividendDao.insertAll(entities)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(e.toUserMessage(), e))
        }
    }

    fun observeDividends(stockCode: String): Flow<List<DividendEntity>> {
        return dividendDao.observeByStock(stockCode)
    }

    /** 全表分红记录观察（股息日历等跨股场景用）。 */
    fun observeAllDividends(): Flow<List<DividendEntity>> {
        return dividendDao.observeAll()
    }

    /** 一次性读取该股全部分红记录（非 Flow，不触发网络刷新）。供数据平面同步读取用。 */
    suspend fun getDividends(stockCode: String): List<DividendEntity> {
        return try {
            dividendDao.getByStock(stockCode)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 全表有除权日的分红记录（今日信号/简报的分红倒计时用）。 */
    suspend fun getAllWithExDate(): List<DividendEntity> {
        return try {
            dividendDao.getAllWithExDate()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getLatestDividend(stockCode: String): DividendEntity? {
        return dividendDao.getLatestByStock(stockCode)
    }

    // ── 场内基金（ETF/LOF）专用源 ────────────────────────────────

    /**
     * 扶摇基金分红（主源，替代 f10 HTML 解析）：结构化 JSON，含税前每10份现金/登记日/除息日。
     * 每10份→每份 ÷10 为唯一单位换算（宪法原则 III 合规项）；除息日未定的记录不入库
     * （与股票侧「exDate 已定才入库」口径一致，未除权记录由东财已排期通道覆盖股票侧）。
     * 失败抛异常由调用方降级 f10 HTML。
     */
    private suspend fun fetchFundDividendsFromFuyao(stockCode: String): List<DividendEntity> {
        val thscode = stockCode.toFuyaoThscodeOrNull()
            ?: throw IllegalArgumentException("基金代码无法映射扶摇 thscode: $stockCode")
        val envelope = fuyaoApi.getFundDividends(thscode = thscode)
        check(envelope.isOk) { "扶摇基金分红失败: code=${envelope.code} ${envelope.message}" }
        return envelope.data?.item.orEmpty().mapNotNull { item ->
            val cashPerShare = item.perTenCashBeforeTax
                ?.let { it / 10.0 }
                ?.takeIf { it > 0.0 }
                ?: return@mapNotNull null
            val exDate = item.exDividendDateMs.fuyaoMsToDateStringOrNull() ?: return@mapNotNull null
            // reportDate 取公告年度（对应 f10 表「年份」列语义），公告日缺失时退除息日年份
            val year = item.publishDateMs.fuyaoMsToDateStringOrNull()?.substringBefore("-")
                ?: exDate.substringBefore("-")
            DividendEntity(
                id = "${stockCode}_${exDate}",
                stockCode = stockCode,
                reportDate = "$year-12-31",
                cashPerShare = cashPerShare,
                dividendYield = null,
                exDividendDate = exDate,
                recordDate = item.registrationDateMs.fuyaoMsToDateStringOrNull(),
                planNoticeDate = null,
                planStatus = null
            )
        }.distinctBy { it.id }
    }

    /**
     * 基金 f10 分红送配页拉取（候补源）。失败静默返回空（与主源同语义）——
     * f10 页对未分红基金会返回「暂无分红」空表，同样得到空列表、不误清历史。
     */
    private suspend fun fetchFundDividends(stockCode: String): List<DividendEntity> {
        val html = try {
            fundDividendApi.getFundDividendHtml(stockCode.substringAfter("."))
        } catch (_: Exception) {
            return emptyList()
        }
        return FundDividendParser.parseDividendHtml(html, stockCode)
    }

    // ── 同花顺扶摇（主源）──────────────────────────────────────

    /**
     * 扶摇股票分红事件流（主源）：**一次请求返回全部已除权历史**（替代腾讯三块各 2 年的分块请求），
     * `dividend_per_share` 已是每股现金口径（无每10股换算）。纯送股事件（现金=0）无字段承载、跳过。
     * 事件流**无报告期与预案状态**：reportDate 先按除权年占位，由 [enrichAndMergeFromEastMoney]
     * 按除权日对齐回填东财准确报告期；「已排期未除权」仍由东财通道合并。
     * 失败/信封非 0 抛异常 → 调用方降级腾讯路径。
     */
    private suspend fun fetchDividendsFromFuyao(stockCode: String): List<DividendEntity> {
        val thscode = stockCode.toFuyaoThscodeOrNull()
            ?: throw IllegalArgumentException("股票代码无法映射扶摇 thscode: $stockCode")
        val envelope = fuyaoApi.getAdjustmentFactors(thscode = thscode)
        check(envelope.isOk) { "扶摇分红事件流失败: code=${envelope.code} ${envelope.message}" }
        return envelope.data?.item.orEmpty().mapNotNull { item ->
            val cashPerShare = item.dividendPerShare?.takeIf { it > 0.0 } ?: return@mapNotNull null
            val exDate = item.exDateMs.fuyaoMsToDateStringOrNull() ?: return@mapNotNull null
            DividendEntity(
                id = "${stockCode}_${exDate}",
                stockCode = stockCode,
                reportDate = "${exDate.substringBefore("-")}-12-31", // 占位，东财 enrich 回填准确报告期
                cashPerShare = cashPerShare,
                dividendYield = null,
                exDividendDate = exDate,
                recordDate = null,
                planNoticeDate = null,
                planStatus = null
            )
        }.distinctBy { it.id }
    }

    // ── 腾讯（候补）──────────────────────────────────────────────

    private suspend fun fetchFromTencent(
        stockCode: String,
        securityCode: String
    ): List<DividendEntity> {
        val tencentCode = stockCode.toTencentCode() ?: "$securityCode"
        val dividends = try {
            fetchAllDividendsFromTencent(tencentCode)
        } catch (_: Exception) {
            // 腾讯失败不中断，交由东方财富回退
            return emptyList()
        }

        return dividends.mapNotNull { item ->
            val cashPerShare = item.fhSh?.toDoubleOrNull()?.let { it / 10.0 }
            if (cashPerShare == null || cashPerShare <= 0.0) return@mapNotNull null
            val exDate = item.cqr?.takeDateOnlyOrNull() ?: return@mapNotNull null
            val reportDate = item.nd?.takeYearEndDateOrNull() ?: exDate

            DividendEntity(
                id = "${stockCode}_${exDate}",
                stockCode = stockCode,
                reportDate = reportDate,
                cashPerShare = cashPerShare,
                dividendYield = null, // 腾讯无此字段，由东财元数据按除权日对齐补充（见 enrichDividendYieldFromEastMoney）
                exDividendDate = exDate,
                recordDate = item.djr?.takeDateOnlyOrNull(),
                planNoticeDate = null,
                planStatus = null
            )
        }
            // 按除权除息日去重（分块合并可能出现重复）
            .distinctBy { it.id }
    }

    /**
     * 腾讯 fqkline 单次上限约 640 个交易日（≈2.6 年），**超出时锚定最新端截头**——
     * 2026-08-20 审计实测：请求 3 年窗口（≈730 交易日）返回首根比请求起点晚 4 个月，
     * 落在截断洞里的除权分红（如中国移动 2023-09-01 的 10派22.247）**永久丢失**。
     * 故分**三块、每块 2 年**（≈487 交易日 < 640 必完整返回，不赌超窗截断行为，
     * 与 KlineRepository.FULL_FETCH_BARS 的纪律一致），端点相接覆盖近 6 年；
     * 块边界日重复请求由 [fetchTencentWindow] 返回 Map 按除权日去重。
     */
    private suspend fun fetchAllDividendsFromTencent(tencentCode: String): List<TencentDividendItem> {
        val today = LocalDate.now()

        val merged = LinkedHashMap<String, TencentDividendItem>()
        merged.putAll(fetchTencentWindow(tencentCode, today.minusYears(2), today))
        merged.putAll(fetchTencentWindow(tencentCode, today.minusYears(4), today.minusYears(2)))
        merged.putAll(fetchTencentWindow(tencentCode, today.minusYears(6), today.minusYears(4)))
        return merged.values.toList()
    }

    private suspend fun fetchTencentWindow(
        tencentCode: String,
        start: LocalDate,
        end: LocalDate
    ): Map<String, TencentDividendItem> {
        val param = "$tencentCode,day,${start.iso()},${end.iso()},$KLINE_COUNT,qfq"
        val response = tencentApi.getKline(param)
        val qfqday = response.data?.values?.firstOrNull()?.qfqday ?: return emptyMap()

        val gson = Gson()
        val result = LinkedHashMap<String, TencentDividendItem>()
        qfqday.forEach { day ->
            // 第 7 个元素（index 6）是分红对象，仅有除权除息日当天才有
            val seventh = day.getOrNull(DIVIDEND_ELEMENT_INDEX) ?: return@forEach
            if (seventh !is com.google.gson.JsonObject) return@forEach
            val item = gson.fromJson(seventh, TencentDividendItem::class.java)
            val key = item.cqr ?: item.djr ?: return@forEach
            if (item.fhSh?.toDoubleOrNull()?.let { it > 0.0 } == true) {
                result[key] = item
            }
        }
        return result
    }

    // ── 东方财富（补充/回退）──────────────────────────────────────

    /**
     * 东财明细对主源数据的补充（同一请求三用）：① 按除权日对齐补 [DividendEntity.dividendYield]
     * 历史股息率快照与 **reportDate/recordDate**（扶摇事件流无报告期——按除权年占位的行由此
     * 回填准确报告期；腾讯主源数据本身有报告期，回填通常为幂等同值）；② 合并**已排期未除权**
     * 记录——主源已除权数据只有过去记录，「实施公告已发布、除权日在未来」的分红（如年度分红
     * 明天除权）只能来自东财。预案（exDate=null，金额可能变）不合并；东财失败/无数据时静默
     * 返回原列表，不影响主源数据。
     */
    private suspend fun enrichAndMergeFromEastMoney(
        stockCode: String,
        securityCode: String,
        entities: List<DividendEntity>
    ): List<DividendEntity> {
        val items = runCatching {
            eastMoneyApi.getDividends(filter = dividendFilter(stockCode, securityCode))
                .result?.data.orEmpty()
        }.getOrDefault(emptyList())
        if (items.isEmpty()) return entities

        val expectedSecuCode = stockCode.toEastmoneySecuCode()
        val yieldByExDate = mutableMapOf<String, Double>()
        val reportDateByExDate = mutableMapOf<String, String>()
        val recordDateByExDate = mutableMapOf<String, String>()
        items.forEach { item ->
            val exDate = item.exDividendDate?.toDateOnlyOrNull() ?: return@forEach
            item.dividentRatio?.let { yieldByExDate.putIfAbsent(exDate, it * 100.0) }
            item.reportDate.toDateOnlyOrNull()?.let { reportDateByExDate.putIfAbsent(exDate, it) }
            item.equityRecordDate.toDateOnlyOrNull()?.let { recordDateByExDate.putIfAbsent(exDate, it) }
        }

        // 已排期未除权 = exDate 已定但主源按除权日没有对应记录（主源只有已除权的）
        val mergedExDates = entities.mapNotNull { it.exDividendDate }.toSet()
        val scheduledOnly = items.mapNotNull { item ->
            item.exDividendDate?.toDateOnlyOrNull()
                ?.takeIf { it !in mergedExDates }
                ?.let { toEastMoneyEntity(item, stockCode, expectedSecuCode) }
        }.distinctBy { it.exDividendDate }

        return entities.map { entity ->
            val exDate = entity.exDividendDate
            entity.copy(
                reportDate = (exDate?.let { reportDateByExDate[it] }) ?: entity.reportDate,
                dividendYield = entity.dividendYield ?: exDate?.let { yieldByExDate[it] },
                recordDate = entity.recordDate ?: exDate?.let { recordDateByExDate[it] }
            )
        } + scheduledOnly
    }

    /** 东财分红明细查询过滤条件：优先精确 SECUCODE，退化为 SECURITY_CODE。 */
    private fun dividendFilter(stockCode: String, securityCode: String): String {
        val expectedSecuCode = stockCode.toEastmoneySecuCode()
        return expectedSecuCode
            ?.let { "(SECUCODE=\"$it\")" }
            ?: "(SECURITY_CODE=\"$securityCode\")"
    }

    private suspend fun fetchFromEastMoney(
        stockCode: String,
        securityCode: String
    ): List<DividendEntity> {
        val expectedSecuCode = stockCode.toEastmoneySecuCode()
        val filter = dividendFilter(stockCode, securityCode)
        // 数值脏值（"-" 占位）由 NetworkModule 的容错 Gson 兜（"-"→null，2026-08-20 审计 M1/M6），
        // 网络异常保持向上传播——双源都失败时 fetchAndCacheDividends 需给出用户可感知的错误信息
        val response = eastMoneyApi.getDividends(filter = filter)
        val items = response.result?.data ?: emptyList()

        return items.mapNotNull { toEastMoneyEntity(it, stockCode, expectedSecuCode) }
    }

    /** 东财明细 → 实体（回退全量与「已排期未除权」合并共用；PRETAX_BONUS_RMB 每10股派息需 ÷10）。 */
    private fun toEastMoneyEntity(
        item: DividendResponse.DividendItem,
        stockCode: String,
        expectedSecuCode: String?
    ): DividendEntity? {
        if (expectedSecuCode != null &&
            item.secuCode != null &&
            !item.secuCode.equals(expectedSecuCode, ignoreCase = true)
        ) {
            return null
        }
        val reportDate = item.reportDate.toDateOnlyOrNull() ?: return null
        val cashRatio = item.pretaxBonusRmb ?: return null
        if (cashRatio <= 0) return null

        return DividendEntity(
            id = "${stockCode}_${reportDate}",
            stockCode = stockCode,
            reportDate = reportDate,
            cashPerShare = cashRatio / 10.0,
            dividendYield = item.dividentRatio?.let { it * 100.0 },
            exDividendDate = item.exDividendDate.toDateOnlyOrNull(),
            recordDate = item.equityRecordDate.toDateOnlyOrNull(),
            planNoticeDate = item.planNoticeDate.toDateOnlyOrNull(),
            planStatus = item.assignProgress
        )
    }

    companion object {
        private const val KLINE_COUNT = 640
        private const val DIVIDEND_ELEMENT_INDEX = 6
    }
}

private fun LocalDate.iso(): String = format(DateTimeFormatter.ISO_LOCAL_DATE)

private fun String?.toDateOnlyOrNull(): String? =
    this
        ?.substringBefore("T")
        ?.substringBefore(" ")
        ?.takeIf { it.isNotBlank() }

private fun String?.takeDateOnlyOrNull(): String? = toDateOnlyOrNull()

/** 报告年度补成 `YYYY-12-31` 作为 reportDate，便于按年份分组统计。 */
private fun String.takeYearEndDateOrNull(): String? =
    substringBefore("-").takeIf { it.length == 4 && it.toIntOrNull() != null }
        ?.let { "$it-12-31" }

/** `sh.600036` → `sh600036`（腾讯 fqkline 代码格式）。 */
private fun String.toTencentCode(): String? {
    return when {
        startsWith("sh.", ignoreCase = true) -> "sh" + substringAfter(".")
        startsWith("sz.", ignoreCase = true) -> "sz" + substringAfter(".")
        else -> null
    }
}

private fun String.toEastmoneySecuCode(): String? {
    val code = substringAfter(".").takeIf { it.isNotBlank() } ?: return null
    return when {
        startsWith("sz.", ignoreCase = true) -> "$code.SZ"
        startsWith("sh.", ignoreCase = true) -> "$code.SH"
        else -> null
    }
}

internal fun Exception.toUserMessage(): String {
    return when (this) {
        is SocketTimeoutException -> "网络连接超时，请重试"
        is UnknownHostException, is ConnectException -> "网络连接失败，请检查网络后重试"
        is HttpException -> {
            if (code() in 500..599) "服务器暂时无法响应，请稍后重试"
            else "网络请求失败，请重试"
        }
        else -> "操作失败，请重试"
    }
}
