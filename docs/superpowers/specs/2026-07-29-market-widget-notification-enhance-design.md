# 行情 Widget + 通知渠道增强 — 设计文档

**日期:** 2026-07-29
**状态:** Draft (待用户审核)
**作者:** brainstorming skill
**目标机型:** Vivo OriginOS（最新版本），兼顾通用 Android

---

## 1. 背景与目标

### 问题
当前 App 的信息获取入口**全部在 App 内**：用户必须打开 App 才能看持仓市值、股息率，才能收到价格/股息率提醒。两个空白：

1. **无桌面 Widget** —— 持仓总资产、FIRE 进度无速览入口，每次都要冷启动 App。
2. **通知触达薄弱** ——
   - 通知点击只落到 App 首页，**点不到个股**（`AndroidDividendAlertNotifier` 的 `PendingIntent` 不带 `stockCode`）。
   - 只有一个 channel「股息率提醒」，价格/股息率/分红事件**混在一起**，用户无法在系统设置里分类调权。
   - （Vivo 特有）**WorkManager 周期检查可能根本不触发**：OriginOS 的后台冻结/熄屏清理会杀进程，导致 `NotificationCheckWorker` 的 PeriodicWorkRequest 延迟或失效，除非用户手动开启「自启动 + 允许后台运行」。现状 App 无任何引导。

### 目标
1. **行情 Widget**（子系统 A）：桌面 2×2 单卡片，展示持仓总市值、相对成本的盈亏、FIRE 进度；默认读价格缓存，可手动触发一次前台拉取。
2. **通知渠道增强**（子系统 B）：
   - 拆分三个 channel（价格/股息率/分红事件），用户可分类调重要性。
   - 通知点击 **deep link 跳转到个股详情页**。
   - 新增「通知可靠性」设置入口，引导 Vivo 用户开启自启动/后台运行/电池白名单——**这是让通知功能真正可用的前提**。

### 非目标 (YAGNI)
- ❌ 不引入 vivo 推送 SDK / 扩展通知扩展服务（个人工具型，保 APK 小，靠引导授权 + WorkManager 兜底）。
- ❌ 不使用 `fullScreenIntent` 横幅（Vivo 后台拉起 Activity 受限，且打扰）。
- ❌ 不做精确到日的除权除息提醒（`dividend_payouts` channel 先建好但暂无规则触发，留给 backlog）。
- ❌ 不做自选股列表 Widget（本次只做 2×2 单卡片，列表形态留待后续）。
- ❌ 不做今日涨跌（需存昨收价，会碰 schema）；Widget 盈亏用**成本基准**口径。
- ❌ 不写 Compose UI / Glance UI 测试（手动真机验证）。

---

## 2. 关键决策（来自 brainstorming）

| 决策点 | 选择 | 理由 |
|---|---|---|
| Widget 技术 | **Jetpack Glance 1.1.1** | minSdk 24 直接可用（1.1+ 支持 API 23+）；复用项目 Compose + M3 风格；列表/卡片体验远好于 RemoteViews |
| Widget 形态 | 2×2 单卡片 | 总市值 + 盈亏 + FIRE 进度，一眼看总资产；列表形态留待后续 |
| Widget 取价 | **只读 price_cache + 手动刷新钮前台触发** | Vivo 上 Glance 周期刷新可能被冻，不指望后台拉网；手动刷新是用户主动点击属前台，可即时拉网 |
| 盈亏口径 | **成本基准盈亏** `(现价 - 成本) × 股数` | 守「不碰 schema」边界；持仓追踪工具用户更关心相对买入成本的浮盈亏 |
| DB schema | **不变**（v15） | 两个子系统完全复用现有表（price_cache / stocks / fire_goal / notification_rules） |
| 通知跳转 | **deep link → stockDetail/{code}** | 用户主动点击不受后台拉起限制；「App 外触达」价值最大化 |
| 通知分组 | 3 个新 channel（价格/股息率/分红） + 保留旧 channel | 分类调权；旧 channel 保留以免已发布设置丢失 |
| Vivo 保活 | **新增设置引导**（跳转 Vivo 私有 intent + 电池白名单） | Vivo 上 WorkManager 不可靠是既有问题；不引导则通知功能形同虚设 |
| Vivo 自启动检测 | **只提供跳转，不检测状态** | 系统 API 隐藏，反射在 Android 9+ 失效，无法可靠检测 |
| 手动刷新拉网 | **前台同步**（不走 WorkManager） | Vivo 会拖延 WorkManager；用户点 Widget 时等 1-2 秒可接受，失败保持旧缓存 |
| `dividend_payouts` channel | **先建好，暂无规则** | 为 backlog 的除权除息精确提醒预留；避免用户配置的重要性设置后续失效 |

---

## 3. 数据模型

**不新增任何 Entity，不改 DB schema（保持 v15）。** 本节定义纯数据类。

### 3.1 Widget UI 状态

```kotlin
// data/widget/WidgetUiState.kt
@Stable
data class WidgetUiState(
    val totalMarketValue: Double,        // Σ(持仓股数 × 现价)，现价缺失的股按 0 计入并标记
    val pricedCount: Int,                // 有现价的持仓股数（用于"X/Y 只已更新"）
    val holdingCount: Int,               // 持仓股总数（shares > 0）
    val costBasisPnl: Double,            // 成本基准盈亏 = Σ((现价 - 成本) × 股数)
    val costBasisPnlPercent: Double,     // 盈亏百分比 = costBasisPnl / Σ(成本 × 股数)
    val fireGoalAmount: Double,          // FIRE 目标金额（fire_goal 表，0 表未设）
    val fireProgress: Double,            // 0..1，年股息收入 / fireGoalAmount（复用现有 FIRE 口径）
    val lastPriceUpdatedAt: Long,        // price_cache 中最新一条 updatedAt（新鲜度）
    val isRefreshing: Boolean,           // 手动刷新中
    val refreshFailed: Boolean,          // 上次手动刷新是否失败
)
```

- 空持仓（`holdingCount == 0`）→ 调用方渲染空状态，不构造此 data class。
- `fireGoalAmount == 0` → UI 不渲染 FIRE 行（隐藏，不显示 0%）。
- 新鲜度相对时间（"12 分钟前"）在渲染层由 `lastPriceUpdatedAt` 计算，不进 data class。

### 3.2 Channel 常量

```kotlin
// data/notification/NotificationChannels.kt
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
```

### 3.3 Deep link extra 约定

```kotlin
// MainActivity 桥接用的 Intent extra key
const val EXTRA_STOCK_CODE = "extra_stock_code"   // 通知 PendingIntent 携带，跳转 stockDetail/{code}
```

---

## 4. 组件设计

### 4.1 子系统 A：行情 Widget

#### 4.1.1 `WidgetDataRepository`（新，数据层薄封装）

```kotlin
// data/repository/WidgetDataRepository.kt
@Singleton
class WidgetDataRepository @Inject constructor(
    private val stockDao: StockDao,
    private val priceCacheDao: PriceCacheDao,   // 复用已有（或经 StockRepository 暴露查询）
    private val fireGoalRepository: FireGoalRepository,
    private val stockRepository: StockRepository,
) {
    /** 读缓存快照，绝不拉网、绝不抛异常（失败返回空状态） */
    suspend fun loadSnapshot(): WidgetUiState

    /** 前台手动刷新：委托 StockRepository.fetchQuotes 拉持仓股现价，结果写回 price_cache */
    suspend fun refreshPrices(): Result<Unit>
}
```

- `loadSnapshot()` 纯聚合：持仓快照（shares>0）+ price_cache 逐股 join + FireGoal → 纯函数算市值/盈亏/进度。任何 DAO 异常吞掉，返回 `holdingCount=0` 的空状态。
- `refreshPrices()` 委托 `stockRepository.fetchQuotes(持仓股列表)`（复用现有 Semaphore(3) 限流逻辑），返回 `Result`；失败不抛，由调用方标记 `refreshFailed`。

#### 4.1.2 Widget UI（Glance）

**`MarketWidget`**（`data/widget/MarketWidget.kt`）：

```kotlin
class MarketWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.widgetDataRepository()   // EntryPoint 取
        val state = runCatching { repo.loadSnapshot() }.getOrNull()
        provideContent {
            MarketWidgetContent(state, isRefreshing = ...)  // Glance Composable
        }
    }
}
```

布局（2×2）：
```
┌─────────────────────────────────────┐
│  持仓总览                    ↻ 刷新  │  右上角 ActionCallback
│  ¥ 128,432.50                       │  总市值（大字，primary）
│  ▲ +3,210.00  +2.56%                │  成本基准盈亏（FinanceGreen/Red）
│  ─────────────────────────────      │
│  FIRE 进度  ████████░░  42%          │  fireGoalAmount>0 时显示
│  ─────────────────────────────      │
│  价格 12 分钟前更新 · 8/10 只        │  新鲜度 + pricedCount/holdingCount
└─────────────────────────────────────┘
       │ 整卡点击（actionStartActivity MainActivity）
       ▼
```

- 空持仓：渲染「暂无持仓，打开 App 添加」+ 点击打开 App。
- 价格全缺：`¥ --` + 「暂无价格缓存，点击刷新」。
- 刷新中：`↻` 置灰禁用（Glance 不支持无限动画，用 `isRefreshing` 切图标 alpha）。
- 刷新失败：底部小字「刷新失败，显示上次缓存」。

#### 4.1.3 `WidgetActionCallback`（点刷新钮）

```kotlin
// data/widget/WidgetActionCallback.kt
class RefreshActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, params: ActionParameters) {
        val repo = context.widgetDataRepository()
        // 先把 isRefreshing=true 写入 GlanceStateDefinition，触发重渲染
        updateAppWidgetState(context, glanceId) { it[KEY_REFRESHING] = true }
        MarketWidget().updateAll(context)
        // 前台同步拉网
        val result = repo.refreshPrices()
        updateAppWidgetState(context, glanceId) {
            it[KEY_REFRESHING] = false
            it[KEY_REFRESH_FAILED] = result.isFailure
        }
        MarketWidget().updateAll(context)   // 重新 loadSnapshot 渲染
    }
}
```

#### 4.1.4 Glance Hilt 集成（EntryPoint）

后台组件（`AppWidgetReceiver` / `ActionCallback`）不能用 `@Inject`，用 EntryPoint：

```kotlin
// data/widget/WidgetEntryPoint.kt
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetDataRepository(): WidgetDataRepository
}

// 扩展函数
fun Context.widgetDataRepository(): WidgetDataRepository =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)
        .widgetDataRepository()
```

#### 4.1.5 `MarketWidgetReceiver`

```kotlin
// data/widget/MarketWidgetReceiver.kt
class MarketWidgetReceiver : AppWidgetReceiver() {
    override val glanceAppWidget = MarketWidget()
}
```

`AndroidManifest.xml` 注册：
```xml
<receiver android:name=".data.widget.MarketWidgetReceiver" android:exported="false">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data android:name="android.appwidget.provider"
               android:resource="@xml/market_widget_info" />
</receiver>
```

`@xml/market_widget_info`：`minWidth=180dp, minHeight=180dp, updatePeriodMillis=1800000, initialLayout, previewLayout`。

### 4.2 子系统 B：通知渠道增强

#### 4.2.1 `AndroidDividendAlertNotifier` 改造

- 新增 `channelFor(ruleType): String`：按 ruleType 路由到 `PRICE_EVENTS` / `DIVIDEND_EVENTS`。
- `createChannel()` 改为建全部 4 个 channel（3 新 + 1 旧，旧的不删）。
- `sendNotificationRuleAlert` 的 `PendingIntent` 携带 `EXTRA_STOCK_CODE` extra：
```kotlin
val intent = Intent(context, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    putExtra(EXTRA_STOCK_CODE, stockCode)   // 新增
}
```
- `dividend_payouts` channel 暂无调用点，但 `createChannel()` 时一并建立。

#### 4.2.2 Deep link 桥接（MainActivity + MainScaffold）

**`MainActivity`** 改造：读取启动/新 Intent 的 `EXTRA_STOCK_CODE`，桥接到 Compose 层。

由于 `tabNavController` 定义在 `MainScaffold` 内部、Activity 拿不到，用 **Activity 持有的 `mutableStateOf`** 桥接（不引入新 CompositionLocal，最简单）：

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // deep link 待消费的 stockCode；null 表无
    var pendingDeepLink by mutableStateOf<String?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeDeepLink(intent)   // 冷启动
        setContent {
            StockDividendTheme {
                AppNavigation(
                    pendingDeepLink = pendingDeepLink,   // 透传给 MainScaffold
                    onDeepLinkConsumed = { pendingDeepLink = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeDeepLink(intent)   // 热启动（通知点击时 App 已在前台）
    }

    private fun consumeDeepLink(intent: Intent?) {
        pendingDeepLink = intent?.getStringExtra(EXTRA_STOCK_CODE)
    }
}
```

**`AppNavigation` / `MainScaffold`** 改造：接收 `pendingDeepLink` + `onDeepLinkConsumed`，在 `MainScaffold` 内用 `LaunchedEffect` 跳转：

```kotlin
@Composable
fun MainScaffold(
    rootNavController: NavHostController,
    pendingDeepLink: String?,            // 新增
    onDeepLinkConsumed: () -> Unit       // 新增
) {
    val tabNavController = rememberNavController()
    // ... 现有逻辑 ...

    LaunchedEffect(pendingDeepLink) {
        val code = pendingDeepLink ?: return@LaunchedEffect
        // tabNavController 已就绪（MainScaffold 的 NavHost startDestination 为 portfolio）
        tabNavController.navigate("stockDetail/$code") {
            launchSingleTop = true
        }
        onDeepLinkConsumed()
    }
    // ... 现有 NavHost ...
}
```

**坑点处理**：`LaunchedEffect` 在首次组合时 `tabNavController` 的 startDestination（portfolio）已就绪，`navigate(stockDetail)` 安全。通知点击时 App 若在后台，`onNewIntent` 写入 state，重组触发 `LaunchedEffect` 重跑（key 变化）。消费后置 null，避免重复跳转。

#### 4.2.3 `NotificationReliabilityScreen`（Vivo 保活引导，新页面）

```
SettingsScreen 新增入口：「通知可靠性」
   │ navigate("notificationReliability")
   ▼
NotificationReliabilityScreen (新)
 ├─ ① 通知权限（POST_NOTIFICATIONS / areNotificationsEnabled）
 │     状态可检测 → 未开则「去开启」跳 ACTION_APP_NOTIFICATION_SETTINGS
 ├─ ② 自启动（Vivo，无法检测状态）
 │     「去开启」按钮 → try startActivity(VivoPermissionIntents.bgStartUp())
 │     catch ActivityNotFoundException → 跳通用应用详情页
 ├─ ③ 电池优化白名单（可检测 PowerManager.isIgnoringBatteryOptimizations）
 │     未豁免 →「允许后台运行」跳 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 └─ ④ 说明文案：「为保证股价/股息率提醒按时推送，请保持以上开启」
```

**`VivoPermissionIntents`**（纯函数 object，可单测 intent 构造）：
```kotlin
// data/notification/VivoPermissionIntents.kt
object VivoPermissionIntents {
    /** Vivo 自启动管理页（私有 ComponentName，非 Vivo 机型抛 ActivityNotFoundException） */
    fun bgStartUp(): Intent = Intent().apply {
        component = ComponentName("com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
    }

    /** 通用应用详情页兜底 */
    fun appDetails(packageName: String): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
    ).setData(Uri.fromParts("package", packageName, null))
}
```

**路由注册**：`MainScaffold` 的 tab NavHost 加 `composable("notificationReliability") { NotificationReliabilityScreen(onBack = ...) }`；`SettingsScreen` 加入口（`onOpenNotificationReliability` 回调）。

---

## 5. 数据流

### 5.1 Widget 渲染与刷新

```
系统周期刷新（Glance periodic, 30min+，Vivo 可能冻）
  → MarketWidgetReceiver.onUpdate → provideGlance
  → widgetDataRepository().loadSnapshot()   ← 只读缓存，不拉网
  → 渲染（即便 Vivo 拖延，下次系统刷新自然更新缓存价）

用户点 ↻ 刷新钮（前台，可靠）
  → RefreshActionCallback.onAction
  → set isRefreshing=true → updateAll
  → widgetDataRepository().refreshPrices()
       → StockRepository.fetchQuotes(持仓股)   ← 复用 Semaphore(3) 限流
       → 结果写回 price_cache
  → set isRefreshing=false, refreshFailed=isFailure
  → loadSnapshot() 重渲染
```

### 5.2 通知发送与跳转

```
NotificationCheckWorker（现有，Vivo 上靠保活引导兜底）
  → NotificationCheckCoordinator（现有）
  → 规则触发 → AndroidDividendAlertNotifier.sendNotificationRuleAlert（改）
      → channelFor(ruleType) 选 channel
      → PendingIntent 携带 EXTRA_STOCK_CODE
      → notify()

用户点通知
  → MainActivity.onNewIntent（或冷启动 onCreate）
  → pendingDeepLink = stockCode
  → MainScaffold.LaunchedEffect(pendingDeepLink)
  → tabNavController.navigate("stockDetail/$code")
  → onDeepLinkConsumed()  // 置 null
```

---

## 6. 错误处理

| 场景 | 行为 |
|---|---|
| Widget loadSnapshot DAO 异常 | 吞异常，返回 `holdingCount=0` 空状态，渲染空态 |
| Widget 空持仓 | 「暂无持仓，打开 App 添加」+ 点击打开 App |
| Widget 价格全缺 | `¥ --` + 「暂无价格缓存，点击刷新」 |
| Widget 手动刷新拉网失败 | `refreshFailed=true`，保持旧缓存价，底部「刷新失败，显示上次缓存」 |
| Widget 手动刷新超时 | 同上（fetchQuotes 内部已有超时） |
| Vivo 自启动 intent 非 Vivo 机型 | `ActivityNotFoundException` → 跳通用应用详情页 |
| deep link stockCode 在库中不存在 | `stockDetail/{code}` 正常导航，StockDetailScreen 已有空处理（不崩） |
| 通知权限未授予 | `canNotify()` 返回 false，不发通知（现有逻辑） |
| 旧 channel 用户已禁用 | 保留旧 channel 不删；新规则走新 channel，受新 channel 设置控制 |

---

## 7. 测试策略

JUnit4 + Truth + MockK + Robolectric + kotlinx-coroutines-test。

### 7.1 `WidgetDataRepositoryTest`（Robolectric，fake DAO）
- 正常：3 只持仓 + price_cache 有 2 只价 → `totalMarketValue` 正确，`pricedCount=2/holdingCount=3`。
- 成本基准盈亏：`(现价-成本)×股数` 正负两种。
- FireGoal=0 → `fireGoalAmount=0`，UI 隐藏。
- DAO 抛异常 → 返回空状态，不崩。
- `refreshPrices()` 成功/失败 → `Result.success/failure`。

### 7.2 `VivoPermissionIntentsTest`（纯函数）
- `bgStartUp()` 的 component 是 Vivo 私有包名。
- `appDetails(pkg)` 的 action + data 正确。

### 7.3 `NotificationChannelsTest`（纯函数）
- `channelFor(PRICE_ABOVE)` / `(BOLL_WEEKLY_UPPER)` → `PRICE_EVENTS`。
- `channelFor(DIVIDEND_YIELD_THRESHOLD)` → `DIVIDEND_EVENTS`。
- 未知 ruleType → 兜底 `PRICE_EVENTS`（非股息率的事件更可能偏价格类；选择明确不再二选一）。

### 7.4 `AndroidDividendAlertNotifierTest`（Robolectric）
- 通知 PendingIntent 的 `EXTRA_STOCK_CODE` 正确。
- 4 个 channel 均已创建。

### 7.5 不写测试
- Glance Widget UI 渲染（手动真机验证）。
- Vivo 私有 intent 真实跳转（真机验证）。
- deep link 实际跳转（手动验证）。

---

## 8. 文件改动清单

### 新增
- `data/widget/MarketWidget.kt`（GlanceAppWidget + Composable 内容）
- `data/widget/MarketWidgetReceiver.kt`（AppWidgetReceiver）
- `data/widget/WidgetActionCallback.kt`（RefreshActionCallback）
- `data/widget/WidgetEntryPoint.kt`（Hilt EntryPoint + Context 扩展）
- `data/widget/WidgetUiState.kt`（data class）
- `data/repository/WidgetDataRepository.kt`（数据层）
- `data/notification/NotificationChannels.kt`（channel 常量 + channelFor）
- `data/notification/VivoPermissionIntents.kt`（Vivo intent 纯函数）
- `ui/screen/NotificationReliabilityScreen.kt`（保活引导页）
- `res/xml/market_widget_info.xml`（AppWidget 元信息）
- 对应测试：`WidgetDataRepositoryTest`、`VivoPermissionIntentsTest`、`NotificationChannelsTest`、`AndroidDividendAlertNotifierTest`

### 修改
- `app/build.gradle.kts` — 加 Glance 依赖（`glance-appwidget` + `glance-material3`）
- `gradle/libs.versions.toml` — 加 `glance = "1.1.1"` + 两个 library 引用（**唯一版本来源**）
- `app/src/main/AndroidManifest.xml` — 注册 `MarketWidgetReceiver`
- `MainActivity.kt` — `pendingDeepLink` state + `onNewIntent` + `consumeDeepLink` + 透传 AppNavigation
- `ui/navigation/AppNavigation.kt` — `AppNavigation` 接收 `pendingDeepLink` / `onDeepLinkConsumed` 透传 MainScaffold
- `ui/screen/MainScaffold.kt` — 接收 deep link 参数 + `LaunchedEffect` 跳转；tab NavHost 加 `notificationReliability` 路由
- `data/notification/AndroidDividendAlertNotifier.kt` — `channelFor` 路由 + 建 4 channel + PendingIntent 携带 `EXTRA_STOCK_CODE`
- `ui/screen/NotificationSettingsScreen.kt`（含 SettingsScreen）— 加「通知可靠性」入口
- `StockDao.kt` — 如需，暴露 price_cache 的查询（或经 StockRepository）

### 不动
- DB schema（`AppDatabase` 保持 v15，无 Migration）
- `NotificationCheckWorker` / `NotificationCheckCoordinator` / `NotificationRuleEvaluator`（通知规则逻辑原样）
- `StockRepository.fetchQuotes`（复用现有拉价逻辑）

---

## 9. 风险与未决

- **Vivo 后台保活不可靠**：即便引导用户开启自启动/后台运行，OriginOS 的激进省电仍可能在某些场景杀进程。本设计**不承诺通知 100% 到达**，靠引导最大化可靠性 + WorkManager 兜底。已向用户说明此限制。
- **Glance 周期刷新被 Vivo 节流**：`updatePeriodMillis=30min` 在 Vivo 上可能更长延迟。设计上 Widget 主体永远只读缓存，延迟只影响「自动看到新价」的频率，不影响可用性；手动刷新是主路径。
- **成本基准盈亏语义**：非传统「今日涨跌」，用户预期可能不符。Widget 上不标「今日」，而标「持仓盈亏」以管理预期。
- **deep link 时机**：`LaunchedEffect` 依赖 `tabNavController` 就绪。冷启动时 startDestination（portfolio）已导航，安全；极少数竞态情况下可能跳转失败但 App 仍正常打开到首页（不崩）。
- **Vivo 私有 intent 不稳定**：`com.vivo.permissionmanager` 的 Activity 名可能随 OriginOS 版本变化。`try/catch` + 通用兜底覆盖；后续可按 OriginOS 版本扩展多个候选 intent。
- **Glance 依赖体积**：`glance-appwidget` + `glance-material3` 约增加 ~500KB APK 体积。对个人工具型可接受。
- **`dividend_payouts` channel 预留**：先建好但无规则，用户在系统设置会看到这个 channel 但收不到通知。可在 channel 描述里注明「分红事件（即将开放）」管理预期。
- **PriceCacheDao 访问**：当前 `price_cache` 的查询若未在 DAO 暴露，需补一个 `getAllCachedPrices(): Map<String, PriceCacheEntity>` 查询（仅读，不改 schema）。

---

## 10. Backlog（本次不做，记录待后续）

- **自选股列表 Widget**（4×2，可滚动 LazyColumn）
- **股息日历 Widget**（近期除权除息日速览）
- **除权除息精确日提醒**（AlarmManager 精确触发，使用预留的 `dividend_payouts` channel）
- **LLM 能力拓展**（自然语言选股问答 / 个股深度分析 / DDM 解读）
- **交易流水与盈亏分析**（盘活 transactions 表）
- **分红再投资 DRIP**（复利模拟计算器）
