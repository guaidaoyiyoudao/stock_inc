package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import org.junit.Test

class TradeStrategyRepositoryCodecTest {

    @Test
    fun risks_roundTrip() {
        val s = risksToJsonString(listOf("息差收窄", "地产敞口"))
        assertThat(risksFromJson(s)).containsExactly("息差收窄", "地产敞口").inOrder()
    }

    @Test
    fun risks_emptyList_toEmptyArray() {
        assertThat(risksToJsonString(emptyList())).isEqualTo("[]")
        assertThat(risksFromJson("[]")).isEmpty()
    }

    @Test
    fun risks_malformed_returnsEmpty() {
        assertThat(risksFromJson("not json")).isEmpty()
        assertThat(risksFromJson("{")).isEmpty()
    }

    @Test
    fun risks_null_returnsEmpty() {
        assertThat(risksFromJson(null)).isEmpty()
    }

    @Test
    fun toUserStrategyRef_daysAgo_andDirection() {
        val now = System.currentTimeMillis()
        val threeDaysAgo = now - 3L * 24 * 3600 * 1000
        val e = TradeStrategyEntity(
            id = "x",
            targetText = "招商银行",
            direction = "BUY",
            reasoning = "r",
            risks = "[]",
            validUntil = null,
            sourceNote = "研报",
            rawOcrText = "t",
            createdAt = threeDaysAgo
        )
        val ref = toUserStrategyRef(e, now)
        assertThat(ref.daysAgo).isEqualTo(3)
        assertThat(ref.direction).isEqualTo("BUY")
        assertThat(ref.reasoning).isEqualTo("r")
    }
}
