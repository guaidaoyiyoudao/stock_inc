package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.LivingExpenseItemDao
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LivingExpenseRepositoryTest {
    private val dao: LivingExpenseItemDao = mockk(relaxed = true)
    private val repository = LivingExpenseRepository(dao)

    @Test
    fun `addExpense assigns next sort order`() = runTest {
        val itemSlot = slot<LivingExpenseItemEntity>()
        coEvery { dao.getMaxSortOrder() } returns 4
        coEvery { dao.insert(capture(itemSlot)) } returns 10L

        repository.addExpense("房租", 3000.0, "MONTHLY")

        assertThat(itemSlot.captured.name).isEqualTo("房租")
        assertThat(itemSlot.captured.amount).isEqualTo(3000.0)
        assertThat(itemSlot.captured.period).isEqualTo("MONTHLY")
        assertThat(itemSlot.captured.sortOrder).isEqualTo(5)
    }

    @Test
    fun `addExpense uses zero sort order when table is empty`() = runTest {
        val itemSlot = slot<LivingExpenseItemEntity>()
        coEvery { dao.getMaxSortOrder() } returns null
        coEvery { dao.insert(capture(itemSlot)) } returns 1L

        repository.addExpense("餐饮", 2000.0, "MONTHLY")

        assertThat(itemSlot.captured.sortOrder).isEqualTo(0)
    }

    @Test
    fun `updateExpense updates editable fields and timestamp`() = runTest {
        val existing = LivingExpenseItemEntity(
            id = 2,
            name = "餐饮",
            amount = 2000.0,
            period = "MONTHLY",
            sortOrder = 1,
            createdAt = 100,
            updatedAt = 100
        )
        val updatedSlot = slot<LivingExpenseItemEntity>()
        coEvery { dao.getById(2) } returns existing

        repository.updateExpense(2, "食品", 24000.0, "YEARLY")

        coVerify { dao.update(capture(updatedSlot)) }
        assertThat(updatedSlot.captured.name).isEqualTo("食品")
        assertThat(updatedSlot.captured.amount).isEqualTo(24000.0)
        assertThat(updatedSlot.captured.period).isEqualTo("YEARLY")
        assertThat(updatedSlot.captured.sortOrder).isEqualTo(1)
        assertThat(updatedSlot.captured.createdAt).isEqualTo(100)
        assertThat(updatedSlot.captured.updatedAt).isGreaterThan(100)
    }

    @Test
    fun `moveUp swaps with previous item`() = runTest {
        val itemsFlow = MutableStateFlow(
            listOf(
                LivingExpenseItemEntity(1, "房租", 3000.0, "MONTHLY", 0),
                LivingExpenseItemEntity(2, "餐饮", 2000.0, "MONTHLY", 1)
            )
        )
        every { dao.observeAll() } returns itemsFlow
        coEvery { dao.getAllOnce() } returns itemsFlow.value

        repository.moveUp(2)

        coVerify { dao.updateSortOrders(2, 0, any()) }
        coVerify { dao.updateSortOrders(1, 1, any()) }
    }

    @Test
    fun `moveDown swaps with next item`() = runTest {
        val items = listOf(
            LivingExpenseItemEntity(1, "房租", 3000.0, "MONTHLY", 0),
            LivingExpenseItemEntity(2, "餐饮", 2000.0, "MONTHLY", 1)
        )
        coEvery { dao.getAllOnce() } returns items

        repository.moveDown(1)

        coVerify { dao.updateSortOrders(1, 1, any()) }
        coVerify { dao.updateSortOrders(2, 0, any()) }
    }
}
