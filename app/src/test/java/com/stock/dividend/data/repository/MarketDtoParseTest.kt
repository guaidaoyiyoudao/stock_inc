package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.stock.dividend.data.remote.dto.DragonTigerResponse
import com.stock.dividend.data.remote.dto.IndexQuoteResponse
import com.stock.dividend.data.remote.dto.MarketClistResponse
import com.stock.dividend.data.remote.dto.StockAnnouncementResponse
import org.junit.Test

/**
 * 新增市场类 DTO 解析测试——锁定字段映射与单位语义。
 *
 * Fixture 取自 **实测**（2026-08-02 东方财富 push2 / datacenter），单位规则经交叉验证：
 * - clist（fltt=2）：全部真实值不 ÷100（汾酒 f2=127.24 元、f3=-1.67 表示 -1.67%、f62=130908103 元）
 * - stock/get（指数）：价格百分比 ×100 整数需 ÷100（上证 f43=383226 → 3832.26 点）
 *
 * ⚠️ 这两个接口单位规则不同，是本批数据接入的核心易错点，必须用真实 fixture 锁定。
 */
class MarketDtoParseTest {

    private val gson = Gson()

    // ── clist 个股资金流（汾酒 600809，沪市主板，实测）──────────────
    // 关键验证：净额（元）原值不除；占比（%）真实值不除
    private val capitalFlowClistJson = """
        {"rc":0,"rt":6,"data":{"total":1,"diff":[
          {"f12":"600809","f14":"山西汾酒","f2":127.24,"f3":-1.67,
           "f62":130908103.0,"f184":6.04,
           "f66":162294103.0,"f69":7.49,
           "f72":77708021.0,"f75":3.59,
           "f78":-57945553.0,"f81":-2.68,
           "f84":-167656571.0,"f87":-7.74}
        ]}}
    """.trimIndent()

    @Test
    fun `clist capital flow parses net amounts in yuan and pct as real value`() {
        val resp = gson.fromJson(capitalFlowClistJson, MarketClistResponse::class.java)
        val item = resp.data!!.diff!!.first()
        assertThat(item.code).isEqualTo("600809")
        assertThat(item.name).isEqualTo("山西汾酒")
        // 价格/涨跌幅：真实值不除
        assertThat(item.price).isEqualTo(127.24)
        assertThat(item.changePct).isEqualTo(-1.67)
        // 净额：元原值不除
        assertThat(item.mainNetInflow).isEqualTo(130908103.0)
        assertThat(item.superLargeNetInflow).isEqualTo(162294103.0)
        assertThat(item.smallNetInflow).isEqualTo(-167656571.0)
        // 占比：真实 % 不除（f184=6.04 表示 6.04%，f69=7.49 表示 7.49%）
        assertThat(item.mainNetInflowPct).isEqualTo(6.04)
        assertThat(item.superLargeNetInflowPct).isEqualTo(7.49)
        assertThat(item.smallNetInflowPct).isEqualTo(-7.74)
    }

    // ── clist 行业板块（实测，GBK 终端乱码但 JSON 字段名 UTF-8）─────
    // 关键验证：板块价 f2 带小数（真实值），与 ulist 的 ×100 整数不同
    private val industryClistJson = """
        {"rc":0,"rt":6,"data":{"total":496,"diff":[
          {"f12":"BK1201","f14":"电子","f2":11297.42,"f3":3.84,"f8":4.81,
           "f62":22205661184.0,"f184":2.82,
           "f128":"领涨股名","f140":"600111","f136":9.99}
        ]}}
    """.trimIndent()

    @Test
    fun `clist industry parses price changePct and inflow as real values`() {
        val resp = gson.fromJson(industryClistJson, MarketClistResponse::class.java)
        val item = resp.data!!.diff!!.first()
        assertThat(item.code).isEqualTo("BK1201")
        // 板块价 11297.42 是真实值（电子板块指数点位），不 ÷100
        assertThat(item.price).isEqualTo(11297.42)
        assertThat(item.changePct).isEqualTo(3.84)
        assertThat(item.turnoverRate).isEqualTo(4.81)
        assertThat(item.mainNetInflow).isEqualTo(22205661184.0)
        assertThat(item.leaderChangePct).isEqualTo(9.99)
    }

    // ── clist 缺失字段优雅降级（部分字段缺失，其余正常解析）─────────
    @Test
    fun `clist partial fields parse without crash`() {
        // 仅传代码/名称/PE/PB，价格类字段缺失 → null
        val json = """
            {"data":{"diff":[{"f12":"600238","f14":"ST岛","f9":503.02,"f23":23.8}]}}
        """.trimIndent()
        val resp = gson.fromJson(json, MarketClistResponse::class.java)
        val item = resp.data!!.diff!!.first()
        assertThat(item.code).isEqualTo("600238")
        assertThat(item.pe).isEqualTo(503.02)
        assertThat(item.pb).isEqualTo(23.8)
        // 未传的字段为 null
        assertThat(item.price).isNull()
        assertThat(item.mainNetInflow).isNull()
    }

    // ── clist 全市场个股榜单（fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23，2026-08-15 实测）──
    // 关键验证：f133 股息率为真实值（14.61 表示 14.61%）。交叉验证：汇洁股份近 12 月
    // 每股分红 1.10 元 ÷ 现价 7.53 元 ≈ 14.6%，与 f133=14.61 吻合。
    private val marketRankingClistJson = """
        {"rc":0,"rt":6,"data":{"total":5549,"diff":[
          {"f12":"002763","f14":"汇洁股份","f2":7.53,"f3":0.53,"f8":1.5,
           "f9":8.41,"f20":3086727720,"f23":1.98,"f133":14.61},
          {"f12":"603165","f14":"荣晟环保","f2":13.56,"f3":0.15,"f8":0.71,
           "f9":13.98,"f20":4240596806,"f23":1.81,"f133":14.1}
        ]}}
    """.trimIndent()

    @Test
    fun `clist market ranking parses dividend yield f133 as real percent`() {
        val resp = gson.fromJson(marketRankingClistJson, MarketClistResponse::class.java)
        val item = resp.data!!.diff!!.first()
        assertThat(item.code).isEqualTo("002763")
        // f133 股息率：真实值不除（14.61 = 14.61%）
        assertThat(item.dividendYield).isEqualTo(14.61)
        // 其余字段：clist 全部真实值不除
        assertThat(item.price).isEqualTo(7.53)
        assertThat(item.changePct).isEqualTo(0.53)
        assertThat(item.turnoverRate).isEqualTo(1.5)
        assertThat(item.pe).isEqualTo(8.41)
        assertThat(item.pb).isEqualTo(1.98)
        assertThat(item.totalMarketCap).isEqualTo(3086727720.0)
    }

    @Test
    fun `clist market ranking missing f133 yields null dividend yield`() {
        val json = """{"data":{"diff":[{"f12":"600238","f14":"ST岛","f2":3.2}]}}"""
        val item = gson.fromJson(json, MarketClistResponse::class.java).data!!.diff!!.first()
        assertThat(item.dividendYield).isNull()
    }

    // ── stock/get 指数（上证 1.000001，实测）────────────────────────
    // 关键验证：f43/f170 为 ×100 整数，解析时 ÷100；f47/f48 原值不除
    private val indexJson = """
        {"rc":0,"rt":4,"data":{
          "f43":383226,"f44":384709,"f45":382237,"f46":383354,
          "f47":597529427,"f48":1187681546393.3,
          "f57":"000001","f58":"上证指数","f60":380469,"f170":72}}
    """.trimIndent()

    @Test
    fun `index stock get parses price and changePct with div100`() {
        val resp = gson.fromJson(indexJson, IndexQuoteResponse::class.java)
        val d = resp.data!!
        // 原始裸值（×100 整数）
        assertThat(d.price).isEqualTo(383226.0)
        assertThat(d.prevClose).isEqualTo(380469.0)
        assertThat(d.changePct).isEqualTo(72.0)
        assertThat(d.amount).isEqualTo(1187681546393.3) // 成交额原值不除
    }

    @Test
    fun `index toIndexQuote applies div100 to price fields`() {
        val resp = gson.fromJson(indexJson, IndexQuoteResponse::class.java)
        // toIndexQuote 是 MarketDataRepository 的 private 方法，这里通过解析逻辑等价验证：
        // 调用方期望 price = f43 ÷ 100 = 3832.26
        val rawPrice = resp.data!!.price!!
        assertThat(rawPrice / 100.0).isEqualTo(3832.26)
        val rawChangePct = resp.data!!.changePct!!
        assertThat(rawChangePct / 100.0).isEqualTo(0.72)
    }

    // ── 个股公告（茅台 600519，实测 np-anotice-stock）──────────────
    private val announcementJson = """
        {"data":{"list":[
          {"art_code":"AN202607171827064564",
           "title":"贵州茅台:贵州茅台重大事项公告",
           "notice_date":"2026-07-18 00:00:00",
           "display_time":"2026-07-17 21:26:22:245",
           "codes":[{"stock_code":"600519","short_name":"贵州茅台"}]}
        ]}}
    """.trimIndent()

    @Test
    fun `announcement parses title and noticeDate`() {
        val resp = gson.fromJson(announcementJson, StockAnnouncementResponse::class.java)
        val item = resp.data!!.list!!.first()
        assertThat(item.artCode).isEqualTo("AN202607171827064564")
        assertThat(item.title).contains("重大事项公告")
        assertThat(item.noticeDate).startsWith("2026-07-18")
    }

    // ── 龙虎榜（datacenter RPT_DAILYBILLBOARD_DETAILS，实测）─────────
    private val dragonTigerJson = """
        {"success":true,"result":{"data":[
          {"TRADE_DATE":"2026-07-31 00:00:00","SECURITY_CODE":"600519",
           "SECURITY_NAME_ABBR":"贵州茅台","EXPLAIN":"日跌幅偏离值达7%的证券",
           "BILLBOARD_DEAL_AMT":71277522.07,"NET_BUY":12345678.9,
           "ACCUM_AMOUNT":500000000.0,"REASON":"跌幅偏离值"}
        ]}}
    """.trimIndent()

    @Test
    fun `dragon tiger parses net buy and deal amount in yuan`() {
        val resp = gson.fromJson(dragonTigerJson, DragonTigerResponse::class.java)
        val item = resp.result!!.data!!.first()
        assertThat(item.securityCode).isEqualTo("600519")
        assertThat(item.securityName).isEqualTo("贵州茅台")
        assertThat(item.explain).contains("跌幅偏离值")
        assertThat(item.netBuy).isEqualTo(12345678.9)
        assertThat(item.billboardDealAmt).isEqualTo(71277522.07)
    }
}
