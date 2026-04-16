package com.stock.dividend.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "fire_goal")
data class FireGoalEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val targetAmount: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
