package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.stock.dividend.data.remote.dto.BondYieldResponse
import org.junit.Test

/**
 * 验证 [BondYieldResponse] 对东方财富「中美国债收益率」接口真实 JSON 的解析。
 *
 * 样本取自 `datacenter.eastmoney.com/api/data/get?type=RPTA_WEB_TREASURYYIELD`，
 * 字段 `EMM00166466` 为中国 10 年期国债到期收益率（%）。
 */
class BondYieldResponseParseTest {

    private val gson = Gson()

    /**
     * 真实响应（仅保留必要字段 + 实际会出现的 null 字段）。
     * data[0] 为最新日期，10Y = 1.7337。
     */
    private val realJson = """
        {
          "version": "1.0.0",
          "result": {
            "pages": 1,
            "data": [
              {"SOLAR_DATE":"2026-07-27 00:00:00","EMM00588704":1.2688,"EMM00166462":1.4451,"EMM00166466":1.7337,"EMM00166469":2.193,"EMM01276014":0.4649,"EMM00000024":null,"EMG00001306":4.31,"EMG00001308":4.4,"EMG00001310":4.65,"EMG00001312":5.12,"EMG01339436":0.34,"EMG00159635":null},
              {"SOLAR_DATE":"2026-07-23 00:00:00","EMM00588704":1.2691,"EMM00166462":1.4401,"EMM00166466":1.7325,"EMM00166469":2.2045,"EMM01276014":0.4634,"EMM00000024":null,"EMG00001306":4.37,"EMG00001308":4.46,"EMG00001310":4.71,"EMG00001312":5.17,"EMG01339436":0.34,"EMG00159635":null}
            ],
            "count": 2
          },
          "success": true,
          "message": "ok",
          "code": 0
        }
    """.trimIndent()

    @Test
    fun `parses 10Y yield from real response`() {
        val resp = gson.fromJson(realJson, BondYieldResponse::class.java)
        val data = resp.result?.data
        assertThat(data).isNotNull()
        assertThat(data).hasSize(2)
        // 首条为最新日期
        assertThat(data!![0].solarDate).isEqualTo("2026-07-27 00:00:00")
        // 10Y 字段直接是 % 数值，无需换算
        assertThat(data[0].yield10Y).isWithin(1e-9).of(1.7337)
    }

    @Test
    fun `firstOrNull is the latest entry`() {
        val resp = gson.fromJson(realJson, BondYieldResponse::class.java)
        val latest = resp.result?.data?.firstOrNull()
        assertThat(latest?.yield10Y).isWithin(1e-9).of(1.7337)
    }

    @Test
    fun `handles null 10Y field gracefully`() {
        val json = """
            {"result":{"data":[{"SOLAR_DATE":"2026-07-27 00:00:00","EMM00166466":null}]}}
        """.trimIndent()
        val resp = gson.fromJson(json, BondYieldResponse::class.java)
        assertThat(resp.result?.data?.firstOrNull()?.yield10Y).isNull()
    }

    @Test
    fun `handles missing data array`() {
        val json = """{"result":{"pages":0}}"""
        val resp = gson.fromJson(json, BondYieldResponse::class.java)
        assertThat(resp.result?.data).isNull()
    }

    @Test
    fun `handles empty result`() {
        val json = """{"result":null}"""
        val resp = gson.fromJson(json, BondYieldResponse::class.java)
        assertThat(resp.result).isNull()
    }

    // ── 多期限国债收益率 / 中美利差 / LPR 字段（2026-08-02 扩展）──────
    // 各字段单位均为「%」，真实值不 ÷100（如 1.7141 表示 1.7141%）。
    @Test
    fun `parses multi tenor yields and lpr from real response`() {
        val resp = gson.fromJson(realJson, BondYieldResponse::class.java)
        val latest = resp.result!!.data!![0]
        // 中国国债：2Y/5Y/10Y/30Y
        assertThat(latest.yield2Y).isWithin(1e-9).of(1.2688)
        assertThat(latest.yield5Y).isWithin(1e-9).of(1.4451)
        assertThat(latest.yield10Y).isWithin(1e-9).of(1.7337)
        assertThat(latest.yield30Y).isWithin(1e-9).of(2.193)
        // 中美 10Y 利差
        assertThat(latest.cnUsSpread10Y).isWithin(1e-9).of(0.4649)
        // LPR：EMG00001306=1Y、EMG00001310=5Y（注意非 EMG00001308/12）
        assertThat(latest.lpr1Y).isWithin(1e-9).of(4.31)
        assertThat(latest.lpr5Y).isWithin(1e-9).of(4.65)
    }

    @Test
    fun `multi tenor fields default null when absent`() {
        val json = """
            {"result":{"data":[{"SOLAR_DATE":"2026-08-01","EMM00166466":1.71}]}}
        """.trimIndent()
        val resp = gson.fromJson(json, BondYieldResponse::class.java)
        val item = resp.result!!.data!![0]
        // 仅传 10Y，其余多期限字段缺失 → null（默认值）
        assertThat(item.yield10Y).isEqualTo(1.71)
        assertThat(item.yield2Y).isNull()
        assertThat(item.lpr5Y).isNull()
    }
}
