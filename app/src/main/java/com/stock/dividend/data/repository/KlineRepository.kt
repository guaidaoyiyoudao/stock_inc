package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.KlineCacheDao
import com.stock.dividend.data.local.entity.KlineCacheEntity
import com.stock.dividend.data.local.entity.KlineCacheMetaEntity
import com.stock.dividend.data.remote.TencentDividendApi
import com.stock.dividend.di.TencentDividendSource
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** 腾讯 fqkline 周期。paramType 进请求 param，responseKey 对应响应 JSON 键。 */
enum class KlinePeriod(val paramType: String, val responseKey: String) {
    DAILY("day", "qfqday"),
    WEEKLY("week", "qfqweek"),
    MONTHLY("month", "qfqmonth");

    /** 取 [bars] 根所需的回看日历天数（含余量）。 */
    internal fun lookbackDays(bars: Int): Int = when (this) {
        DAILY -> bars * 7 / 5 + 30   // 交易日→日历，+buffer
        WEEKLY -> bars * 7
        MONTHLY -> bars * 31
    }
}

/**
 * 单根 K 线（前复权，OHLCV）。由 [parseKlineBars] 解析腾讯 fqkline 数组行得到。
 *
 * @property date      日期（YYYY-MM-DD）。
 * @property open      开盘价（元）。
 * @property close     收盘价（元）。
 * @property high      最高价（元）。
 * @property low       最低价（元）。
 * @property volume    成交量（手）。
 */
data class KlineBar(
    val date: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double
)

/**
 * 腾讯 fqkline 数组行 → [KlineBar] 列表（纯函数，无 Android 依赖）。
 *
 * 数组下标（实测 2026-08，腾讯 `appstock/app/fqkline/get`）：
 * `[0]date [1]open [2]close [3]high [4]low [5]volume`（均为字符串）。
 * 第 7 元素（index 6）是分红对象，仅除权除息日有——本函数只取 K 线，分红由 [com.stock.dividend.data.repository.DividendRepository] 单独解析。
 *
 * 过滤规则（与 [KlineRepository.fetchCloses] 一致）：close 缺失/≤0/非数字的行整体丢弃，
 * 因为 BOLL 等技术指标依赖连续有效收盘价。OHLC 任一缺失无法构成完整 K 线，亦丢弃。
 *
 * @param rows 接口返回的 qfq{day|week|month} 数组
 * @return 升序（旧→新）的有效 K 线；rows 为空返回空表
 */
fun parseKlineBars(rows: List<List<*>>): List<KlineBar> = rows.mapNotNull { row ->
    val date = row.getOrNull(0) as? String
    val open = row.parseDouble(1)
    val close = row.parseDouble(2)
    val high = row.parseDouble(3)
    val low = row.parseDouble(4)
    val volume = row.parseDouble(5) ?: 0.0   // volume 缺失视为 0（不丢整根 K 线）
    if (date.isNullOrBlank() || close == null || close <= 0.0) return@mapNotNull null
    KlineBar(date = date, open = open ?: close, close = close, high = high ?: close, low = low ?: close, volume = volume)
}

/** 取下标 [index] 的字符串并转 Double；越界/非数字/null 返回 null。 */
private fun List<*>.parseDouble(index: Int): Double? =
    (getOrNull(index) as? String)?.toDoubleOrNull()?.takeIf { it.isFinite() }

/**
 * K 线仓库：腾讯 fqkline 网络源 + Room 本地缓存（**永久缓存**：历史不可变数据持久化）。
 *
 * 读取编排（[loadBars]）：
 * 1. 缓存尾部已覆盖「本周期正在形成的最新一根」（日线=今天/周线=本周/月线=本月），或今日已同步过
 *    → 直接返回缓存，零网络——历史永不因时间过期重拉；
 * 2. 尾部落后且今日未同步 → **每日最多一次**小窗口增量补尾（从最后一根缓存日期含拉到今天，覆盖盘中变动的尾根）；
 * 3. 无缓存 / [forceRefresh] / 出现新除权日（前复权全历史漂移，增量合并会算错 BOLL）→ 全量拉取并重建缓存
 *    （固定按 [FULL_FETCH_BARS] 深窗口拉取，与调用方请求条数解耦——浅窗口调用者不得截断缓存深度）；
 * 4. 任一网络失败 → 回退缓存（红线 #2，断网 BOLL/回测仍可用）；无缓存返回空表（历史行为）。
 *
 * 前复权漂移检测：[KlineCacheMetaEntity.lastExDividendDate] 记录写入缓存时该股最新除权日，
 * 与 dividends 表当前最新除权日比对——除权后所有历史价格整体位移，必须全量重建（约每股每年 1-2 次）。
 */
@Singleton
class KlineRepository @Inject constructor(
    @TencentDividendSource private val tencentApi: TencentDividendApi,
    private val klineCacheDao: KlineCacheDao,
    private val dividendDao: DividendDao
) {
    suspend fun fetchCloses(
        stockCode: String,
        period: KlinePeriod,
        bars: Int = DEFAULT_BARS,
        forceRefresh: Boolean = false
    ): List<Double> {
        return loadBars(stockCode, period, bars, forceRefresh).map { it.close }
    }

    /**
     * 拉取 [stockCode] 指定 [period] 的完整 OHLCV K 线（前复权）。网络失败回退缓存（红线 #2）。
     *
     * 与 [fetchCloses] 共享同一次请求编排，只是保留 open/high/low/volume——这些字段原本就被接口返回，
     * 只是 [fetchCloses] 丢弃了。故不增加任何网络成本。
     */
    suspend fun fetchKlines(
        stockCode: String,
        period: KlinePeriod,
        bars: Int = DEFAULT_BARS,
        forceRefresh: Boolean = false
    ): List<KlineBar> {
        return loadBars(stockCode, period, bars, forceRefresh)
    }

    /** 周线兼容封装（旧调用点不改）。 */
    suspend fun fetchWeeklyCloses(stockCode: String, weeks: Int = DEFAULT_BARS): List<Double> =
        fetchCloses(stockCode, KlinePeriod.WEEKLY, weeks)

    private suspend fun loadBars(
        stockCode: String,
        period: KlinePeriod,
        bars: Int,
        forceRefresh: Boolean
    ): List<KlineBar> {
        val tencentCode = stockCode.toTencentCode() ?: return emptyList()
        val periodKey = period.name
        val cachedBars = runCatching { klineCacheDao.getBars(stockCode, periodKey) }
            .getOrDefault(emptyList())
            .map { it.toKlineBar() }
        val meta = runCatching { klineCacheDao.getMeta(stockCode, periodKey) }.getOrNull()
        // 未来除权日（日历倒计时的已排期除权）不参与漂移检测——否则每次都会误触发一次多余的
        // 全量重建（meta 写入后即稳定，仅浪费；ISO 日期字符串字典序即时间序）
        val latestExDate = runCatching { dividendDao.getLatestExDividendDate(stockCode) }.getOrNull()
            ?.takeIf { it <= LocalDate.now().toString() }

        val qfqShifted = meta != null && latestExDate != meta.lastExDividendDate

        // 永久缓存：尾部已是本周期最新 / 今日已同步过（含停牌/长假期空结果）→ 零网络直读
        if (!forceRefresh && !qfqShifted && cachedBars.isNotEmpty()) {
            val today = LocalDate.now()
            val tailCurrent = klineTailIsCurrent(cachedBars.last().date, period, today)
            val syncedToday = meta != null && sameDay(meta.fetchedAt, today)
            if (tailCurrent || syncedToday) {
                return cachedBars.takeLast(bars)
            }
        }

        if (forceRefresh || qfqShifted || cachedBars.isEmpty()) {
            // 全量路径：首拉 / 强刷 / 前复权漂移。窗口固定按 [FULL_FETCH_BARS]（最深消费方网格回测
            // 的需求）拉取，与调用方 bars 解耦——否则图表等小窗口调用者触发的重建只会落浅历史，
            // replaceBars 覆盖掉已有深缓存且不会自愈（增量只向前追加），违背「历史不可变数据永久缓存」。
            // 窗口折算 ≈ 543 交易日（周线 500 根/月线全量），均在腾讯单次上限 640 内，无截尾歧义。
            val remote = fetchByParam(buildParam(tencentCode, period, FULL_FETCH_BARS), period)
                ?: return cachedBars.takeLast(bars)      // 网络失败：回退缓存（无缓存时空表）
            if (remote.isEmpty()) return cachedBars.takeLast(bars)  // 接口确无数据：不动缓存
            runCatching {
                klineCacheDao.replaceBars(stockCode, periodKey, remote.map { it.toEntity(stockCode, periodKey) })
                klineCacheDao.upsertMeta(
                    KlineCacheMetaEntity(stockCode, periodKey, System.currentTimeMillis(), latestExDate)
                )
            }
            return remote.takeLast(bars)
        }

        // 增量补尾（每日最多一次）：从最后一根缓存日期（含）拉到今天，覆盖更新盘中变动的尾根
        val tail = fetchByParam(
            buildIncrementalParam(tencentCode, period, cachedBars.last().date),
            period
        ) ?: return cachedBars.takeLast(bars)
        runCatching {
            if (tail.isNotEmpty()) {
                klineCacheDao.upsertBars(tail.map { it.toEntity(stockCode, periodKey) })
                klineCacheDao.trimToRecent(stockCode, periodKey, MAX_CACHED_BARS)
            }
            klineCacheDao.upsertMeta(
                KlineCacheMetaEntity(stockCode, periodKey, System.currentTimeMillis(), latestExDate)
            )
        }
        val merged = (cachedBars.associateBy { it.date } + tail.associateBy { it.date })
            .values.sortedBy { it.date }
        return merged.takeLast(bars)
    }

    /** 网络请求并解析；异常或响应缺数据键返回 null（与「成功但空」区分，前者回退缓存）。 */
    private suspend fun fetchByParam(param: String, period: KlinePeriod): List<KlineBar>? {
        val response = try {
            tencentApi.getKline(param)
        } catch (_: Exception) {
            return null
        }
        val rows = response.klineRows(period) ?: return null
        return parseKlineBars(rows)
    }

    internal fun buildParam(tencentCode: String, period: KlinePeriod, bars: Int): String {
        val today = LocalDate.now()
        val start = today.minusDays((period.lookbackDays(bars) * BUFFER_FACTOR).toLong())
        return "$tencentCode,${period.paramType},${start.iso()},${today.iso()},$KLINE_COUNT,$ADJUST_QFQ"
    }

    /** 增量窗口参数：从 [fromDate]（含，通常为最后一根缓存日期）到今天。 */
    internal fun buildIncrementalParam(tencentCode: String, period: KlinePeriod, fromDate: String): String {
        return "$tencentCode,${period.paramType},$fromDate,${LocalDate.now().iso()},$KLINE_COUNT,$ADJUST_QFQ"
    }

    companion object {
        const val DEFAULT_BARS = 40
        const val KLINE_COUNT = 640
        const val BUFFER_FACTOR = 2
        const val ADJUST_QFQ = "qfq"
        /** 每股每周期缓存上限，防增量写入无限增长。 */
        const val MAX_CACHED_BARS = 800

        /**
         * 全量拉取（首拉/强刷/除权重建）的固定回看条数，与调用方请求条数解耦：
         * 覆盖最深消费方（网格回测 250 根），折算日期窗口 ≈ 543 交易日，仍在腾讯单次
         * 上限 640 内（窗口超出上限时尾部是否被截无文档保证，不赌）。
         */
        const val FULL_FETCH_BARS = 250
    }
}

/**
 * 缓存尾部是否已覆盖「本周期正在形成的最新一根」（纯函数）：
 * DAILY=已含今天；WEEKLY=最后一根在本周（周一及以后）；MONTHLY=最后一根在本月。
 * 满足即视为完整——历史永不再拉，当日重复读取零网络。日期解析失败视为完整（不阻塞读取）。
 */
internal fun klineTailIsCurrent(lastBarDate: String, period: KlinePeriod, today: LocalDate): Boolean {
    val last = runCatching { LocalDate.parse(lastBarDate) }.getOrNull() ?: return true
    return when (period) {
        KlinePeriod.DAILY -> !last.isBefore(today)
        KlinePeriod.WEEKLY -> !last.isBefore(today.with(DayOfWeek.MONDAY))
        KlinePeriod.MONTHLY -> !last.isBefore(today.withDayOfMonth(1))
    }
}

private fun sameDay(epochMs: Long, today: LocalDate): Boolean =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate() == today

/** 按周期取对应键的 K 线数组；优先周期键，缺失时日线→周线降级，周线→日线降级。 */
private fun com.stock.dividend.data.remote.dto.TencentKlineResponse.klineRows(
    period: KlinePeriod
): List<List<*>>? {
    val stockData = data?.values?.firstOrNull() ?: return null
    return when (period) {
        KlinePeriod.DAILY -> stockData.qfqday
        KlinePeriod.WEEKLY -> stockData.qfqweek ?: stockData.qfqday
        KlinePeriod.MONTHLY -> stockData.qfqmonth ?: stockData.qfqweek
    }
}

private fun KlineCacheEntity.toKlineBar(): KlineBar =
    KlineBar(date = date, open = open, close = close, high = high, low = low, volume = volume)

private fun KlineBar.toEntity(stockCode: String, periodKey: String): KlineCacheEntity =
    KlineCacheEntity(
        stockCode = stockCode,
        period = periodKey,
        date = date,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume
    )

private fun String.toTencentCode(): String? = when {
    startsWith("sh.", ignoreCase = true) -> "sh" + substringAfter(".")
    startsWith("sz.", ignoreCase = true) -> "sz" + substringAfter(".")
    else -> null
}

private fun LocalDate.iso(): String = format(DateTimeFormatter.ISO_LOCAL_DATE)
