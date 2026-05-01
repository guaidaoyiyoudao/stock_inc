# 股息追踪 — Stock Dividend Tracker

A股股息追踪 Android 应用，记录持仓、预测股息收入、规划 FIRE 财务自由目标。

## 特性

- **自选股管理** — 搜索东方财富全量 A 股，添加到自选列表
- **持仓编辑** — 设置持股数量、成本价、分红统计年限（1/3/5 年）
- **股息预测** — 基于历史分红数据计算年均每股分红，预估持仓年度股息收入
- **股息日历** — 逐笔股息收入记录，自动生成与手动录入双来源，支持纠偏
- **FIRE 目标** — 设定目标年支出，实时追踪股息覆盖率进度条
- **实时行情** — 下拉刷新获取持仓最新价与市值
- **股息收入趋势** — 年度柱状图直观展示多年代际变化
- **深色模式** — 支持 Material Design 3 动态取色与深色主题

## 技术栈

| 层 | 技术 |
|---|---|
| 语言 | Kotlin 2.0+ |
| UI | Jetpack Compose + Material Design 3 |
| 架构 | MVVM + Repository Pattern |
| 依赖注入 | Hilt (2.53.1) |
| 本地存储 | Room (2.6.1) |
| 网络 | Retrofit + OkHttp + Gson |
| 异步 | Kotlin Coroutines + Flow |
| 导航 | Navigation Compose (2.8.5) |
| 图表 | Vico |
| 数据源 | 东方财富公开 API |
| 构建 | Gradle + KSP |

## 构建

### 环境要求

- JDK 17
- Android SDK (compileSdk 36, minSdk 24)

### 构建命令

```bash
# Debug
./gradlew assembleDebug

# Release（需配置签名，参见下文）
./gradlew assembleRelease
```

### Release 签名

Release 构建通过环境变量传入签名信息：

| 变量 | 说明 |
|---|---|
| `KEYSTORE_FILE` | keystore 文件路径 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

CI 会自动从 GitHub Secrets 注入以上变量。本地构建示例：

```bash
export KEYSTORE_FILE=app/release.keystore
export KEYSTORE_PASSWORD=your_password
export KEY_ALIAS=your_alias
export KEY_PASSWORD=your_password
./gradlew assembleRelease
```

### GitHub Release

推送 `v*` 标签触发自动构建并发布到 GitHub Releases：

```bash
git tag v1.0.0
git push origin v1.0.0
```

## 数据来源

所有行情与分红数据来自 [东方财富](https://www.eastmoney.com/) 公开接口。
