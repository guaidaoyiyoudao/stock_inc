package com.stock.dividend.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stock.dividend.data.local.dao.FuyaoCacheDao
import com.stock.dividend.data.local.entity.FuyaoCacheEntity
import java.lang.reflect.Type
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 扶摇数据持久缓存通用存取器（DB v28 `fuyao_cache`，payload=Gson JSON）。
 *
 * 三种取数语义（离线优先：断网/未配置 key 时历史数据依然可读）：
 * 1. [fetchFirstMerge] 合并式——历史不可变数据（交易日历/指数日K/基金持仓/净值/报告期）：
 *    远端覆盖同期、缓存独有的旧期次永续保留（mergeByReportDate 同思想）；
 * 2. [fetchFirstReplace] 覆盖式——慢变数据（指数目录/成分/基金资料等）：成功整体替换，
 *    失败/禁用回退缓存（旧值好过没有）；
 * 3. [cacheFirstForDate] 按日缓存优先——按自然日不可变的数据（龙虎榜/热股历史/涨跌停池）：
 *    过去日期命中缓存零网络，当日/缺省日期拉网并写缓存。
 *
 * fetch lambda 由调用方提供，其内部已处理「禁用/失败 → null」；本类不做网络、不感知开关。
 * 所有 DB/Gson 操作吞异常（红线 #2）：缓存层故障不影响取数主流程。
 */
@Singleton
class FuyaoCacheStore @Inject constructor(
    private val dao: FuyaoCacheDao
) {
    private val gson = Gson()

    /** 读缓存（带原始实体，fetchedAt 供新鲜度判断）；损坏 payload 返回 null 不抛。 */
    suspend fun <T> loadEntry(key: String, typeOfT: Type): CachedEntry<T>? {
        val entity = runCatching { dao.get(key) }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        val value = runCatching {
            gson.fromJson<Any>(entity.payload, typeOfT) as T
        }.getOrNull() ?: return null
        return CachedEntry(value, entity.fetchedAt)
    }

    data class CachedEntry<T>(val value: T, val fetchedAt: Long)

    private suspend fun save(key: String, value: Any?) {
        runCatching {
            dao.upsert(FuyaoCacheEntity(key, gson.toJson(value), System.currentTimeMillis()))
        }
    }

    /** 语义①：拉网成功 → 与缓存按 [merge] 合并后持久化返回；失败/禁用 → 回退缓存。 */
    suspend fun <T> fetchFirstMerge(
        key: String,
        typeOfT: Type,
        merge: (cached: T?, fresh: T) -> T,
        fetch: suspend () -> T?
    ): T? {
        val fresh = fetch()
        if (fresh != null) {
            val cached = loadEntry<T>(key, typeOfT)?.value
            val merged = merge(cached, fresh)
            save(key, merged)
            return merged
        }
        return loadEntry<T>(key, typeOfT)?.value
    }

    /** 语义②：拉网成功 → 整体替换持久化；失败/禁用 → 回退缓存。 */
    suspend fun <T> fetchFirstReplace(
        key: String,
        typeOfT: Type,
        fetch: suspend () -> T?
    ): T? = fetchFirstMerge(key, typeOfT, { _, fresh -> fresh }, fetch)

    /**
     * 语义③：[isPastDate] 为真（过去日期的不可变数据）且缓存命中 → 直接返回零网络；
     * 否则拉网，成功持久化，失败回退缓存。
     */
    suspend fun <T> cacheFirstForDate(
        key: String,
        typeOfT: Type,
        isPastDate: Boolean,
        fetch: suspend () -> T?
    ): T? {
        if (isPastDate) {
            loadEntry<T>(key, typeOfT)?.let { return it.value }
        }
        val fresh = fetch() ?: return loadEntry<T>(key, typeOfT)?.value
        save(key, fresh)
        return fresh
    }
}

/** 缓存 payload 的泛型 Type（避免与 kotlin.typeOf 重名，data.repository 包内直接可用）。 */
inline fun <reified T> fuyaoCacheTypeOf(): Type = object : TypeToken<T>() {}.type
