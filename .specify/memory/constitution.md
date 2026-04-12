<!--
Sync Impact Report:
- Version change: N/A → 1.0.0 (initial ratification)
- Modified principles: N/A (first version)
- Added sections: All (Core Principles I–V, Design Standards, Development Workflow, Governance)
- Removed sections: None
- Templates requiring updates:
  ✅ plan-template.md — Constitution Check section aligns with principles
  ✅ spec-template.md — No changes needed (spec is implementation-agnostic)
  ✅ tasks-template.md — Task phases align with development workflow
- Follow-up TODOs: None
-->

# Stock Dividend Tracker Constitution

## Core Principles

### I. Modern Android Development

本项目 MUST 采用 Kotlin 作为主要开发语言。所有新代码 MUST 使用 Kotlin 编写，
禁止引入 Java 代码。UI 层 MUST 采用 Jetpack Compose 实现声明式界面，遵循
Material Design 3 设计规范。架构 MUST 遵循 Android 官方推荐的现代应用架构
（MVVM + Repository 模式 + 单 Activity 架构）。

Rationale: Kotlin + Jetpack Compose + Material Design 3 是 Android 生态的
现代化标准，确保长期可维护性和与平台演进的一致性。

### II. 离线优先与数据持久化

应用 MUST 支持离线浏览已缓存的股息数据。所有用户关注的股票列表和查询到的
股息数据 MUST 持久化存储在本地设备上。网络请求仅用于获取新数据或刷新现有数据，
MUST NOT 阻塞用户查看已缓存的内容。

Rationale: 移动应用的网络环境不稳定，离线优先策略确保用户随时可以访问自己的
投资数据，提升用户体验和可靠性。

### III. 数据准确性

从东方财富获取的股息数据 MUST 与数据源保持 100% 一致。应用 MUST NOT 对
原始数据进行任何换算或修改（除展示格式化及"每10股→每股"单位换算外）。每股派息
金额 MUST 展示为税前金额，MUST 明确标注数据来源和更新时间。

Rationale: 股息数据是投资决策的重要参考，任何数据偏差都可能导致用户做出错误
判断。数据准确性是不可妥协的底线。

### IV. 简洁与可维护性

代码结构 MUST 保持简单直接，遵循 YAGNI（You Aren't Gonna Need It）原则。
每个功能模块 MUST 职责单一、边界清晰。禁止过度抽象——三行相似代码优于一个
不必要的抽象层。依赖注入 MUST 使用 Hilt，但仅用于必须跨模块共享的依赖。

Rationale: 个人使用的工具型应用不需要企业级架构的复杂性。保持简单可以让
开发和维护更高效。

### V. 用户友好的错误处理

所有外部数据源交互（东方财富查询、网络请求）MUST 实现优雅的错误处理：
网络不可用时展示友好的提示信息和缓存数据；数据查询失败时 MUST 提供重试
机制；MUST NOT 向用户暴露原始技术错误信息。

Rationale: 用户不是开发者，技术性的错误信息会造成困惑。优雅的错误处理
提升用户对应用的信任感。

## Design Standards

界面设计 MUST 遵循以下标准：

- MUST 使用 Material Design 3 设计语言，包括动态配色（Dynamic Color）
- MUST 支持深色模式（Dark Theme）
- 列表展示 MUST 使用 Card 组件区分不同股票
- 汇总数据 MUST 在页面顶部突出显示
- 添加股票的交互 MUST 通过 FloatingActionButton（FAB）触发
- MUST 使用下拉刷新（SwipeRefresh）更新股息数据
- 空状态页面 MUST 提供引导用户操作的视觉提示
- MUST 支持中文界面，所有文本使用中文展示

## Development Workflow

- MUST 使用 Gradle Kotlin DSL 进行项目构建配置
- MUST 采用单 Activity + 多 Composable Screen 的导航架构
- MUST 使用 Version Catalog（libs.versions.toml）统一管理依赖版本
- Minimum SDK: 24（Android 7.0）, Target SDK: 最新稳定版
- 数据存储 MUST 使用 Room 数据库
- 网络请求 MUST 使用 Retrofit + OkHttp
- MUST 使用协程（Coroutines）和 Flow 处理异步操作
- 每个功能完成 MUST 在至少一台真机或模拟器上验证

## Governance

本宪法是项目所有开发实践的最高准则。所有代码实现 MUST 符合上述原则。

修正案流程：
- 任何原则的修改 MUST 记录修改原因和影响范围
- 修改 MUST 更新版本号（语义化版本：MAJOR.MINOR.PATCH）
- MAJOR：原则删除或根本性重定义
- MINOR：新增原则或实质性扩展
- PATCH：措辞澄清、修正、非语义性调整

所有实现方案 MUST 通过宪法检查（Constitution Check）后才能进入开发阶段。

**Version**: 1.0.1 | **Ratified**: 2026-04-12 | **Last Amended**: 2026-04-12
