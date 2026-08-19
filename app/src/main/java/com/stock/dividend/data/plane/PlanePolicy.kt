package com.stock.dividend.data.plane

/**
 * 数据平面（[MarketDataPlane]）的缓存/新鲜度策略，集中一处便于审查与调整。
 *
 * 分层职责（自上而下）：
 * 1. 内存会话缓存——挡住同一会话内的重复请求（combine 风暴、多页面/工具同时取数）；
 * 2. Room/SharedPreferences 持久缓存——冷启动兜底与历史不可变数据（price_cache / kline_cache /
 *    fundamentals_cache / dividends 等，由各网络源 Repository 自行编排）；
 * 3. 真实网络请求。
 */
object PlanePolicy {

    /**
     * 行情内存会话新鲜度窗口：窗口内对同一批代码的重复获取直接复用内存结果（不发网络）。
     * 主要防御 VM combine 多流重发射导致的重复拉价风暴；用户显式下拉刷新走 force=true 绕过。
     */
    const val QUOTE_FRESH_MS = 10_000L

    /**
     * 分红数据有效期：表空或距上次**成功**拉取超此时长，任何取数入口（getDps 等）自动触发网络刷新。
     * 分红一年变更 1-2 次，7 天足够新鲜且请求开销可忽略。
     */
    const val DIVIDEND_FRESH_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * 分红刷新失败退避窗口：失败后此时长内不再重试，防止断网时 combine 风暴反复打接口。
     */
    const val DIVIDEND_RETRY_BACKOFF_MS = 5L * 60 * 1000

    /**
     * 市场类数据（指数/板块/榜单/资金流）内存缓存 TTL：今日页 + AI 简报 + Agent 工具
     * 常在同一会话内取同一批数据，60 秒共享一次请求。
     */
    const val MARKET_TTL_MS = 60_000L

    /**
     * BOLL 内存缓存 TTL：K线 Room 缓存已挡掉绝大部分网络请求，这里只挡重复读库 + 重复计算
     * （今日页/组合页/简报/通知各自算 BOLL 的场景）。
     */
    const val BOLL_TTL_MS = 60_000L
}
