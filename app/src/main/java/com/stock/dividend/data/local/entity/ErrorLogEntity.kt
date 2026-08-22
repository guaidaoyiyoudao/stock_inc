package com.stock.dividend.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 关键失败日志（`error_logs` 表，DB v26 新增）。
 *
 * 收集「静默失败」的关键事件（红线 #2 吞异常处的数据获取失败等），供
 * 设置 → 数据 → 失败日志 页查看与清理。记录本身也全程吞异常——日志绝不能把主流程搞挂。
 */
@Entity(tableName = "error_logs")
data class ErrorLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 发生时间（epoch millis）。 */
    val timestamp: Long,
    /** 分类 raw（[com.stock.dividend.data.repository.ErrorLogCategory.name]）。 */
    val category: String,
    /** 来源模块中文名（行情/分红/K线/基本面…）。 */
    val source: String,
    /** 失败摘要（面向用户，含标的代码等上下文）。 */
    val message: String,
    /** 详细信息（异常类名+消息+裁剪后的堆栈），可空。 */
    val detail: String? = null,
)
