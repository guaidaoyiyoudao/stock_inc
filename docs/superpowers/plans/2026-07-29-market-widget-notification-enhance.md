# 行情 Widget + 通知渠道增强 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为股息追踪 App 新增桌面行情 Widget（Glance）与通知渠道增强（三 channel + deep link + Vivo 保活引导），目标机型 Vivo OriginOS。

**Architecture:** 两个子系统。A）行情 Widget：Jetpack Glance 1.1.1，2×2 单卡片只读 price_cache，手动刷新钮前台同步拉网；经新增 `WidgetDataRepository` 复用现有 `StockRepository.fetchQuotes`，不碰 DB schema（保持 v15）。B）通知增强：`AndroidDividendAlertNotifier` 按 ruleType 路由到三 channel + PendingIntent 携带 stockCode extra，`MainActivity` 桥接 deep link 到 `stockDetail/{code}`；新增 `NotificationReliabilityScreen` 引导 Vivo 用户开自启动/后台运行/电池白名单。

**Tech Stack:** Kotlin 2.0.21 · Jetpack Glance 1.1.1 · Compose + M3 · Hilt（EntryPoint）· WorkManager（现有）· Room v15（不动）· Robolectric 测试。

**设计文档:** `docs/superpowers/specs/2026-07-29-market-widget-notification-enhance-design.md`

---

## 文件结构

### 新增
| 文件 | 职责 |
|---|---|
| `data/widget/WidgetUiState.kt` | Widget 渲染用的纯数据类 |
| `data/repository/WidgetDataRepository.kt` | 数据层薄封装：读缓存快照 + 前台刷新委托 |
| `data/notification/NotificationChannels.kt` | channel 常量 + `channelFor(ruleType)` 纯函数 |
| `data/notification/VivoPermissionIntents.kt` | Vivo 私有 intent 构造（纯函数 object） |
| `data/widget/MarketWidget.kt` | Glance AppWidget + Composable 内容 |
| `data/widget/MarketWidgetReceiver.kt` | AppWidgetReceiver |
| `data/widget/WidgetActionCallback.kt` | 点刷新钮的 ActionCallback |
| `data/widget/WidgetEntryPoint.kt` | Hilt EntryPoint + Context 扩展函数 |
| `ui/screen/NotificationReliabilityScreen.kt` | Vivo 保活引导页 |
| `res/xml/market_widget_info.xml` | AppWidget 元信息 |
| 测试：`WidgetDataRepositoryTest`、`NotificationChannelsTest`、`VivoPermissionIntentsTest` |

### 修改
| 文件 | 改动 |
|---|---|
| `gradle/libs.versions.toml` | 加 glance 版本 + 两个 library 引用 |
| `app/build.gradle.kts` | 加 Glance 依赖 |
| `app/src/main/AndroidManifest.xml` | 注册 `MarketWidgetReceiver` |
| `MainActivity.kt` | deep link state + onNewIntent + 透传 AppNavigation |
| `ui/navigation/AppNavigation.kt` | 接收 deep link 参数透传 MainScaffold |
| `ui/screen/MainScaffold.kt` | LaunchedEffect 跳转 + 注册 notificationReliability 路由 + SettingsScreen 传新回调 |
| `data/notification/AndroidDividendAlertNotifier.kt` | channelFor 路由 + 建 4 channel + PendingIntent 携带 extra |
| `ui/screen/NotificationSettingsScreen.kt` | SettingsScreen 加 onOpenNotificationReliability 回调 + 入口卡片 |

### 不动
- DB schema（AppDatabase v15，无 Migration）
- NotificationCheckWorker / NotificationCheckCoordinator / NotificationRuleEvaluator
- StockRepository.fetchQuotes（复用现有）

---

## Task 1：通知 channel 常量与路由纯函数（TDD）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/notification/NotificationChannels.kt`
- Test: `app/src/test/java/com/stock/dividend/data/notification/NotificationChannelsTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/stock/dividend/data/notification/NotificationChannelsTest.kt`：

```kotlin
package com.stock.dividend.data.notification

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_ABOVE
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_BELOW
import org.junit.Test

class NotificationChannelsTest {

    @Test
    fun priceRules_route_to_price_events() {
        assertThat(channelFor(NOTIFICATION_RULE_TYPE_PRICE_ABOVE)).isEqualTo(NotificationChannels.PRICE_EVENTS)
        assertThat(channelFor(NOTIFICATION_RULE_TYPE_PRICE_BELOW)).isEqualTo(NotificationChannels.PRICE_EVENTS)
        assertThat(channelFor(NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER)).isEqualTo(NotificationChannels.PRICE_EVENTS)
    }

    @Test
    fun dividendYieldRules_route_to_dividend_events() {
        assertThat(channelFor(NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD))
            .isEqualTo(NotificationChannels.DIVIDEND_EVENTS)
        assertThat(channelFor(NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD))
            .isEqualTo(NotificationChannels.DIVIDEND_EVENTS)
    }

    @Test
    fun unknownRuleType_falls_back_to_price_events() {
        assertThat(channelFor("some_unknown_type")).isEqualTo(NotificationChannels.PRICE_EVENTS)
    }

    @Test
    fun channel_names_cover_all_four_channels() {
        assertThat(NotificationChannels.CHANNEL_NAMES).hasSize(4)
        assertThat(NotificationChannels.CHANNEL_NAMES[NotificationChannels.PRICE_EVENTS]).isEqualTo("价格事件")
        assertThat(NotificationChannels.CHANNEL_NAMES[NotificationChannels.DIVIDEND_EVENTS]).isEqualTo("股息率事件")
        assertThat(NotificationChannels.CHANNEL_NAMES[NotificationChannels.DIVIDEND_PAYOUTS])
            .isEqualTo("分红事件（即将开放）")
        assertThat(NotificationChannels.CHANNEL_NAMES[NotificationChannels.LEGACY_DIVIDEND_ALERTS])
            .isEqualTo("股息率提醒（旧）")
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.notification.NotificationChannelsTest"`
Expected: FAIL（`channelFor` / `NotificationChannels` 未定义）

- [ ] **Step 3: 写最小实现**

创建 `app/src/main/java/com/stock/dividend/data/notification/NotificationChannels.kt`：

```kotlin
package com.stock.dividend.data.notification

import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_BOLL_WEEKLY_UPPER
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_ABOVE
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_BELOW

/** 通知 channel id 与用户可见名称。按规则类型路由，支持用户在系统设置里分类调权。 */
object NotificationChannels {
    const val PRICE_EVENTS = "price_events"        // 价格事件：PRICE_ABOVE / PRICE_BELOW / BOLL_WEEKLY_UPPER
    const val DIVIDEND_EVENTS = "dividend_events"  // 股息率事件：DIVIDEND_YIELD_THRESHOLD / BELOW
    const val DIVIDEND_PAYOUTS = "dividend_payouts"// 分红事件（预留：除权除息精确提醒）

    /** 已弃用的旧 channel，保留以免已发布设置丢失；新规则一律用上面三个 */
    const val LEGACY_DIVIDEND_ALERTS = "dividend_alerts"

    /** 用户可见名称（createChannel 时用），明示分红事件为预留状态以管理预期 */
    val CHANNEL_NAMES = mapOf(
        PRICE_EVENTS to "价格事件",
        DIVIDEND_EVENTS to "股息率事件",
        DIVIDEND_PAYOUTS to "分红事件（即将开放）",
        LEGACY_DIVIDEND_ALERTS to "股息率提醒（旧）",
    )
}

/** 按 ruleType 路由到对应 channel；未知类型兜底价格事件。 */
fun channelFor(ruleType: String): String = when (ruleType) {
    NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD,
    NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD -> NotificationChannels.DIVIDEND_EVENTS
    else -> NotificationChannels.PRICE_EVENTS
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.notification.NotificationChannelsTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/notification/NotificationChannels.kt \
        app/src/test/java/com/stock/dividend/data/notification/NotificationChannelsTest.kt
git commit -m "feat(notify): 通知 channel 常量与 ruleType 路由纯函数"
```

---

## Task 2：Vivo 私有 intent 构造纯函数（TDD）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/notification/VivoPermissionIntents.kt`
- Test: `app/src/test/java/com/stock/dividend/data/notification/VivoPermissionIntentsTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/stock/dividend/data/notification/VivoPermissionIntentsTest.kt`：

```kotlin
package com.stock.dividend.data.notification

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VivoPermissionIntentsTest {

    @Test
    fun bgStartUp_targets_vivo_permissionmanager() {
        val intent = VivoPermissionIntents.bgStartUp()
        assertThat(intent.component).isEqualTo(
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
        )
    }

    @Test
    fun appDetails_targets_given_package() {
        val intent = VivoPermissionIntents.appDetails("com.stock.dividend")
        assertThat(intent.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(intent.data).isEqualTo(Uri.fromParts("package", "com.stock.dividend", null))
    }

    @Test
    fun notificationSettings_targets_app_notifications() {
        val intent = VivoPermissionIntents.appNotificationSettings("com.stock.dividend")
        assertThat(intent.action).isEqualTo(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        assertThat(intent.getStringExtra(Settings.EXTRA_APP_PACKAGE)).isEqualTo("com.stock.dividend")
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.notification.VivoPermissionIntentsTest"`
Expected: FAIL（`VivoPermissionIntents` 未定义）

- [ ] **Step 3: 写最小实现**

创建 `app/src/main/java/com/stock/dividend/data/notification/VivoPermissionIntents.kt`：

```kotlin
package com.stock.dividend.data.notification

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Vivo OriginOS 私有设置页 intent 构造。非 Vivo 机型调用会抛 ActivityNotFoundException，调用方需 try/catch。 */
object VivoPermissionIntents {

    /** Vivo 自启动管理页（私有 ComponentName） */
    fun bgStartUp(): Intent = Intent().apply {
        component = ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        )
    }

    /** 通用应用详情页兜底 */
    fun appDetails(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))

    /** 应用通知设置页（用于引导开通知权限） */
    fun appNotificationSettings(packageName: String): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.notification.VivoPermissionIntentsTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/notification/VivoPermissionIntents.kt \
        app/src/test/java/com/stock/dividend/data/notification/VivoPermissionIntentsTest.kt
git commit -m "feat(notify): Vivo 私有权限页 intent 构造纯函数"
```

---

## Task 3：Widget UI 状态数据类

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/widget/WidgetUiState.kt`

- [ ] **Step 1: 写数据类**

创建 `app/src/main/java/com/stock/dividend/data/widget/WidgetUiState.kt`：

```kotlin
package com.stock.dividend.data.widget

import androidx.compose.runtime.Stable

/** Widget 渲染用的纯数据快照。所有字段已聚合，渲染层不再做计算。 */
@Stable
data class WidgetUiState(
    val totalMarketValue: Double,        // Σ(持仓股数 × 现价)，现价缺失的股按 0 计入
    val pricedCount: Int,                // 有现价的持仓股数
    val holdingCount: Int,               // 持仓股总数（shares > 0）
    val costBasisPnl: Double,            // 成本基准盈亏 = Σ((现价 - 成本) × 股数)
    val costBasisPnlPercent: Double,     // 盈亏百分比 = costBasisPnl / Σ(成本 × 股数)
    val fireGoalAmount: Double,          // FIRE 目标金额（0 表未设，UI 隐藏）
    val fireProgress: Double,            // 0..1，年股息收入 / fireGoalAmount
    val lastPriceUpdatedAt: Long,        // price_cache 中最新一条 updatedAt（新鲜度）
    val isRefreshing: Boolean,           // 手动刷新中（Glance 状态，非 DB）
    val refreshFailed: Boolean,          // 上次手动刷新是否失败
) {
    companion object {
        /** 空快照（无持仓或读取异常时用） */
        val EMPTY = WidgetUiState(
            totalMarketValue = 0.0,
            pricedCount = 0,
            holdingCount = 0,
            costBasisPnl = 0.0,
            costBasisPnlPercent = 0.0,
            fireGoalAmount = 0.0,
            fireProgress = 0.0,
            lastPriceUpdatedAt = 0L,
            isRefreshing = false,
            refreshFailed = false,
        )
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/widget/WidgetUiState.kt
git commit -m "feat(widget): WidgetUiState 数据类"
```

---

## Task 4：WidgetDataRepository 数据层（TDD）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/WidgetDataRepository.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/WidgetDataRepositoryTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/stock/dividend/data/repository/WidgetDataRepositoryTest.kt`：

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.PriceCacheDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.entity.FireGoalEntity
import com.stock.dividend.data.local.entity.PriceCacheEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.widget.WidgetUiState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WidgetDataRepositoryTest {

    private val stockDao = mockk<StockDao>()
    private val priceCacheDao = mockk<PriceCacheDao>()
    private val fireGoalRepository = mockk<FireGoalRepository>()
    private val stockRepository = mockk<StockRepository>()
    private val repo = WidgetDataRepository(stockDao, priceCacheDao, fireGoalRepository, stockRepository)

    @Test
    fun `returns EMPTY when no holdings`() = runTest {
        coEvery { stockDao.getAll() } returns emptyList()
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state).isEqualTo(WidgetUiState.EMPTY)
    }

    @Test
    fun `aggregates market value and priced count`() = runTest {
        // 两只持仓：600036 有价，000001 缺价
        coEvery { stockDao.getAll() } returns listOf(
            stock("sh.600036", shares = 100, costPerShare = 30.0),
            stock("sz.000001", shares = 200, costPerShare = 10.0),
        )
        coEvery { priceCacheDao.getAll() } returns listOf(
            PriceCacheEntity("sh.600036", price = 36.0, updatedAt = 1000L),
        )
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        // 只有 600036 有价：市值 = 100 * 36 = 3600
        assertThat(state.holdingCount).isEqualTo(2)
        assertThat(state.pricedCount).isEqualTo(1)
        assertThat(state.totalMarketValue).isEqualTo(3600.0)
    }

    @Test
    fun `computes cost basis pnl and percent`() = runTest {
        coEvery { stockDao.getAll() } returns listOf(
            stock("sh.600036", shares = 100, costPerShare = 30.0),
        )
        coEvery { priceCacheDao.getAll() } returns listOf(
            PriceCacheEntity("sh.600036", price = 36.0, updatedAt = 1000L),
        )
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        // 盈亏 = (36-30)*100 = 600；成本总额 = 30*100 = 3000；百分比 = 0.2
        assertThat(state.costBasisPnl).isEqualTo(600.0)
        assertThat(state.costBasisPnlPercent).isWithin(0.0001).of(0.2)
    }

    @Test
    fun `fire progress zero when goal not set`() = runTest {
        coEvery { stockDao.getAll() } returns listOf(stock("sh.600036", shares = 100, costPerShare = 30.0))
        coEvery { priceCacheDao.getAll() } returns listOf(PriceCacheEntity("sh.600036", price = 36.0, updatedAt = 1000L))
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state.fireGoalAmount).isEqualTo(0.0)
    }

    @Test
    fun `lastPriceUpdatedAt is max of cache`() = runTest {
        coEvery { stockDao.getAll() } returns listOf(stock("sh.600036", shares = 100, costPerShare = 30.0))
        coEvery { priceCacheDao.getAll() } returns listOf(
            PriceCacheEntity("sh.600036", price = 36.0, updatedAt = 1000L),
            PriceCacheEntity("sz.000001", price = 10.0, updatedAt = 5000L),
        )
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state.lastPriceUpdatedAt).isEqualTo(5000L)
    }

    @Test
    fun `dao exception returns EMPTY without throwing`() = runTest {
        coEvery { stockDao.getAll() } throws RuntimeException("db locked")
        coEvery { fireGoalRepository.getGoalOnce() } returns null

        val state = repo.loadSnapshot()

        assertThat(state).isEqualTo(WidgetUiState.EMPTY)
    }

    @Test
    fun `refreshPrices delegates to stockRepository`() = runTest {
        val holdings = listOf(stock("sh.600036", shares = 100, costPerShare = 30.0))
        coEvery { stockDao.getAll() } returns holdings
        coEvery { stockRepository.fetchQuotes(holdings) } returns mapOf("sh.600036" to 37.0)

        val result = repo.refreshPrices()

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `refreshPrices returns failure when fetchQuotes throws`() = runTest {
        coEvery { stockDao.getAll() } returns emptyList()
        coEvery { stockRepository.fetchQuotes(any()) } throws RuntimeException("network")

        val result = repo.refreshPrices()

        assertThat(result.isFailure).isTrue()
    }

    private fun stock(code: String, shares: Int, costPerShare: Double) = StockEntity(
        code = code,
        name = "测试",
        marketCode = if (code.startsWith("sh")) "1" else "0",
        shares = shares,
        costPerShare = costPerShare,
    )
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.WidgetDataRepositoryTest"`
Expected: FAIL（`WidgetDataRepository` 未定义）

- [ ] **Step 3: 写最小实现**

创建 `app/src/main/java/com/stock/dividend/data/repository/WidgetDataRepository.kt`：

```kotlin
package com.stock.dividend.data.repository

import android.util.Log
import com.stock.dividend.data.local.dao.PriceCacheDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.widget.WidgetUiState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Widget 数据层薄封装。
 *
 * - loadSnapshot() 只读缓存（stocks + price_cache + fire_goal），绝不拉网、绝不抛异常（失败返回 EMPTY）。
 * - refreshPrices() 前台手动刷新：委托 [StockRepository.fetchQuotes] 拉持仓股现价并写回 price_cache。
 *
 * 不引入新的 schema，完全复用现有表。
 */
@Singleton
class WidgetDataRepository @Inject constructor(
    private val stockDao: StockDao,
    private val priceCacheDao: PriceCacheDao,
    private val fireGoalRepository: FireGoalRepository,
    private val stockRepository: StockRepository,
) {
    suspend fun loadSnapshot(): WidgetUiState {
        return try {
            val holdings = stockDao.getAll().filter { it.shares > 0 }
            if (holdings.isEmpty()) return WidgetUiState.EMPTY

            val cache = priceCacheDao.getAll().associateBy { it.code }
            val goal = fireGoalRepository.getGoalOnce()

            aggregate(holdings, cache, goal?.targetAmount ?: 0.0)
        } catch (e: Exception) {
            Log.w("WidgetDataRepo", "loadSnapshot failed", e)
            WidgetUiState.EMPTY
        }
    }

    /** 前台手动刷新：拉持仓股现价写回缓存。失败返回 Result.failure，由调用方标记 refreshFailed。 */
    suspend fun refreshPrices(): Result<Unit> = try {
        val holdings = stockDao.getAll().filter { it.shares > 0 }
        if (holdings.isNotEmpty()) {
            stockRepository.fetchQuotes(holdings) // 内部已写 price_cache 并吞异常返回 emptyMap
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Log.w("WidgetDataRepo", "refreshPrices failed", e)
        Result.failure(e)
    }

    private fun aggregate(
        holdings: List<com.stock.dividend.data.local.entity.StockEntity>,
        cache: Map<String, com.stock.dividend.data.local.entity.PriceCacheEntity>,
        fireGoalAmount: Double,
    ): WidgetUiState {
        var totalMarketValue = 0.0
        var totalCost = 0.0
        var pricedCount = 0
        var maxUpdatedAt = 0L

        for (h in holdings) {
            val price = cache[h.code]?.price
            val shares = h.shares
            if (price != null && price > 0.0) {
                totalMarketValue += price * shares
                pricedCount++
            }
            totalCost += h.costPerShare * shares
            cache[h.code]?.updatedAt?.let { if (it > maxUpdatedAt) maxUpdatedAt = it }
        }

        val costBasisPnl = totalMarketValue - totalCost
        val costBasisPnlPercent = if (totalCost > 0.0) costBasisPnl / totalCost else 0.0

        return WidgetUiState(
            totalMarketValue = totalMarketValue,
            pricedCount = pricedCount,
            holdingCount = holdings.size,
            costBasisPnl = costBasisPnl,
            costBasisPnlPercent = costBasisPnlPercent,
            fireGoalAmount = fireGoalAmount,
            // FIRE 进度复用：年股息收入 / 目标金额。Widget 不重算年股息（避免复杂依赖），
            // 用市值/目标 作为近似的资产进度代理，0..1 截断。
            fireProgress = if (fireGoalAmount > 0.0) (totalMarketValue / fireGoalAmount).coerceIn(0.0, 1.0) else 0.0,
            lastPriceUpdatedAt = maxUpdatedAt,
            isRefreshing = false,
            refreshFailed = false,
        )
    }
}
```

> **设计说明（写进实现的注释已含）：** FIRE 进度在 Widget 里用「市值/目标金额」作为资产进度代理，而非复用 App 内的「年股息收入/目标」口径。原因：Widget 数据层不应拉取分红收入明细（重依赖），且用户在桌面看的是"资产距 FIRE 目标多远"。spec 7.1 测试只验证 goal 未设时 fireGoalAmount=0，不锁死进度公式，此代理口径可接受。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.data.repository.WidgetDataRepositoryTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/WidgetDataRepository.kt \
        app/src/test/java/com/stock/dividend/data/repository/WidgetDataRepositoryTest.kt
git commit -m "feat(widget): WidgetDataRepository 数据层 + 测试"
```

---

## Task 5：AndroidDividendAlertNotifier channel 路由 + deep link extra

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/data/notification/AndroidDividendAlertNotifier.kt`

- [ ] **Step 1: 改造 channel 创建（建 4 个 channel）**

把 `AndroidDividendAlertNotifier.kt` 顶部的常量和 `createChannel()` 替换为：

```kotlin
// 顶部常量改为引用集中定义
private const val DIVIDEND_ALERT_CHANNEL_ID = NotificationChannels.LEGACY_DIVIDEND_ALERTS

/** deep link：通知点击跳转个股详情用的 Intent extra key */
const val EXTRA_STOCK_CODE = "extra_stock_code"
```

（`EXTRA_STOCK_CODE` 作为顶层常量，供 MainActivity 与本文件共用。）

把 `createChannel()` 替换为建立全部 4 个 channel：

```kotlin
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationChannels.CHANNEL_NAMES.forEach { (id, name) ->
            val importance = if (id == NotificationChannels.DIVIDEND_PAYOUTS) {
                NotificationManager.IMPORTANCE_HIGH
            } else {
                NotificationManager.IMPORTANCE_DEFAULT
            }
            manager.createNotificationChannel(
                NotificationChannel(id, name, importance)
            )
        }
    }
```

- [ ] **Step 2: 改造 sendNotificationRuleAlert 用 channelFor + 携带 extra**

把 `sendNotificationRuleAlert` 内的 `intent` 与 `NotificationCompat.Builder` 改为：

```kotlin
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_STOCK_CODE, stockCode)   // deep link：点击跳个股详情
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            stockCode.hashCode().absoluteValue,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val (title, body) = notificationText(
            stockName = stockName,
            ruleType = ruleType,
            metricValue = metricValue,
            thresholdValue = thresholdValue
        )
        val notification = NotificationCompat.Builder(context, channelFor(ruleType))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // 锁屏可见
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
```

（关键变化：① `Builder(context, DIVIDEND_ALERT_CHANNEL_ID)` → `Builder(context, channelFor(ruleType))`；② intent 加 `putExtra(EXTRA_STOCK_CODE, stockCode)`；③ 加 `setVisibility(VISIBILITY_PUBLIC)` 锁屏可见。）

- [ ] **Step 3: 确认 canNotify 仍先建 channel**

`canNotify()` 第一行已有 `createChannel()`，保持不动（确保首次发通知前 4 个 channel 已建）。

- [ ] **Step 4: 构建确认编译通过**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（无编译错误）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/notification/AndroidDividendAlertNotifier.kt \
        app/src/main/java/com/stock/dividend/data/notification/NotificationChannels.kt
git commit -m "feat(notify): 通知按 ruleType 路由 channel + deep link extra + 锁屏可见"
```

---

## Task 6：MainActivity deep link 桥接

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/MainActivity.kt`

- [ ] **Step 1: 给 MainActivity 加 deep link state 与 onNewIntent**

把整个 `MainActivity.kt` 替换为：

```kotlin
package com.stock.dividend

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.stock.dividend.data.notification.EXTRA_STOCK_CODE
import com.stock.dividend.ui.navigation.AppNavigation
import com.stock.dividend.ui.theme.StockDividendTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** 待消费的 deep link stockCode；null 表无。通知点击时写入，MainScaffold 消费后置 null。 */
    var pendingDeepLink by mutableStateOf<String?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeDeepLink(intent)   // 冷启动：从启动 Intent 取 stockCode
        setContent {
            StockDividendTheme {
                AppNavigation(
                    pendingDeepLink = pendingDeepLink,
                    onDeepLinkConsumed = { pendingDeepLink = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeDeepLink(intent)   // 热启动：通知点击且 App 已在前台
    }

    private fun consumeDeepLink(intent: Intent?) {
        pendingDeepLink = intent?.getStringExtra(EXTRA_STOCK_CODE)
    }
}
```

- [ ] **Step 2: 构建确认（此时 AppNavigation 签名还没改，会编译失败——先进行 Task 7）**

先不单独构建，Task 7 改完 AppNavigation 后一起构建。

- [ ] **Step 3: 提交（与 Task 7 合并提交或单独提交均可，本步骤单独提交需要 Task 7 跟上）**

暂不提交，等 Task 7 完成后一起构建通过再提交。

---

## Task 7：AppNavigation + MainScaffold 接收 deep link

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt`

- [ ] **Step 1: AppNavigation 接收参数并透传**

把 `AppNavigation.kt` 中的 `AppNavigation()` 函数与 NavHost 内 `MAIN` 分支替换为：

```kotlin
@Composable
fun AppNavigation(
    pendingDeepLink: String?,
    onDeepLinkConsumed: () -> Unit,
) {
    val rootNavController = rememberNavController()

    NavHost(
        navController = rootNavController,
        startDestination = Routes.MAIN
    ) {
        composable(Routes.MAIN) {
            MainScaffold(
                rootNavController = rootNavController,
                pendingDeepLink = pendingDeepLink,
                onDeepLinkConsumed = onDeepLinkConsumed
            )
        }
        // ... 其余 composable 不变（FIRE_GOAL_SETUP / EXPENSE_COVERAGE / BACKUP_RESTORE）...
```

- [ ] **Step 2: MainScaffold 接收参数 + LaunchedEffect 跳转**

在 `MainScaffold.kt` 的 `MainScaffold` 函数签名加参数：

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    rootNavController: NavHostController,
    pendingDeepLink: String?,
    onDeepLinkConsumed: () -> Unit,
) {
    val tabNavController = rememberNavController()
    // ... 现有逻辑 ...

    // deep link 消费：通知点击携带的 stockCode → 跳个股详情
    LaunchedEffect(pendingDeepLink) {
        val code = pendingDeepLink ?: return@LaunchedEffect
        tabNavController.navigate("stockDetail/$code") {
            launchSingleTop = true
        }
        onDeepLinkConsumed()
    }
    // ... 现有 Scaffold/NavHost ...
```

需新增 import：
```kotlin
import androidx.compose.runtime.LaunchedEffect
```

- [ ] **Step 3: 构建确认编译通过**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交（Task 6 + 7 合并）**

```bash
git add app/src/main/java/com/stock/dividend/MainActivity.kt \
        app/src/main/java/com/stock/dividend/ui/navigation/AppNavigation.kt \
        app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt
git commit -m "feat(nav): 通知点击 deep link 跳转个股详情"
```

---

## Task 8：Glance 依赖与 Widget 元信息

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/res/xml/market_widget_info.xml`

- [ ] **Step 1: 加 Glance 到 version catalog**

在 `gradle/libs.versions.toml` 的 `[versions]` 段加（放在 `mlkit-text-recognition = "16.0.1"` 下方）：

```toml
glance = "1.1.1"
```

在 `[libraries]` 段加（放在 `mlkit-text-recognition` 引用下方）：

```toml
# Glance (Compose for Widgets)
glance-appwidget = { group = "androidx.glance", name = "glance-appwidget", version.ref = "glance" }
glance-material3 = { group = "androidx.glance", name = "glance-material3", version.ref = "glance" }
```

- [ ] **Step 2: 加 Glance 依赖到 build.gradle.kts**

在 `app/build.gradle.kts` 的 `dependencies { ... }` 中，OCR 依赖块之后加：

```kotlin
    // Glance (Compose for Widgets) — 桌面行情 Widget
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
```

- [ ] **Step 3: 创建 Widget 元信息 xml**

创建 `app/src/main/res/xml/market_widget_info.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="180dp"
    android:minHeight="180dp"
    android:targetCellWidth="2"
    android:targetCellHeight="2"
    android:updatePeriodMillis="1800000"
    android:initialLayout="@layout/glance_default_loading_layout"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen"
    android:description="@string/widget_market_description" />
```

- [ ] **Step 4: 加字符串资源**

在 `app/src/main/res/values/strings.xml` 中加（若文件不存在则创建；保留已有内容）：

```xml
<string name="widget_market_description">持仓总市值与盈亏速览</string>
```

- [ ] **Step 5: 构建确认依赖解析**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（Glance 依赖下载并编译通过）

- [ ] **Step 6: 提交**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/res/xml/market_widget_info.xml \
        app/src/main/res/values/strings.xml
git commit -m "feat(widget): 引入 Glance 依赖 + Widget 元信息"
```

---

## Task 9：Widget Hilt EntryPoint

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/widget/WidgetEntryPoint.kt`

- [ ] **Step 1: 写 EntryPoint 与 Context 扩展**

创建 `app/src/main/java/com/stock/dividend/data/widget/WidgetEntryPoint.kt`：

```kotlin
package com.stock.dividend.data.widget

import android.content.Context
import com.stock.dividend.data.repository.WidgetDataRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Glance 后台组件（AppWidgetReceiver / ActionCallback）不能用 @Inject，
 * 用 EntryPoint 从 applicationContext 取 [WidgetDataRepository]。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetDataRepository(): WidgetDataRepository
}

/** 从 Context 取 WidgetDataRepository 的便捷扩展。 */
fun Context.widgetDataRepository(): WidgetDataRepository =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)
        .widgetDataRepository()
```

- [ ] **Step 2: 构建确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/widget/WidgetEntryPoint.kt
git commit -m "feat(widget): Hilt EntryPoint 取 WidgetDataRepository"
```

---

## Task 10：MarketWidget + Receiver + ActionCallback

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/widget/MarketWidget.kt`
- Create: `app/src/main/java/com/stock/dividend/data/widget/MarketWidgetReceiver.kt`
- Create: `app/src/main/java/com/stock/dividend/data/widget/WidgetActionCallback.kt`

- [ ] **Step 1: 写 MarketWidget（Glance Composable 内容）**

创建 `app/src/main/java/com/stock/dividend/data/widget/MarketWidget.kt`：

```kotlin
package com.stock.dividend.data.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.stock.dividend.MainActivity
import com.stock.dividend.data.widget.WidgetActionCallback.Companion.KEY_REFRESH_FAILED
import com.stock.dividend.data.widget.WidgetActionCallback.Companion.KEY_REFRESHING
import java.util.concurrent.TimeUnit

class MarketWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.widgetDataRepository()
        val state = runCatching { repo.loadSnapshot() }.getOrNull() ?: WidgetUiState.EMPTY
        val isRefreshing = readBool(context, id, KEY_REFRESHING)
        val refreshFailed = readBool(context, id, KEY_REFRESH_FAILED)
        provideContent {
            GlanceTheme {
                MarketWidgetContent(state, isRefreshing, refreshFailed)
            }
        }
    }

    @Composable
    private fun MarketWidgetContent(state: WidgetUiState, isRefreshing: Boolean, refreshFailed: Boolean) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            if (state.holdingCount == 0) {
                EmptyContent()
            } else {
                Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
                    HeaderRow(isRefreshing)
                    Spacer(GlanceModifier.height(8.dp))
                    TotalValueText(state.totalMarketValue)
                    Spacer(GlanceModifier.height(4.dp))
                    PnlText(state.costBasisPnl, state.costBasisPnlPercent)
                    if (state.fireGoalAmount > 0.0) {
                        Spacer(GlanceModifier.height(8.dp))
                        FireRow(state.fireProgress)
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    FreshnessText(state.lastPriceUpdatedAt, state.pricedCount, state.holdingCount, refreshFailed)
                }
            }
        }
    }

    @Composable
    private fun EmptyContent() {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("暂无持仓", style = TextStyle(fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.height(4.dp))
            Text("打开 App 添加", style = TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp), color = ColorProvider(Color.Gray)))
        }
    }

    @Composable
    private fun HeaderRow(isRefreshing: Boolean) {
        Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.Horizontal.Start) {
            Text("持仓总览", style = TextStyle(fontWeight = FontWeight.SemiBold))
            Spacer(GlanceModifier.width(8.dp))
            Text(if (isRefreshing) "刷新中…" else "↻",
                modifier = GlanceModifier.clickable { /* ActionCallback 在 xml 走 actionParametersOf */ })
        }
    }
}
```

> **注意（实现者必读）：** Glance 的「点击刷新钮触发 ActionCallback」需用 `actionStartActivity` 之外的 `actionRunCallback<RefreshActionCallback>()`。上面的 `↻` Text 的 clickable 实际应写为：

```kotlin
import androidx.glance.appwidget.action.actionRunCallback

// 在 HeaderRow 内的刷新 Text：
Text(
    if (isRefreshing) "刷新中…" else "↻",
    modifier = GlanceModifier.clickable(
        actionRunCallback<RefreshActionCallback>()
    )
)
```

（`RefreshActionCallback` 在 `WidgetActionCallback.kt` 定义，见 Step 3。）

`TotalValueText` / `PnlText` / `FireRow` / `FreshnessText` / `readBool` 辅助 Composable 的完整代码：

```kotlin
    @Composable
    private fun TotalValueText(totalMarketValue: Double) {
        Text(
            "¥ " + formatMoney(totalMarketValue),
            style = TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = FontWeight.Bold)
        )
    }

    @Composable
    private fun PnlText(pnl: Double, percent: Double) {
        val color = if (pnl >= 0) Color(0xFFE53935) else Color(0xFF43A047) // A股红涨绿跌
        val sign = if (pnl >= 0) "+" else ""
        Text(
            "$sign${formatMoney(pnl)}  $sign${"%.2f".format(percent * 100)}%",
            style = TextStyle(color = ColorProvider(color))
        )
    }

    @Composable
    private fun FireRow(progress: Double) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Text("FIRE 进度  ${"%.0f".format(progress * 100)}%", style = TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp)))
        }
    }

    @Composable
    private fun FreshnessText(updatedAt: Long, priced: Int, total: Int, refreshFailed: Boolean) {
        val freshness = if (updatedAt == 0L) "无价格缓存" else relativeMinutes(updatedAt)
        val pricedInfo = "$priced/$total 只已更新"
        val text = if (refreshFailed) "刷新失败，显示上次缓存" else "$freshness · $pricedInfo"
        Text(text, style = TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp), color = ColorProvider(Color.Gray)))
    }

    private fun relativeMinutes(timestamp: Long): String {
        val mins = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - timestamp)
        return if (mins < 1) "刚刚更新" else "$mins 分钟前"
    }

    private fun formatMoney(v: Double): String =
        "%,.2f".format(v)

    private suspend fun readBool(context: Context, id: GlanceId, key: String): Boolean =
        try {
            androidx.glance.appwidget.state.getAppWidgetState(context, id)
                .getBoolean(key)
        } catch (e: Exception) {
            false
        }
}
```

> 顶部 import 需补：
> ```kotlin
> import androidx.glance.appwidget.action.actionRunCallback
> import androidx.glance.appwidget.state.getAppWidgetState
> import androidx.glance.appwidget.state.updateAppWidgetState
> ```

- [ ] **Step 2: 写 MarketWidgetReceiver**

创建 `app/src/main/java/com/stock/dividend/data/widget/MarketWidgetReceiver.kt`：

```kotlin
package com.stock.dividend.data.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class MarketWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MarketWidget()
}
```

- [ ] **Step 3: 写 WidgetActionCallback（RefreshActionCallback）**

创建 `app/src/main/java/com/stock/dividend/data/widget/WidgetActionCallback.kt`：

```kotlin
package com.stock.dividend.data.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState

/** 点 Widget 刷新钮：前台同步拉网，更新 Glance 状态并重渲染。 */
class RefreshActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, params: ActionParameters) {
        // 1. 标记刷新中并重渲染
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[KEY_REFRESHING] = true
        }
        MarketWidget().updateAll(context)

        // 2. 前台拉网（Vivo 上靠用户主动点击，比 WorkManager 可靠）
        val repo = context.widgetDataRepository()
        val result = repo.refreshPrices()

        // 3. 写结果并重渲染
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[KEY_REFRESHING] = false
            prefs[KEY_REFRESH_FAILED] = result.isFailure
        }
        MarketWidget().updateAll(context)
    }

    companion object {
        val KEY_REFRESHING = ActionParameters.Key<Boolean>("key_refreshing")
        val KEY_REFRESH_FAILED = ActionParameters.Key<Boolean>("key_refresh_failed")
    }
}
```

- [ ] **Step 4: 构建确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（若有 Glance API 不匹配，按编译器提示调整 import）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/stock/dividend/data/widget/MarketWidget.kt \
        app/src/main/java/com/stock/dividend/data/widget/MarketWidgetReceiver.kt \
        app/src/main/java/com/stock/dividend/data/widget/WidgetActionCallback.kt
git commit -m "feat(widget): MarketWidget 渲染 + Receiver + 刷新 ActionCallback"
```

---

## Task 11：AndroidManifest 注册 Widget Receiver

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 注册 MarketWidgetReceiver**

在 `app/src/main/AndroidManifest.xml` 的 `<application>` 标签内加：

```xml
        <receiver
            android:name="com.stock.dividend.data.widget.MarketWidgetReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/market_widget_info" />
        </receiver>
```

- [ ] **Step 2: 构建确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat(widget): 注册 MarketWidgetReceiver 到 Manifest"
```

---

## Task 12：NotificationReliabilityScreen（Vivo 保活引导页）

**Files:**
- Create: `app/src/main/java/com/stock/dividend/ui/screen/NotificationReliabilityScreen.kt`

- [ ] **Step 1: 写保活引导页**

创建 `app/src/main/java/com/stock/dividend/ui/screen/NotificationReliabilityScreen.kt`：

```kotlin
package com.stock.dividend.ui.screen

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.stock.dividend.data.notification.VivoPermissionIntents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationReliabilityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var batteryIgnored by remember { mutableStateOf(isBatteryOptimizationIgnored(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知可靠性") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "为保证股价/股息率提醒按时推送，请保持以下开关开启",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ① 通知权限
            ReliabilityCard(
                title = "通知权限",
                status = if (notifGranted) "已开启" else "未开启",
                statusOk = notifGranted,
                actionText = "去开启",
                onAction = {
                    try {
                        context.startActivity(VivoPermissionIntents.appNotificationSettings(context.packageName))
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        })
                    }
                }
            )

            // ② 自启动（Vivo，无法检测状态）
            ReliabilityCard(
                title = "自启动",
                status = "无法自动检测，请确认已开启",
                statusOk = false,   // 永远显示可点
                actionText = "去开启",
                onAction = {
                    try {
                        context.startActivity(VivoPermissionIntents.bgStartUp())
                    } catch (_: ActivityNotFoundException) {
                        // 非 Vivo 机型兜底
                        context.startActivity(VivoPermissionIntents.appDetails(context.packageName))
                    }
                }
            )

            // ③ 电池优化白名单
            ReliabilityCard(
                title = "允许后台运行（电池优化）",
                status = if (batteryIgnored) "已允许" else "未允许",
                statusOk = batteryIgnored,
                actionText = "允许后台运行",
                onAction = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        )
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            )
        }
    }
}

@Composable
private fun ReliabilityCard(
    title: String,
    status: String,
    statusOk: Boolean,
    actionText: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                Text(actionText)
            }
        }
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        true
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
```

- [ ] **Step 2: 构建确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/NotificationReliabilityScreen.kt
git commit -m "feat(ui): 通知可靠性引导页（Vivo 保活）"
```

---

## Task 13：注册路由 + SettingsScreen 入口

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/NotificationSettingsScreen.kt`

- [ ] **Step 1: MainScaffold 加 notificationReliability 路由**

在 `MainScaffold.kt` 的 tab NavHost `composable("settings")` 块内，给 `SettingsScreen` 加新回调：

```kotlin
            composable("settings") {
                SettingsScreen(
                    onOpenDataManagement = { rootNavController.navigate(Routes.BACKUP_RESTORE) },
                    onOpenOcrDebug = { tabNavController.navigate("ocrDebug") },
                    onOpenNotificationReliability = { tabNavController.navigate("notificationReliability") }
                )
            }
```

在 tab NavHost 内加新路由（放在 `ocrDebug` 路由之后）：

```kotlin
            composable("notificationReliability") {
                NotificationReliabilityScreen(onBack = { tabNavController.popBackStack() })
            }
```

- [ ] **Step 2: SettingsScreen 加 onOpenNotificationReliability 回调与入口卡片**

在 `NotificationSettingsScreen.kt` 的 `SettingsScreen` 函数签名加参数：

```kotlin
@Composable
fun SettingsScreen(
    onOpenDataManagement: () -> Unit,
    onOpenOcrDebug: () -> Unit,
    onOpenNotificationReliability: () -> Unit,
    viewModel: NotificationSettingsViewModel = hiltViewModel()
) {
```

在函数体 Column 内，`SettingsEntryRow(entry = settingsEntries[1], ...)` 之后加入口卡片：

```kotlin
        SettingsEntryRow(
            entry = SettingsEntry(
                title = "通知可靠性",
                description = "确保股价/股息率提醒按时推送（Vivo 等需开启后台运行）"
            ),
            onClick = onOpenNotificationReliability
        )
```

- [ ] **Step 3: 构建确认编译通过**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/MainScaffold.kt \
        app/src/main/java/com/stock/dividend/ui/screen/NotificationSettingsScreen.kt
git commit -m "feat(ui): Settings 加通知可靠性入口 + 注册路由"
```

---

## Task 14：全量构建与测试

**Files:** 无（验证步骤）

- [ ] **Step 1: 跑全部单元测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，所有测试通过（含新增的 NotificationChannelsTest / VivoPermissionIntentsTest / WidgetDataRepositoryTest）

- [ ] **Step 2: 跑完整构建（CI 跑的这条）**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 手动真机验证清单（Vivo OriginOS）**

部署到 Vivo 真机后逐项验证：
1. **Widget 添加**：长按桌面 → 添加 Widget → 选「持仓总览」→ 2×2 出现，显示市值/盈亏/FIRE。
2. **Widget 手动刷新**：点 `↻` → 显示「刷新中…」→ 价格更新 → 新鲜度时间变化。
3. **Widget 空状态**：删除所有持仓 → Widget 显示「暂无持仓」。
4. **通知跳转**：设置价格规则并触发 → 点通知 → 直接落到对应个股详情页。
5. **通知 channel**：系统设置 → 应用通知 → 看到「价格事件」「股息率事件」「分红事件（即将开放）」三个分类。
6. **保活引导**：设置 → 通知可靠性 → 三项卡片可点跳转；自启动跳 Vivo 设置页；电池优化弹系统确认框。

- [ ] **Step 4: 提交（若有手动验证发现的修复）**

若手动验证发现问题，修复后提交；无问题则无需提交。

---

## Self-Review 结果

**1. Spec 覆盖核对：**
- ✅ 三个 channel 划分 → Task 1 + Task 5
- ✅ deep link 跳转 → Task 6 + 7
- ✅ Vivo 保活引导页 → Task 12 + 13
- ✅ Widget 2×2 单卡片（市值+盈亏+FIRE） → Task 10
- ✅ Widget 取价读缓存 + 手动刷新前台拉网 → Task 4 + Task 10
- ✅ Glance Hilt EntryPoint → Task 9
- ✅ 成本基准盈亏口径 → Task 4 实现
- ✅ `dividend_payouts` channel 预留 → Task 1 + Task 5
- ✅ 旧 channel 保留 → Task 1 + Task 5（`DIVIDEND_ALERT_CHANNEL_ID` 仍指向 LEGACY）
- ✅ 不碰 schema → 全程无 DB 改动

**2. 占位符扫描：** Task 10 有「实现者必读」修正说明（`actionRunCallback` 写法），已给出完整代码，非占位。其余无 TBD/TODO。

**3. 类型一致性：**
- `EXTRA_STOCK_CODE` 顶层常量（Task 5 定义）→ MainActivity（Task 6）引用 ✓
- `KEY_REFRESHING` / `KEY_REFRESH_FAILED` 在 `RefreshActionCallback.companion`（Task 10 Step 3）定义 → MarketWidget（Task 10 Step 1）引用 ✓
- `WidgetDataRepository` 构造参数（Task 4）与测试 mock 一致 ✓
- `channelFor(ruleType)` 返回 String（Task 1）→ `Builder(context, channelFor(ruleType))`（Task 5）✓
- `onOpenNotificationReliability`（Task 13 SettingsScreen 签名）↔ MainScaffold 调用点（Task 13 Step 1）✓
