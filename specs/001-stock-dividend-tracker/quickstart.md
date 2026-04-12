# Quickstart: Stock Dividend Tracker

**Branch**: `001-stock-dividend-tracker` | **Date**: 2026-04-12

## 前提条件

- Android Studio Hedgehog 或更新版本
- JDK 17+
- Android 模拟器 (API 24+) 或真机 (Android 7.0+)
- 网络连接 (首次添加股票和刷新数据时需要)

## 构建与运行

```bash
# 1. 打开项目
# 在 Android Studio 中打开仓库根目录

# 2. 同步 Gradle
# Android Studio 会自动提示同步, 或手动 File > Sync Project with Gradle Files

# 3. 构建 APK
./gradlew assembleDebug

# 4. 安装到设备
./gradlew installDebug

# 5. 或直接在 Android Studio 中点击 Run
```

## 首次使用验证

1. 启动应用 → 看到空状态页面, 提示"点击右下角按钮添加股票"
2. 点击 FAB (+) 按钮 → 进入股票搜索页面
3. 输入"平安银行" → 搜索结果中显示"平安银行 (000001)"
4. 点击选择 → 返回主页, 列表中出现"平安银行"卡片, 展示逐年股息数据
5. 主页顶部汇总卡片显示总股息收入

## 关键配置

- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 35 (最新稳定版)
- **数据源**: 东方财富公开 API (无需配置 API Key)
- **本地存储**: Room SQLite (自动创建, 无需手动配置)

## 项目结构速览

```
app/src/main/java/com/stock/dividend/
├── MainActivity.kt          # 入口
├── data/                    # 数据层 (Room + Retrofit)
├── ui/                      # UI 层 (Compose Screens)
└── viewmodel/               # ViewModel 层
```
