package com.stock.dividend.data.remote

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.stock.dividend.data.remote.dto.MarketClistResponse
import org.junit.Test

/**
 * [LenientDoubleDeserializer]（lenientMarketGson）行为锁定——2026-08-20 审计 M1/M6 修复。
 *
 * 实测背景：clist 对退市/停牌股全字段返回 "-"（如国华退 f2="-"），默认 Gson 对
 * `Double?` 抛 NumberFormatException 且发生在整个 diff 数组反序列化阶段——**一条脏
 * 记录毒死整个列表**。容错 Gson 把 "-" 读成 null，单条降级而非整批丢弃。
 */
class LenientGsonTest {

    private val gson: Gson = lenientMarketGson()

    @Test
    fun `default gson throws on dash placeholder - documents the poison we are fixing`() {
        // 锁定背景事实：默认 Gson（无容错）遇 "-" 直接炸整批——这是本 adapter 存在的理由
        val json = """{"data":{"diff":[{"f2":"-"}]}}"""
        var threw = false
        try {
            Gson().fromJson(json, MarketClistResponse::class.java)
        } catch (e: NumberFormatException) {
            threw = true   // Gson 2.11 实测：JsonReader.nextDouble 对 "-" 直接抛 NFE（未包装）
        }
        assertThat(threw).isTrue()
    }

    @Test
    fun `lenient gson reads dash placeholder as null`() {
        val json = """{"data":{"diff":[{"f12":"000004","f14":"国华退","f2":"-","f3":"-"}]}}"""
        val response = gson.fromJson(json, MarketClistResponse::class.java)

        val item = response.data!!.diff!!.single()
        assertThat(item.price).isNull()
        assertThat(item.changePct).isNull()
        assertThat(item.code).isEqualTo("000004")   // 文本字段不受影响
    }

    @Test
    fun `one dirty record does not poison the whole list`() {
        // 2026-08-20 实测样本结构：正常记录 + 退市记录混排（clist 全市场列表常态）
        val json = """{"data":{"total":2,"diff":[
            {"f12":"603801","f14":"志邦家居","f2":7.23,"f133":5.4},
            {"f12":"000004","f14":"国华退","f2":"-","f133":"-"}
        ]}}"""
        val response = gson.fromJson(json, MarketClistResponse::class.java)

        val items = response.data!!.diff!!
        assertThat(items).hasSize(2)
        assertThat(items[0].price).isEqualTo(7.23)      // 正常记录完整保留
        assertThat(items[1].price).isNull()             // 脏记录字段降级，行不丢
    }

    @Test
    fun `numeric strings and null json still parse correctly`() {
        val json = """{"data":{"diff":[
            {"f12":"600519","f2":"1294.54"},
            {"f12":"000001","f2":null}
        ]}}"""
        val items = gson.fromJson(json, MarketClistResponse::class.java).data!!.diff!!

        assertThat(items[0].price).isEqualTo(1294.54)  // 数字字符串照常解析
        assertThat(items[1].price).isNull()            // JSON null → null
    }
}
