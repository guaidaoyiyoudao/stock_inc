package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.LivingExpenseItemDao
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_MONTHLY
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_YEARLY
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LivingExpenseRepository @Inject constructor(
    private val dao: LivingExpenseItemDao
) {
    fun observeExpenses(): Flow<List<LivingExpenseItemEntity>> = dao.observeAll()

    suspend fun addExpense(name: String, amount: Double, period: String): Long {
        requireValidExpense(name, amount, period)
        val nextOrder = (dao.getMaxSortOrder() ?: -1) + 1
        return dao.insert(
            LivingExpenseItemEntity(
                name = name.trim(),
                amount = amount,
                period = period,
                sortOrder = nextOrder
            )
        )
    }

    suspend fun updateExpense(id: Long, name: String, amount: Double, period: String) {
        requireValidExpense(name, amount, period)
        val existing = dao.getById(id) ?: return
        dao.update(
            existing.copy(
                name = name.trim(),
                amount = amount,
                period = period,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteExpense(id: Long) {
        dao.deleteById(id)
    }

    suspend fun moveUp(id: Long) {
        val items = dao.getAllOnce()
        val index = items.indexOfFirst { it.id == id }
        if (index <= 0) return
        swapOrders(items[index], items[index - 1])
    }

    suspend fun moveDown(id: Long) {
        val items = dao.getAllOnce()
        val index = items.indexOfFirst { it.id == id }
        if (index == -1 || index >= items.lastIndex) return
        swapOrders(items[index], items[index + 1])
    }

    private suspend fun swapOrders(first: LivingExpenseItemEntity, second: LivingExpenseItemEntity) {
        val now = System.currentTimeMillis()
        dao.updateSortOrders(first.id, second.sortOrder, now)
        dao.updateSortOrders(second.id, first.sortOrder, now)
    }

    private fun requireValidExpense(name: String, amount: Double, period: String) {
        require(name.isNotBlank()) { "支出名称不能为空" }
        require(amount > 0.0) { "支出金额必须大于零" }
        require(amount <= 999_999_999_999.0) { "金额超出有效范围" }
        require(period == EXPENSE_PERIOD_MONTHLY || period == EXPENSE_PERIOD_YEARLY) { "支出周期无效" }
    }
}
