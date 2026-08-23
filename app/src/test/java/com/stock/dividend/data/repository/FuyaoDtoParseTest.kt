package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.reflect.TypeToken
import com.stock.dividend.data.remote.dto.FuyaoEnvelope
import com.stock.dividend.data.remote.dto.FuyaoSnapshotData
import com.stock.dividend.data.remote.dto.FuyaoIndicatorsData
import com.stock.dividend.data.remote.dto.FuyaoIndicatorIds
import com.stock.dividend.data.remote.dto.fuyaoMsToDateStringOrNull
import com.stock.dividend.data.remote.dto.fuyaoThscodeToAppCodeOrNull
import com.stock.dividend.data.remote.dto.indicatorValueOf
import com.stock.dividend.data.remote.dto.toFuyaoReportParamOrNull
import com.stock.dividend.data.remote.dto.toFuyaoThscodeOrNull
import com.stock.dividend.data.remote.lenientMarketGson
import org.junit.Test

/**
 * 同花顺扶摇 DTO 解析锁定测试（§4.9.5：每个新 DTO 配真实 JSON fixture，逐字段断言单位）。
 *
 * fixture 取自 2026-08-23 实测（同花顺扶摇 fuyao.aicubes.cn，腾讯 qt 同时刻交叉验证一致）。
 * 核心单位纪律：**全部真实值**（价格元/百分比%原值/金额元），唯一换算是
 * 快照成交量 股→手 ÷100（对齐 App QuoteSnapshot.volume 语义）。
 */
class FuyaoDtoParseTest {

    private val gson = lenientMarketGson()

    private inline fun <reified T> parse(json: String): T =
        gson.fromJson(json, object : TypeToken<T>() {}.type)

    // ── 信封与行情快照 ──────────────────────────────────────────

    @Test
    fun `envelope parses real batch snapshot response`() {
        // 实测：A股批量快照（茅台+农行，2026-08-21 收盘）
        val json = """
        {"code":0,"message":"success","request_id":"949e8ae81a814b81a9502e4d7f522444",
         "data":{"timestamp":1787447437000,"total":2,"item":[
           {"thscode":"600519.SH","ticker":"600519","volume":3347231,"turnover":4278311000,
            "last_price":1272.83,"price_change":-18.67,"price_change_ratio_pct":-1.445606,
            "open_price":1291.5,"high_price":1291.5,"low_price":1272.01,"prev_price":1291.5},
           {"thscode":"601288.SH","ticker":"601288","volume":364661790,"turnover":2469445600,
            "last_price":6.78,"price_change":-0.03,"price_change_ratio_pct":-0.440529,
            "open_price":6.79,"high_price":6.85,"low_price":6.73,"prev_price":6.81}]}}
        """.trimIndent()

        val envelope = parse<FuyaoEnvelope<FuyaoSnapshotData>>(json)

        assertThat(envelope.isOk).isTrue()
        val items = envelope.data!!.item!!
        assertThat(items).hasSize(2)
        val maotai = items[0]
        // 真实值口径：价格/百分比/金额原值不换算（腾讯同刻 1272.83 / -1.45 / -18.67 交叉验证）
        assertThat(maotai.lastPrice).isWithin(1e-9).of(1272.83)
        assertThat(maotai.changePct).isWithin(1e-9).of(-1.445606)
        assertThat(maotai.priceChange).isWithin(1e-9).of(-18.67)
        assertThat(maotai.prevClose).isWithin(1e-9).of(1291.5)
        assertThat(maotai.volumeShares).isWithin(1e-9).of(3347231.0)   // 单位：股
        assertThat(maotai.turnover).isWithin(1e-9).of(4278311000.0)    // 单位：元
    }

    @Test
    fun `business error envelope is failure with null data`() {
        // 实测：ETF 代码误入 A 股批量接口 → 整批 1002（这是股票/基金必须拆分请求的原因）
        val json = """
        {"code":1002,"message":"Unknown thscode: 510880.SH",
         "request_id":"8c70e70a9e444fd5b5a28b148f1b6779","data":null}
        """.trimIndent()

        val envelope = parse<FuyaoEnvelope<FuyaoSnapshotData>>(json)

        assertThat(envelope.isOk).isFalse()
        assertThat(envelope.code).isEqualTo(1002)
        assertThat(envelope.data).isNull()
    }

    @Test
    fun `toQuoteSnapshotFromFuyao converts volume to lots and nulls missing fields`() {
        val snapshot = toQuoteSnapshotFromFuyao(
            parse<FuyaoEnvelope<FuyaoSnapshotData>>(
                """
                {"code":0,"data":{"item":[{"thscode":"600519.SH","volume":3347231,
                 "last_price":1272.83,"price_change":-18.67,"price_change_ratio_pct":-1.445606,
                 "open_price":1291.5,"high_price":1291.5,"low_price":1272.01,"prev_price":1291.5,
                 "turnover":4278311000}]}}
                """.trimIndent()
            ).data!!.item!![0]
        )!!

        assertThat(snapshot.stockCode).isEqualTo("sh.600519")
        assertThat(snapshot.price).isWithin(1e-9).of(1272.83)
        assertThat(snapshot.changePct).isWithin(1e-9).of(-1.445606)
        // 唯一换算：成交量 股→手（腾讯同刻 33472 手）
        assertThat(snapshot.volume).isWithin(1e-9).of(33472.31)
        // A股快照缺失字段 → null，由东财并行补齐（supplementedFrom）
        assertThat(snapshot.totalMarketCap).isNull()
        assertThat(snapshot.pe).isNull()
        assertThat(snapshot.turnoverRate).isNull()
        assertThat(snapshot.volumeRatio).isNull()
    }

    @Test
    fun `fund snapshot carries amplitude and turnover rate`() {
        // 实测：红利ETF 基金快照（比 A 股快照多振幅/换手率字段）
        val snapshot = toQuoteSnapshotFromFuyao(
            parse<FuyaoEnvelope<FuyaoSnapshotData>>(
                """
                {"code":0,"data":{"item":[{"thscode":"510880.SH","ticker":"510880",
                 "last_price":3.387,"open_price":3.378,"high_price":3.397,"low_price":3.372,
                 "prev_price":3.382,"price_change_ratio_pct":0.147842,"price_change":0.005,
                 "price_amplitude_ratio_pct":0.739208,"volume":136263960,"turnover":461654710,
                 "turnover_ratio_pct":2.281838}]}}
                """.trimIndent()
            ).data!!.item!![0]
        )!!

        assertThat(snapshot.stockCode).isEqualTo("sh.510880")
        assertThat(snapshot.price).isWithin(1e-9).of(3.387)
        assertThat(snapshot.amplitude).isWithin(1e-9).of(0.739208)
        assertThat(snapshot.turnoverRate).isWithin(1e-9).of(2.281838)
        assertThat(snapshot.volume).isWithin(1e-9).of(1362639.60)  // 股→手
    }

    @Test
    fun `unmappable thscode returns null snapshot`() {
        val snapshot = toQuoteSnapshotFromFuyao(
            parse<FuyaoEnvelope<FuyaoSnapshotData>>("""{"code":0,"data":{"item":[{"thscode":"899050.BJ"}]}}""").data!!.item!![0]
        )
        assertThat(snapshot).isNull()   // 北交所 App 不支持
    }

    @Test
    fun `supplementedFrom fills only null fields from eastmoney`() {
        val fuyaoSide = QuoteSnapshot(
            stockCode = "sh.600519", price = 1272.83, changePct = -1.445606, volume = 33472.31
        )
        val eastMoneySide = QuoteSnapshot(
            stockCode = "sh.600519", price = 1270.0, changePct = -1.6,
            pe = 19.54, pb = 6.33, totalMarketCap = 1591141000000.0, turnoverRate = 0.27
        )

        val merged = fuyaoSide.supplementedFrom(eastMoneySide)

        // 扶摇为权威：已有字段不被东财覆盖
        assertThat(merged.price).isWithin(1e-9).of(1272.83)
        assertThat(merged.changePct).isWithin(1e-9).of(-1.445606)
        // 缺失字段由东财补齐
        assertThat(merged.pe).isWithin(1e-9).of(19.54)
        assertThat(merged.pb).isWithin(1e-9).of(6.33)
        assertThat(merged.totalMarketCap).isWithin(1e-9).of(1591141000000.0)
        assertThat(merged.turnoverRate).isWithin(1e-9).of(0.27)
        // 东财补齐失败（null）不影响主源
        assertThat(fuyaoSide.supplementedFrom(null).price).isWithin(1e-9).of(1272.83)
    }

    @Test
    fun `toIndexQuoteFromFuyao keeps real values`() {
        // 实测：上证指数批量快照 3905.2 / +0.037913%
        val quote = parse<FuyaoEnvelope<FuyaoSnapshotData>>(
            """
            {"code":0,"data":{"item":[{"thscode":"000001.SH","ticker":"1A0000","volume":44689587000,
             "turnover":883423480000,"last_price":3905.2,"price_change":1.48,
             "price_change_ratio_pct":0.037913,"open_price":3891.18,"high_price":3912.13,
             "low_price":3883.79,"prev_price":3903.72}]}}
            """.trimIndent()
        ).data!!.item!![0].toIndexQuoteFromFuyao(code6 = "000001", name = "上证指数")

        assertThat(quote.code).isEqualTo("000001")
        assertThat(quote.name).isEqualTo("上证指数")
        assertThat(quote.price).isWithin(1e-9).of(3905.2)
        assertThat(quote.changePct).isWithin(1e-9).of(0.037913)
        assertThat(quote.prevClose).isWithin(1e-9).of(3903.72)
        assertThat(quote.amount).isWithin(1e-9).of(883423480000.0)
    }

    // ── 纯映射工具 ─────────────────────────────────────────────

    @Test
    fun `ms timestamp converts to shanghai calendar date`() {
        // 实测：农行 2025 中期分红 ex_date_ms=1765728000000 → 2025-12-15（Asia/Shanghai 日历日）
        assertThat(1765728000000L.fuyaoMsToDateStringOrNull()).isEqualTo("2025-12-15")
        val nullMs: Long? = null
        assertThat(nullMs.fuyaoMsToDateStringOrNull()).isNull()
    }

    @Test
    fun `thscode mapping both directions`() {
        assertThat("sh.600519".toFuyaoThscodeOrNull()).isEqualTo("600519.SH")
        assertThat("sz.000001".toFuyaoThscodeOrNull()).isEqualTo("000001.SZ")
        assertThat("sz.159915".toFuyaoThscodeOrNull()).isEqualTo("159915.SZ")
        assertThat("600519".toFuyaoThscodeOrNull()).isNull()          // 无前缀
        assertThat("bj.899050".toFuyaoThscodeOrNull()).isNull()       // 北交所不支持

        assertThat("600519.SH".fuyaoThscodeToAppCodeOrNull()).isEqualTo("sh.600519")
        assertThat("399006.SZ".fuyaoThscodeToAppCodeOrNull()).isEqualTo("sz.399006")
        assertThat("899050.BJ".fuyaoThscodeToAppCodeOrNull()).isNull()
        assertThat("023572.OF".fuyaoThscodeToAppCodeOrNull()).isNull() // 场外基金
    }

    @Test
    fun `report date converts to year-quarter param`() {
        assertThat("2024-12-31".toFuyaoReportParamOrNull()).isEqualTo("2024-4")
        assertThat("2025-03-31".toFuyaoReportParamOrNull()).isEqualTo("2025-1")
        assertThat("2024-06-30".toFuyaoReportParamOrNull()).isEqualTo("2024-2")
        assertThat("2024-09-30".toFuyaoReportParamOrNull()).isEqualTo("2024-3")
        assertThat("2024-05-31".toFuyaoReportParamOrNull()).isNull()  // 非季末
        assertThat("garbage".toFuyaoReportParamOrNull()).isNull()
    }

    // ── 财务指标 ───────────────────────────────────────────────

    @Test
    fun `indicatorValueOf parses real indicators response`() {
        // 实测：茅台 2024 年报（report=2024-4）指标片段；value 为字符串数值（非 JSON number）
        val json = """
        {"code":0,"data":{"thscode":"600519.SH","report":"2024-4","abilities":[
          {"ability":"growth","indicators":[
            {"index_id":"calculate_operating_income_yoy_growth_ratio","value":"15.71195100"},
            {"index_id":"calculate_parent_holder_net_profit_yoy_growth_ratio","value":"15.37996600"}]},
          {"ability":"profitability","indicators":[
            {"index_id":"index_deduct_weighted_avg_roe","value":"36.0300"},
            {"index_id":"index_weighted_avg_roe","value":"36.0200"}]},
          {"ability":"solvency","indicators":[
            {"index_id":"assets_debt_ratio","value":"19.0448"}]}]}}
        """.trimIndent()

        val data = parse<FuyaoEnvelope<FuyaoIndicatorsData>>(json).data

        // 与东财 RPT_LICO_FN_CPD 对应字段公开值核对：茅台 2024 加权 ROE≈36.02、资产负债率≈19.04
        assertThat(data.indicatorValueOf(FuyaoIndicatorIds.WEIGHTED_ROE)).isWithin(1e-6).of(36.02)
        assertThat(data.indicatorValueOf(FuyaoIndicatorIds.ASSETS_DEBT_RATIO)).isWithin(1e-6).of(19.0448)
        assertThat(data.indicatorValueOf(FuyaoIndicatorIds.REVENUE_YOY)).isWithin(1e-6).of(15.711951)
        assertThat(data.indicatorValueOf(FuyaoIndicatorIds.PARENT_NET_PROFIT_YOY)).isWithin(1e-6).of(15.379966)
        // 不存在的指标 id → null（不臆造）
        assertThat(data.indicatorValueOf("no_such_id")).isNull()
        // 未披露期（data=null）→ 全部 null
        val nullData: FuyaoIndicatorsData? = null
        assertThat(nullData.indicatorValueOf(FuyaoIndicatorIds.WEIGHTED_ROE)).isNull()
    }

    // ── 财务三表 ───────────────────────────────────────────────

    @Test
    fun `statements builder uses period_end_ms as reportDate and maps subjects`() {
        // 实测：茅台 2026Q2 三表（注意 report_date_ms 是公告日，两个季度会同值——绝不能当报告期）
        val income = """
        [{"thscode":"600519.SH","period":"quarterly","fiscal_year":2026,"fiscal_period":"Q2",
          "report_date_ms":1786723200000,"period_end_ms":1782748800000,"currency":"CNY",
          "operating_income":51180000000,"operating_costs":5100000000,"sales_fee":null,
          "manage_fee":null,"operating_profit":38800000000,"profit_total":39000000000,
          "income_tax_expense":9000000000,"net_profit":30000000000,
          "parent_holder_net_profit":29900000000,"basic_eps":23.81}]
        """.trimIndent()
        val balance = """
        [{"thscode":"600519.SH","fiscal_year":2026,"fiscal_period":"Q2",
          "report_date_ms":1786723200000,"period_end_ms":1782748800000,"currency":"CNY",
          "assets_total":309050784569.31,"cash":53518798979.08,"accounts_receivable":570895.04,
          "total_debt":46954432394.95,"holder_equity_total":262096352174.36}]
        """.trimIndent()
        val cashFlow = """
        [{"thscode":"600519.SH","fiscal_year":2026,"fiscal_period":"Q2",
          "report_date_ms":1786723200000,"period_end_ms":1782748800000,"currency":"CNY",
          "act_cash_flow_net":70690750119.06,"invest_cash_flow_net":25640543520.6,
          "financing_cash_flow_net":-37944297802.12,"cash_equivalents_net_addition":58385486034.9}]
        """.trimIndent()

        val statements = FuyaoStatementsBuilder.build(
            income = parseList(income),
            balance = parseList(balance),
            cashFlow = parseList(cashFlow)
        )!!

        assertThat(statements.periods).hasSize(1)
        val p = statements.periods[0]
        // 报告期取 period_end_ms（2026-06-30），不是 report_date_ms（公告日）
        assertThat(p.reportDate).isEqualTo("2026-06-30")
        // 营业总收入：扶摇 operating_income 为「营业收入」口径（2026-08-23 审计 M1：
        // 茅台 2026H1 扶摇 907.0 亿 vs 东财营业总收入 922.8 亿），恒 null 由东财并行回填
        assertThat(p.totalOperateIncome).isNull()
        assertThat(p.operateCost).isWithin(0.01).of(5100000000.0)
        assertThat(p.operateProfit).isWithin(0.01).of(38800000000.0)
        assertThat(p.parentNetProfit).isWithin(0.01).of(29900000000.0)
        assertThat(p.totalAssets).isWithin(0.01).of(309050784569.31)
        assertThat(p.totalLiabilities).isWithin(0.01).of(46954432394.95)
        assertThat(p.totalEquity).isWithin(0.01).of(262096352174.36)
        assertThat(p.monetaryFunds).isWithin(0.01).of(53518798979.08)
        assertThat(p.netcashOperate).isWithin(0.01).of(70690750119.06)
        assertThat(p.netcashFinance).isWithin(0.01).of(-37944297802.12)
        // 扶摇缺口科目 → null，由东财并行补齐
        assertThat(p.financeExpense).isNull()
        assertThat(p.deductParentNetProfit).isNull()
        assertThat(p.endCce).isNull()
        assertThat(p.inventory).isNull()
        assertThat(p.accountsPayable).isNull()
        assertThat(p.fixedAsset).isNull()
    }

    @Test
    fun `statements period supplementedFrom fills missing subjects from eastmoney`() {
        val fuyaoSide = FinancialStatements.Period(
            reportDate = "2026-06-30",
            totalOperateIncome = 51180000000.0, operateCost = 5100000000.0,
            saleExpense = null, manageExpense = null, financeExpense = null,
            operateProfit = 38800000000.0, totalProfit = 39000000000.0,
            incomeTax = 9000000000.0, parentNetProfit = 29900000000.0,
            deductParentNetProfit = null,
            netcashOperate = 70690750119.06, netcashInvest = 25640543520.6,
            netcashFinance = -37944297802.12, endCce = null,
            totalAssets = 309050784569.31, totalLiabilities = 46954432394.95,
            totalEquity = 262096352174.36, monetaryFunds = 53518798979.08,
            accountsRece = 570895.04, inventory = null, accountsPayable = null,
            fixedAsset = null
        )
        val eastMoneySide = fuyaoSide.copy(
            totalOperateIncome = 51179999999.0,   // 东财同期值略有出入——扶摇为权威不覆盖
            financeExpense = 1000000.0,
            deductParentNetProfit = 29000000000.0,
            endCce = 60000000000.0,
            inventory = 40000000000.0,
            accountsPayable = 5000000000.0,
            fixedAsset = 20000000000.0
        )

        val merged = fuyaoSide.supplementedFrom(eastMoneySide)

        assertThat(merged.totalOperateIncome).isWithin(0.01).of(51180000000.0)   // 权威值保留
        assertThat(merged.financeExpense).isWithin(0.01).of(1000000.0)           // 缺口补齐
        assertThat(merged.deductParentNetProfit).isWithin(0.01).of(29000000000.0)
        assertThat(merged.endCce).isWithin(0.01).of(60000000000.0)
        assertThat(merged.inventory).isWithin(0.01).of(40000000000.0)
        assertThat(merged.accountsPayable).isWithin(0.01).of(5000000000.0)
        assertThat(merged.fixedAsset).isWithin(0.01).of(20000000000.0)
        assertThat(fuyaoSide.supplementedFrom(null)).isSameInstanceAs(fuyaoSide)
    }

    // ── 财务摘要（扶摇指标 + 东财补齐合并）────────────────────

    @Test
    fun `mergeFundamentalsPeriods fills dividendPlan and appends eastmoney-only periods`() {
        val fuyaoPeriods = listOf(
            Fundamentals.Period(
                reportDate = "2026-06-30", roe = 18.0, debtToAssetRatio = 19.0,
                revenueYoy = 8.0, netProfitYoy = 7.0, basicEps = 23.81
            ),
            Fundamentals.Period(
                reportDate = "2026-03-31", roe = 9.0, debtToAssetRatio = 18.5,
                revenueYoy = 6.0, netProfitYoy = 5.0, basicEps = 11.0
            )
        )
        val eastMoneyPeriods = listOf(
            Fundamentals.Period(
                reportDate = "2026-06-30", roe = 17.9, debtToAssetRatio = 19.1,
                revenueYoy = 8.1, netProfitYoy = 7.1, basicEps = 23.80,
                announceYield = 3.2, dividendPlan = "10派239.57元(含税)"
            ),
            // 东财独有旧期（扶摇窗口未覆盖）→ 追加
            Fundamentals.Period(
                reportDate = "2025-09-30", roe = 26.0, debtToAssetRatio = 18.0,
                revenueYoy = 15.0, netProfitYoy = 14.0, basicEps = 40.0,
                dividendPlan = "10派276.24元(含税)"
            )
        )

        val merged = mergeFundamentalsPeriods(fuyaoPeriods, eastMoneyPeriods)

        // 升序三期：2025-09-30（东财追加）+ 2026-03-31 + 2026-06-30
        assertThat(merged.map { it.reportDate }).containsExactly(
            "2025-09-30", "2026-03-31", "2026-06-30"
        ).inOrder()
        val latest = merged.last()
        // 扶摇数值为权威
        assertThat(latest.roe).isWithin(1e-9).of(18.0)
        assertThat(latest.basicEps).isWithin(1e-9).of(23.81)
        // 东财仅补 null 字段（分红方案/公告股息率）
        assertThat(latest.dividendPlan).isEqualTo("10派239.57元(含税)")
        assertThat(latest.announceYield).isWithin(1e-9).of(3.2)
        // 东财空补齐 → 原样
        assertThat(mergeFundamentalsPeriods(fuyaoPeriods, emptyList())).hasSize(2)
    }

    /** JSON 数组 → DTO 列表（泛型 TypeToken，防擦除）。 */
    private inline fun <reified T> parseList(json: String): List<T> =
        gson.fromJson(json, object : TypeToken<List<T>>() {}.type)

    // ══ 全量接入新增端点 fixture（实测 2026-08-23）════════════════

    @Test
    fun `valuation snapshot parses pe and pb`() {
        // 实测：农行/茅台估值（PE_TTM 与东财/腾讯同刻一致：8.06 / 19.54）
        val json = """
        {"code":0,"data":{"total":2,"item":[
          {"thscode":"601288.SH","ticker":"601288","name":"农业银行",
           "pe_ttm":8.062947,"pe_mrq":7.890154,"pb_mrq":0.839487,"ps_ttm":3.106562,"pcf_ttm":0.58351},
          {"thscode":"600519.SH","ticker":"600519","name":"贵州茅台","pe_ttm":19.539033,"pb_mrq":6.33281}]}}
        """.trimIndent()

        val data = parse<FuyaoEnvelope<com.stock.dividend.data.remote.dto.FuyaoValuationData>>(json).data!!

        val abank = data.item!![0]
        assertThat(abank.name).isEqualTo("农业银行")
        assertThat(abank.peTtm).isWithin(1e-9).of(8.062947)
        assertThat(abank.pbMrq).isWithin(1e-9).of(0.839487)
        val maotai = data.item!![1]
        assertThat(maotai.peTtm).isWithin(1e-9).of(19.539033)
        assertThat(maotai.psTtm).isNull()   // 缺失字段 null 不臆造
    }

    @Test
    fun `trading days parse yyyyMMdd dates`() {
        val json = """
        {"code":0,"data":{"item":[{"date_ms":1756051200000,"date":"20250825"},
          {"date_ms":1756137600000,"date":"20250826"}]}}
        """.trimIndent()

        val data = parse<FuyaoEnvelope<com.stock.dividend.data.remote.dto.FuyaoTradingDaysData>>(json).data!!

        assertThat(data.item!!).hasSize(2)
        assertThat(data.item!![0].date).isEqualTo("20250825")
    }

    @Test
    fun `dragon tiger change and net rate are decimal fractions converted to percent`() {
        // 实测：哈药股份 跌停 -0.10022（= -10.022%）、净买入占比 0.04056451（= 4.06%）
        // —— 扶摇全 API 唯一小数分数比率字段，域转换 ×100（toDragonTigerBoard）
        val json = """
        {"code":0,"data":{"board_type":"all","trade_date":"2026-08-21","count":61,"stock_count":54,
          "stock_items":[{"thscode":"600664.SH","ticker":"600664","name":"哈药股份",
           "concept_list":[{"name":"振兴东北"},{"name":"流感"}],
           "change":-0.10022,"net_value":149273537.66,"net_rate":0.04056451,"hot_rank":2,
           "buy_value":349180829.03,"sell_value":199907291.37,"limit_reason":"医药","range_days":1,
           "hot_money_net_value":47294254.5}]}}
        """.trimIndent()

        val board = parse<FuyaoEnvelope<com.stock.dividend.data.remote.dto.FuyaoDragonTigerData>>(json)
            .data!!.toDragonTigerBoard()

        assertThat(board.tradeDate).isEqualTo("2026-08-21")
        val entry = board.entries.single()
        assertThat(entry.securityCode).isEqualTo("600664")
        assertThat(entry.securityName).isEqualTo("哈药股份")
        // 小数分数 ×100 → 百分比口径
        assertThat(entry.changePct).isWithin(1e-9).of(-10.022)
        assertThat(entry.netBuyPct).isWithin(1e-9).of(4.056451)
        // 金额原值（元）
        assertThat(entry.netBuy).isWithin(0.01).of(149273537.66)
        assertThat(entry.hotMoneyNetBuy).isWithin(0.01).of(47294254.5)
        assertThat(entry.concepts).containsExactly("振兴东北", "流感").inOrder()
    }

    @Test
    fun `limit pool pagination and item fields parse`() {
        // 实测（周末空池场景）+ 文档字段示例合并；price_change_ratio_pct 为百分比原值
        val json = """
        {"code":0,"data":{"timestamp":1748102400000,
          "pagination":{"total":126,"pages":3,"size":50,"page":1},
          "item":[{"thscode":"603986.SH","ticker":"603986","name":"兆易创新","is_st":false,"is_new":false,
           "last_price":128.5,"price_change_ratio_pct":10.0,"limit_up_time":"09:25",
           "limit_up_reason":"存储芯片涨价","continue_day_text":"首板","continue_day_cnt":1,
           "seal_money":123456789.01,"max_seal_money":223456789.01}]}}
        """.trimIndent()

        val data = parse<FuyaoEnvelope<com.stock.dividend.data.remote.dto.FuyaoLimitPoolData>>(json).data!!

        assertThat(data.pagination!!.total).isEqualTo(126)
        val item = data.item!!.single()
        assertThat(item.changePct).isWithin(1e-9).of(10.0)
        assertThat(item.sealMoney).isWithin(0.01).of(123456789.01)
        assertThat(item.continueDayText).isEqualTo("首板")
    }

    @Test
    fun `hot stock list parses rank and string heat`() {
        // 实测：热股榜（heat 为字符串数值 "7886619"）
        val json = """
        {"code":0,"data":{"item":[{"thscode":"688836.SH","ticker":"688836","name":"宇树科技",
          "rank":1,"heat":"7886619","rank_change":0,"rank_trend":"flat"},
          {"thscode":"002015.SZ","ticker":"002015","name":"协鑫能科","rank":2,"heat":"64044.2",
           "rank_change":27,"rank_trend":"up"}]}}
        """.trimIndent()

        val data = parse<FuyaoEnvelope<com.stock.dividend.data.remote.dto.FuyaoHotStockData>>(json).data!!

        assertThat(data.item!!).hasSize(2)
        assertThat(data.item!![0].heat).isWithin(0.1).of(7886619.0)
        assertThat(data.item!![1].rankChange).isEqualTo(27)
    }

    @Test
    fun `fund profile parses scale managers and rates`() {
        // 实测：红利ETF 资料（规模 202.3 亿元、经理任职回报、管理费 0.50%）
        val json = """
        {"code":0,"data":{"item":[{"thscode":"510880.SH","ticker":"510880",
          "fund_name":"红利ETF华泰柏瑞","estab_date":1163692800000,"company_id":"00089990",
          "mgmt_name":"华泰柏瑞基金管理有限公司","manager_name":"李茜",
          "fund_scale":20230842936.46,"unit_nav":3.3878,
          "manager_info":[{"manager_id":"T155695300","manager_name":"李茜","tenure_return_pct":66.45,
            "tenure_days":2483,"start_date_ms":1572883200000}],
          "rate_info":[{"rate_type":"management","charge_mode":"ongoing","standard_rate":"0.50%"}]}]}}
        """.trimIndent()

        val profile = parse<FuyaoEnvelope<com.stock.dividend.data.remote.dto.FuyaoFundProfileData>>(json)
            .data!!.item!!.single()

        assertThat(profile.fundName).isEqualTo("红利ETF华泰柏瑞")
        assertThat(profile.fundScale).isWithin(0.01).of(20230842936.46)
        assertThat(profile.unitNav).isWithin(1e-9).of(3.3878)
        val manager = profile.managerInfo!!.single()
        assertThat(manager.managerId).isEqualTo("T155695300")
        assertThat(manager.tenureReturnPct).isWithin(1e-9).of(66.45)
        val rate = profile.rateInfo!!.single()
        assertThat(rate.rateType).isEqualTo("management")
        assertThat(rate.standardRate).isEqualTo("0.50%")
    }

    @Test
    fun `fund holdings parse summary and top position`() {
        // 实测：红利ETF 重仓（中远海控 4.82%；集中度 0.3135 为小数分数 = 31.35%）
        val json = """
        {"code":0,"data":{"total_stock_ratio_pct":30.95,"stock_ratio_pct":98.73,
          "main_industry":"均衡","concentration_ratio":0.3135,"timestamp":0,
          "item":[{"thscode":"601919.SH","ticker":"601919","stock_name":"中远海控",
           "hold_ratio":4.82,"asset_type":"stock","position_capital":1073215510.38,
           "position_count":80936313,"security_market_value_rate_pct":0.04856,
           "period_increase_rate_pct":-0.04,"investment_rank":1,
           "start_date_ms":1774972800000,"end_date_ms":1782748800000}]}}
        """.trimIndent()

        val data = parse<FuyaoEnvelope<com.stock.dividend.data.remote.dto.FuyaoFundHoldingsData>>(json).data!!

        assertThat(data.stockRatioPct).isWithin(1e-9).of(98.73)
        assertThat(data.mainIndustry).isEqualTo("均衡")
        assertThat(data.concentrationRatio).isWithin(1e-9).of(0.3135)   // 小数分数（文档口径：前十占比）
        val top = data.item!!.single()
        assertThat(top.stockName).isEqualTo("中远海控")
        assertThat(top.holdRatio).isWithin(1e-9).of(4.82)
        assertThat(top.investmentRank).isEqualTo(1)
    }

    @Test
    fun `fund returns parse multi period and peer average`() {
        // 实测：红利ETF 区间收益（成立以来 302.91%、近一年 8.92%）
        val json = """
        {"code":0,"data":{"item":[{"return_week":2.87,"return_month":5.68,"return_tmonth":6.99,
          "return_hyear":8.09,"return_year":8.92,"return_twoyear":21.23,"return_tyear":29.81,
          "return_fyear":45.85,"return_nowyear":11.45,"return_now":302.91,
          "peer_average_week":-0.7731,"peer_average_month":0.3546}]}}
        """.trimIndent()

        val r = parse<FuyaoEnvelope<com.stock.dividend.data.remote.dto.FuyaoFundReturnsData>>(json)
            .data!!.item!!.single()

        assertThat(r.sinceInception).isWithin(1e-9).of(302.91)
        assertThat(r.year).isWithin(1e-9).of(8.92)
        assertThat(r.peerWeek).isWithin(1e-9).of(-0.7731)
    }

    @Test
    fun `fund nav and holders parse`() {
        val navJson = """
        {"code":0,"data":{"item":[{"nav_date":1784563200000,"unit_nav":3.2056,"adj_nav":3.8124}]}}
        """.trimIndent()
        val nav = parse<FuyaoEnvelope<com.stock.dividend.data.remote.dto.FuyaoFundNavData>>(navJson)
            .data!!.item!!.single()
        assertThat(nav.unitNav).isWithin(1e-9).of(3.2056)
        assertThat(nav.adjNav).isWithin(1e-9).of(3.8124)

        // 实测：红利ETF 持有人结构（机构 25.96% / 个人 74.04%）
        val holdersJson = """
        {"code":0,"data":{"item":[{"merge_scope":"separate","report_date_ms":1767110400000,
          "ins_position":25.96,"holder_amount":416667,"avg_holder_share":14533.61,
          "psnl_rate":74.04,"mgmt_staff_hold_rate":0.0}]}}
        """.trimIndent()
        val holder = parse<FuyaoEnvelope<com.stock.dividend.data.remote.dto.FuyaoFundHoldersData>>(holdersJson)
            .data!!.item!!.single()
        assertThat(holder.institutionPct).isWithin(1e-9).of(25.96)
        assertThat(holder.personalPct).isWithin(1e-9).of(74.04)
    }

    @Test
    fun `ths index list and constituents parse`() {
        // 实测：同花顺行业指数清单 + 沪深300 成分
        val listJson = """
        {"code":0,"data":{"item":[{"thscode":"881101.TI","name":"种植业与林业"},
          {"thscode":"884001.TI","name":"种子生产"}]}}
        """.trimIndent()
        val list = parse<FuyaoEnvelope<com.stock.dividend.data.remote.dto.FuyaoThsIndexListData>>(listJson)
            .data!!.item!!
        assertThat(list).hasSize(2)
        assertThat(list[0].thscode).isEqualTo("881101.TI")

        val constituentsJson = """
        {"code":0,"data":{"item":[{"thscode":"302132.SZ","ticker":"302132","name":"中航成飞"},
          {"thscode":"601018.SH","ticker":"601018","name":"宁波港"}]}}
        """.trimIndent()
        val constituentsEnvelope: FuyaoEnvelope<com.stock.dividend.data.remote.dto.FuyaoConstituentsData> =
            parse(constituentsJson)
        val constituents = constituentsEnvelope.data!!.item!!
        assertThat(constituents).hasSize(2)
        assertThat(constituents[0].name).isEqualTo("中航成飞")
    }
}
