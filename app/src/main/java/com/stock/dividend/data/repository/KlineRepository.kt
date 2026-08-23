package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.KlineCacheDao
import com.stock.dividend.data.local.entity.KlineCacheEntity
import com.stock.dividend.data.local.entity.KlineCacheMetaEntity
import com.stock.dividend.data.remote.TencentDividendApi
import com.stock.dividend.data.remote.dto.FUYAO_ZONE
import com.stock.dividend.data.remote.dto.fuyaoMsToDateStringOrNull
import com.stock.dividend.data.remote.dto.toFuyaoThscodeOrNull
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
 * K 线仓库：网络源 + Room 本地缓存（**永久缓存**：历史不可变数据持久化）。
 *
 * **主源分层**（2026-08-23 起）：股票走同花顺扶摇日K（前复权 adjust=forward，窗口 ≤10 年，
 * 一次拉全）；扶摇**只有日线**，周线/月线由 [KlineAggregator] 从日线本地聚合。基金（ETF/LOF）
 * 保持腾讯——扶摇基金日K恒为未复权（adjust 不支持），与 BOLL/网格回测的前复权口径不符。
 * 扶摇失败降级腾讯；未配置 key 时全部走腾讯（现状路径）。
 *
 * 读取编排（[loadBars]）：
 * 1. 缓存尾部已覆盖「本周期正在形成的最新一根」（日线=今天/周线=本周/月线=本月），或今日已同步过
 *    → 直接返回缓存，零网络——历史永不因时间过期重拉；
 * 2. 尾部落后且今日未同步 → **每日最多一次**小窗口增量补尾（日线从最后日期、周/月线从所在
 *    周期首日拉到今天重算当期部分周期 K，覆盖盘中变动的尾根）；
 * 3. 无缓存 / [forceRefresh] / 出现新除权日（前复权全历史漂移，增量合并会算错 BOLL）/ **换源**
 *    （同花顺与腾讯的前复权因子舍入不同，基准混用会在增量边界产生价格跳变）→ 全量拉取并重建缓存
 *    （固定按 [FULL_FETCH_BARS] 深窗口拉取，与调用方请求条数解耦——浅窗口调用者不得截断缓存深度）；
 * 4. 任一网络失败 → 回退缓存（红线 #2，断网 BOLL/回测仍可用）；无缓存返回空表（历史行为）。
 *
 * 前复权漂移检测：[KlineCacheMetaEntity.lastExDividendDate] 记录写入缓存时该股最新除权日，
 * 与 dividends 表当前最新除权日比对——除权后所有历史价格整体位移，必须全量重建（约每股每年 1-2 次）。
 *
 * 扶摇故障冷却（[fuyaoFailureAt]）：主源失败后 [FUYAO_FAILURE_COOLDOWN_MS] 内主源视同腾讯——
 * 否则「缓存 source=tencent ≠ 主源 fuyao」的换源判定会在扶摇故障期间把每次读取都变成全量重拉（热循环）。
 */
@Singleton
class KlineRepository @Inject constructor(
    @TencentDividendSource private val tencentApi: TencentDividendApi,
    private val fuyaoApi: com.stock.dividend.data.remote.FuyaoApi,
    private val fuyaoConfig: FuyaoConfig,
    private val klineCacheDao: KlineCacheDao,
    private val dividendDao: DividendDao
) {
    /** 扶摇最近一次失败时刻（epoch ms，0=健康）；@Volatile 供并发读取。 */
    @Volatile
    private var fuyaoFailureAt: Long = 0L

    private fun fuyaoInCooldown(now: Long = System.currentTimeMillis()): Boolean =
        now - fuyaoFailureAt < FUYAO_FAILURE_COOLDOWN_MS

    private fun markFuyaoFailure() {
        fuyaoFailureAt = System.currentTimeMillis()
    }

    private fun markFuyaoHealthy() {
        fuyaoFailureAt = 0L
    }

    /** 当前生效主源：股票且扶摇启用且不在故障冷却 → fuyao；否则腾讯（基金恒腾讯）。 */
    private fun effectivePrimarySource(stockCode: String): String =
        if (fuyaoConfig.enabled &&
            !FundDividendParser.isExchangeTradedFund(stockCode) &&
            !fuyaoInCooldown()
        ) SOURCE_FUYAO else SOURCE_TENCENT

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

        val primary = effectivePrimarySource(stockCode)
        val qfqShifted = meta != null && latestExDate != meta.lastExDividendDate
        val sourceShifted = meta != null && meta.source != primary

        // 永久缓存：尾部已是本周期最新 / 今日已同步过（含停牌/长假期空结果）→ 零网络直读
        if (!forceRefresh && !qfqShifted && !sourceShifted && cachedBars.isNotEmpty()) {
            val today = LocalDate.now()
            val tailCurrent = klineTailIsCurrent(cachedBars.last().date, period, today)
            val syncedToday = meta != null && sameDay(meta.fetchedAt, today)
            if (tailCurrent || syncedToday) {
                return cachedBars.takeLast(bars)
            }
        }

        if (forceRefresh || qfqShifted || sourceShifted || cachedBars.isEmpty()) {
            // 全量路径：首拉 / 强刷 / 前复权漂移 / 换源。窗口固定按 [FULL_FETCH_BARS]（最深消费方网格回测
            // 的需求）拉取，与调用方 bars 解耦——否则图表等小窗口调用者触发的重建只会落浅历史，
            // replaceBars 覆盖掉已有深缓存且不会自愈（增量只向前追加），违背「历史不可变数据永久缓存」。
            // 扶摇主源：日线直出，周/月线由日线聚合；失败标记冷却并整体降级腾讯全量（一次）。
            val fuyaoDaily = if (primary == SOURCE_FUYAO) {
                runCatching { fetchDailyBarsFromFuyao(stockCode, fuyaoFullWindowDays(period)) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
            } else null
            if (fuyaoDaily == null && primary == SOURCE_FUYAO) markFuyaoFailure()

            val remote: List<KlineBar>
            val usedSource: String
            if (fuyaoDaily != null) {
                markFuyaoHealthy()
                remote = KlineAggregator.aggregate(fuyaoDaily, period)
                usedSource = SOURCE_FUYAO
            } else {
                val tencentRemote = fetchByParam(buildParam(tencentCode, period, FULL_FETCH_BARS), period)
                    ?: return cachedBars.takeLast(bars)      // 网络失败：回退缓存（无缓存时空表）
                if (tencentRemote.isEmpty()) return cachedBars.takeLast(bars)  // 接口确无数据：不动缓存
                remote = tencentRemote
                usedSource = SOURCE_TENCENT
            }
            runCatching {
                klineCacheDao.replaceBars(stockCode, periodKey, remote.map { it.toEntity(stockCode, periodKey) })
                klineCacheDao.upsertMeta(
                    KlineCacheMetaEntity(stockCode, periodKey, System.currentTimeMillis(), latestExDate, usedSource)
                )
            }
            return remote.takeLast(bars)
        }

        // 增量补尾（每日最多一次）：日线从最后一根缓存日期（含）拉到今天；周/月线从最后一根
        // 所在周期首日拉——当期部分周期 K 需用完整日线重算（upsert 按日期覆盖旧尾根）
        if (primary == SOURCE_FUYAO) {
            val from = incrementalStartDate(period, cachedBars.last().date)
            val windowDays = java.time.temporal.ChronoUnit.DAYS.between(from, LocalDate.now()) + 1
            val daily = runCatching { fetchDailyBarsFromFuyao(stockCode, windowDays.toInt()) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
            if (daily == null) {
                markFuyaoFailure()
                return cachedBars.takeLast(bars)   // 扶摇增量失败：回退缓存（冷却期内走腾讯增量）
            }
            markFuyaoHealthy()
            val tail = KlineAggregator.aggregate(daily, period)
            runCatching {
                klineCacheDao.upsertBars(tail.map { it.toEntity(stockCode, periodKey) })
                if (period == KlinePeriod.DAILY) {
                    klineCacheDao.trimToRecent(stockCode, periodKey, MAX_CACHED_BARS)
                }
                klineCacheDao.upsertMeta(
                    KlineCacheMetaEntity(stockCode, periodKey, System.currentTimeMillis(), latestExDate, SOURCE_FUYAO)
                )
            }
            val merged = (cachedBars.associateBy { it.date } + tail.associateBy { it.date })
                .values.sortedBy { it.date }
            return merged.takeLast(bars)
        }

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
                KlineCacheMetaEntity(stockCode, periodKey, System.currentTimeMillis(), latestExDate, SOURCE_TENCENT)
            )
        }
        val merged = (cachedBars.associateBy { it.date } + tail.associateBy { it.date })
            .values.sortedBy { it.date }
        return merged.takeLast(bars)
    }

    /**
     * 扶摇日K（前复权）：窗口 [windowDays] 日历天（内部钳制 ≤ [FUYAO_MAX_WINDOW_DAYS]，接口上限 10 年）。
     * 失败/信封非 0/空结果返回 null（调用方降级腾讯）。行过滤与 [parseKlineBars] 同规则：
     * close 缺失/≤0 丢整行，volume 缺失补 0，open/high/low 缺失回退 close。
     * ⚠️ volume 单位换算：扶摇为**股**，腾讯 K 线为**手**（2026-08-23 审计 M2 实测：茅台
     * 扶摇 3347231 股 = 腾讯 33472 手）——[KlineBar.volume] 语义沿用腾讯手口径，此处 ÷100。
     */
    private suspend fun fetchDailyBarsFromFuyao(stockCode: String, windowDays: Int): List<KlineBar>? {
        val thscode = stockCode.toFuyaoThscodeOrNull() ?: return null
        val window = windowDays.toLong().coerceIn(1, FUYAO_MAX_WINDOW_DAYS.toLong())
        val startMs = LocalDate.now().minusDays(window)
            .atStartOfDay(FUYAO_ZONE).toInstant().toEpochMilli()
        val envelope = fuyaoApi.getDailyBars(thscode = thscode, startMs = startMs, endMs = System.currentTimeMillis())
        if (!envelope.isOk) return null
        return envelope.data?.item.orEmpty().mapNotNull { bar ->
            val date = bar.dateMs.fuyaoMsToDateStringOrNull() ?: return@mapNotNull null
            val close = bar.close?.takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
            KlineBar(
                date = date,
                open = bar.open?.takeIf { it.isFinite() } ?: close,
                close = close,
                high = bar.high?.takeIf { it.isFinite() } ?: close,
                low = bar.low?.takeIf { it.isFinite() } ?: close,
                volume = bar.volume?.takeIf { it.isFinite() }?.div(100.0) ?: 0.0   // 股→手
            )
        }
    }

    /** 全量拉取的扶摇日K窗口（日历天）：日线/周线按 [FULL_FETCH_BARS] 折算，月线取满窗口（250 月≈20 年超接口上限）。 */
    internal fun fuyaoFullWindowDays(period: KlinePeriod): Int =
        period.lookbackDays(FULL_FETCH_BARS).coerceAtMost(FUYAO_MAX_WINDOW_DAYS)

    /** 增量起始日：日线=最后缓存日；周线=其所在周的周一；月线=其所在月 1 号（当期部分周期 K 整段重算）。 */
    internal fun incrementalStartDate(period: KlinePeriod, lastBarDate: String): LocalDate {
        val last = runCatching { LocalDate.parse(lastBarDate) }.getOrNull() ?: LocalDate.now()
        return when (period) {
            KlinePeriod.DAILY -> last
            KlinePeriod.WEEKLY -> last.with(DayOfWeek.MONDAY)
            KlinePeriod.MONTHLY -> last.withDayOfMonth(1)
        }
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

        /** K 线缓存数据来源标识（kline_cache_meta.source，换源触发全量重建）。 */
        const val SOURCE_FUYAO = "fuyao"
        const val SOURCE_TENCENT = "tencent"

        /** 扶摇日K窗口上限（接口限制 10 自然年，留余量）。 */
        const val FUYAO_MAX_WINDOW_DAYS = 3600

        /** 扶摇失败冷却：期间主源视同腾讯，避免换源判定热循环（每次读取都全量重拉）。 */
        const val FUYAO_FAILURE_COOLDOWN_MS = 10 * 60 * 1000L

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
