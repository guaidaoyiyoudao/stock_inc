package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.remote.FuyaoApi
import com.stock.dividend.data.remote.dto.FuyaoEnvelope
import com.stock.dividend.data.remote.dto.FuyaoFundProfileData
import com.stock.dividend.data.remote.dto.FuyaoFundProfileItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/** 基金扩展数据仓库（扶摇独有能力：禁用即不可用、失败落日志无降级）。 */
class FundDataRepositoryTest {

    private val fuyaoApi: FuyaoApi = mockk()
    private val fuyaoConfig: FuyaoConfig = mockk(relaxed = true)
    private val errorLogRepository: ErrorLogRepository = mockk(relaxed = true)
    // 真实缓存仓（mock DAO）：走真实合并/回退语义而非桩
    private val cacheDao: com.stock.dividend.data.local.dao.FuyaoCacheDao = mockk(relaxed = true)
    private val repository = FundDataRepository(fuyaoApi, fuyaoConfig, FuyaoCacheStore(cacheDao), errorLogRepository)

    @Before
    fun setUp() {
        coEvery { fuyaoConfig.enabled } returns true
    }

    private fun profileEnvelope() = FuyaoEnvelope(
        code = 0, message = "success", requestId = "t",
        data = FuyaoFundProfileData(
            item = listOf(
                FuyaoFundProfileItem(
                    thscode = "510880.SH", fundName = "红利ETF华泰柏瑞",
                    fundScale = 20230842936.46, unitNav = 3.3878
                )
            )
        )
    )

    @Test
    fun `profile maps fund code to exchange thscode`() = runTest {
        coEvery { fuyaoApi.getFundProfile(fundType = any(), thscode = any()) } returns profileEnvelope()

        val profile = repository.getProfile("sh.510880")

        assertThat(profile!!.fundName).isEqualTo("红利ETF华泰柏瑞")
        assertThat(profile.fundScale).isWithin(0.01).of(20230842936.46)
        coVerify { fuyaoApi.getFundProfile(fundType = "exchange", thscode = "510880.SH") }
    }

    @Test
    fun `disabled config returns null without network`() = runTest {
        coEvery { fuyaoConfig.enabled } returns false

        assertThat(repository.getProfile("sh.510880")).isNull()
        assertThat(repository.getHoldings("sh.510880")).isNull()
        assertThat(repository.getIndustryAllocation("sh.510880")).isEmpty()
        coVerify(exactly = 0) { fuyaoApi.getFundProfile(any(), any()) }
    }

    @Test
    fun `failure logs error and returns null`() = runTest {
        coEvery { fuyaoApi.getFundProfile(fundType = any(), thscode = any()) } throws
            java.io.IOException("fuyao down")

        assertThat(repository.getProfile("sh.510880")).isNull()
        coVerify(atLeast = 1) {
            errorLogRepository.record(source = "同花顺", message = any(), throwable = any(), category = any())
        }
    }

    @Test
    fun `business error envelope treated as failure`() = runTest {
        coEvery { fuyaoApi.getFundProfile(fundType = any(), thscode = any()) } returns
            FuyaoEnvelope(code = 2003, message = "forbidden", requestId = "t", data = null)

        assertThat(repository.getProfile("sh.510880")).isNull()
    }

    @Test
    fun `unmappable fund code returns empty without network`() = runTest {
        assertThat(repository.getProfile("600519")).isNull()   // 非 sh./sz. 前缀
        coVerify(exactly = 0) { fuyaoApi.getFundProfile(any(), any()) }
    }

    // ── 持久缓存语义（DB v28：历史不可变合并保留 + 失败/禁用回退）──

    @Test
    fun `holdings merge keeps cached older report periods forever`() = runTest {
        val dao = com.stock.dividend.data.local.dao.FuyaoCacheDao::class
        // 预置缓存：旧报告期（2025 年中）已存在
        val cachedItem = com.stock.dividend.data.remote.dto.FuyaoFundHoldingItem(
            thscode = "601919.SH", stockName = "中远海控", holdRatio = 4.82,
            endDateMs = 1748736000000L
        )
        coEvery { cacheDao.get("fundHoldings|sh.510880") } returns com.stock.dividend.data.local.entity.FuyaoCacheEntity(
            key = "fundHoldings|sh.510880",
            payload = com.google.gson.Gson().toJson(
                com.stock.dividend.data.remote.dto.FuyaoFundHoldingsData(item = listOf(cachedItem))
            ),
            fetchedAt = System.currentTimeMillis() - 86_400_000L
        )
        // 远端：新报告期（2026 年中）
        coEvery { fuyaoApi.getFundHoldings(fundType = any(), thscode = any()) } returns
            FuyaoEnvelope(
                code = 0, message = "success", requestId = "t",
                data = com.stock.dividend.data.remote.dto.FuyaoFundHoldingsData(
                    item = listOf(
                        com.stock.dividend.data.remote.dto.FuyaoFundHoldingItem(
                            thscode = "601919.SH", stockName = "中远海控", holdRatio = 5.0,
                            endDateMs = 1782748800000L
                        )
                    )
                )
            )

        val holdings = repository.getHoldings("sh.510880")

        // 新旧两个报告期都在（缓存独有旧期次永续保留）
        assertThat(holdings!!.item).hasSize(2)
        assertThat(holdings.item!!.map { it.endDateMs }).containsExactly(1782748800000L, 1748736000000L).inOrder()
    }

    @Test
    fun `disabled config still serves cached holdings offline`() = runTest {
        coEvery { fuyaoConfig.enabled } returns false
        coEvery { cacheDao.get("fundHoldings|sh.510880") } returns com.stock.dividend.data.local.entity.FuyaoCacheEntity(
            key = "fundHoldings|sh.510880",
            payload = com.google.gson.Gson().toJson(
                com.stock.dividend.data.remote.dto.FuyaoFundHoldingsData(
                    item = listOf(
                        com.stock.dividend.data.remote.dto.FuyaoFundHoldingItem(
                            stockName = "中远海控", holdRatio = 4.82
                        )
                    )
                )
            ),
            fetchedAt = System.currentTimeMillis()
        )

        val holdings = repository.getHoldings("sh.510880")

        assertThat(holdings!!.item).hasSize(1)   // 断网/禁用：历史数据依然可读（离线优先）
        coVerify(exactly = 0) { fuyaoApi.getFundHoldings(any(), any()) }
    }

    @Test
    fun `holdings merge remote overrides same period and null keys do not accumulate`() = runTest {
        // 缓存：2026 年中报告期 holdRatio=4.82 + 一条 endDateMs=null 的脏行
        coEvery { cacheDao.get("fundHoldings|sh.510880") } returns com.stock.dividend.data.local.entity.FuyaoCacheEntity(
            key = "fundHoldings|sh.510880",
            payload = com.google.gson.Gson().toJson(
                com.stock.dividend.data.remote.dto.FuyaoFundHoldingsData(
                    item = listOf(
                        com.stock.dividend.data.remote.dto.FuyaoFundHoldingItem(
                            thscode = "601919.SH", stockName = "中远海控", holdRatio = 4.82,
                            endDateMs = 1782748800000L
                        ),
                        com.stock.dividend.data.remote.dto.FuyaoFundHoldingItem(
                            thscode = "600941.SH", stockName = "中国移动", holdRatio = 1.0,
                            endDateMs = null
                        )
                    )
                )
            ),
            fetchedAt = System.currentTimeMillis() - 86_400_000L
        )
        // 远端：同期报告期修正 holdRatio=5.1 + 一条新的 null 键行
        coEvery { fuyaoApi.getFundHoldings(fundType = any(), thscode = any()) } returns
            FuyaoEnvelope(
                code = 0, message = "success", requestId = "t",
                data = com.stock.dividend.data.remote.dto.FuyaoFundHoldingsData(
                    item = listOf(
                        com.stock.dividend.data.remote.dto.FuyaoFundHoldingItem(
                            thscode = "601919.SH", stockName = "中远海控", holdRatio = 5.1,
                            endDateMs = 1782748800000L
                        ),
                        com.stock.dividend.data.remote.dto.FuyaoFundHoldingItem(
                            thscode = "601398.SH", stockName = "工商银行", holdRatio = 0.9,
                            endDateMs = null
                        )
                    )
                )
            )

        val holdings = repository.getHoldings("sh.510880")

        // 同期冲突远端胜出（扶摇侧修正生效），null 键行只保留远端侧（不与缓存 null 行累积塌缩）
        assertThat(holdings!!.item).hasSize(2)
        val keyed = holdings.item!!.first { it.endDateMs == 1782748800000L }
        assertThat(keyed.holdRatio).isWithin(1e-9).of(5.1)
        assertThat(holdings.item!!.count { it.endDateMs == null }).isEqualTo(1)
        assertThat(holdings.item!!.single { it.endDateMs == null }.thscode).isEqualTo("601398.SH")
    }

    @Test
    fun `nav merge remote overrides same date`() = runTest {
        // 缓存：2026-06-30 净值 3.00
        coEvery { cacheDao.get("fundNav|sh.510880|latest") } returns com.stock.dividend.data.local.entity.FuyaoCacheEntity(
            key = "fundNav|sh.510880|latest",
            payload = com.google.gson.Gson().toJson(
                listOf(
                    com.stock.dividend.data.remote.dto.FuyaoFundNavItem(navDateMs = 1782748800000L, unitNav = 3.00)
                )
            ),
            fetchedAt = System.currentTimeMillis() - 86_400_000L
        )
        // 远端：同日净值修正 3.10 + 新一日 3.12
        coEvery { fuyaoApi.getFundNav(fundType = any(), thscode = any(), range = any()) } returns
            FuyaoEnvelope(
                code = 0, message = "success", requestId = "t",
                data = com.stock.dividend.data.remote.dto.FuyaoFundNavData(
                    item = listOf(
                        com.stock.dividend.data.remote.dto.FuyaoFundNavItem(navDateMs = 1782748800000L, unitNav = 3.10),
                        com.stock.dividend.data.remote.dto.FuyaoFundNavItem(navDateMs = 1782835200000L, unitNav = 3.12)
                    )
                )
            )

        val nav = repository.getNav("sh.510880")

        assertThat(nav).hasSize(2)
        assertThat(nav.first { it.navDateMs == 1782748800000L }.unitNav).isWithin(1e-9).of(3.10)
        assertThat(nav.map { it.navDateMs }).containsExactly(1782748800000L, 1782835200000L).inOrder()
    }
}
