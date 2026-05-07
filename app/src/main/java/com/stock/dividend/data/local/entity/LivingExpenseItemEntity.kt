package com.stock.dividend.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val EXPENSE_PERIOD_MONTHLY = "MONTHLY"
const val EXPENSE_PERIOD_YEARLY = "YEARLY"

@Entity(
    tableName = "living_expense_items",
    indices = [Index(value = ["sortOrder"])]
)
data class LivingExpenseItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val amount: Double,
    val period: String,
    val sortOrder: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
