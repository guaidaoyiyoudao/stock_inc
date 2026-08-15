package com.stock.dividend.di

import com.stock.dividend.data.remote.AnnouncementApi
import com.stock.dividend.data.remote.BondYieldApi
import com.stock.dividend.data.remote.DividendApi
import com.stock.dividend.data.remote.FundamentalApi
import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.MarketApi
import com.stock.dividend.data.remote.QuoteApi
import com.stock.dividend.data.remote.ResearchApi
import com.stock.dividend.data.remote.SearchApi
import com.stock.dividend.data.remote.TencentDividendApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** 标记东方财富股息接口。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EastMoneyDividendApi

/** 标记东方财富基本面（主要财务指标）接口。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EastMoneyFundamentalApi

/** 标记腾讯财经股息接口（主源）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TencentDividendSource

/** 标记 LLM 专用 client（60s 超时，LLM 响应慢）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LlmClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val SEARCH_BASE_URL = "https://searchapi.eastmoney.com/"
    private const val DATA_BASE_URL = "https://datacenter-web.eastmoney.com/"
    private const val DATACENTER_BASE_URL = "https://datacenter.eastmoney.com/"
    private const val QUOTE_BASE_URL = "https://push2.eastmoney.com/"
    private const val TENCENT_KLINE_BASE_URL = "https://web.ifzq.gtimg.cn/"
    private const val REPORT_API_BASE_URL = "https://reportapi.eastmoney.com/"
    private const val ANNOUNCEMENT_BASE_URL = "https://np-anotice-stock.eastmoney.com/"

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                val referer = when {
                    url.contains("ifzq.gtimg.cn") -> "https://gu.qq.com/"
                    url.contains("searchapi") -> "https://so.eastmoney.com/"
                    url.contains("push2") -> "https://quote.eastmoney.com/"
                    else -> "https://data.eastmoney.com/"
                }
                val newRequest = request.newBuilder()
                    .header("Referer", referer)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()
                chain.proceed(newRequest)
            }
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideSearchApi(client: OkHttpClient): SearchApi {
        return Retrofit.Builder()
            .baseUrl(SEARCH_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SearchApi::class.java)
    }

    @Provides
    @Singleton
    @EastMoneyDividendApi
    fun provideEastMoneyDividendApi(client: OkHttpClient): DividendApi {
        return Retrofit.Builder()
            .baseUrl(DATA_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DividendApi::class.java)
    }

    @Provides
    @Singleton
    @EastMoneyFundamentalApi
    fun provideEastMoneyFundamentalApi(client: OkHttpClient): FundamentalApi {
        return Retrofit.Builder()
            .baseUrl(DATA_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FundamentalApi::class.java)
    }

    @Provides
    @Singleton
    @TencentDividendSource
    fun provideTencentDividendApi(client: OkHttpClient): TencentDividendApi {
        return Retrofit.Builder()
            .baseUrl(TENCENT_KLINE_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TencentDividendApi::class.java)
    }

    @Provides
    @Singleton
    fun provideQuoteApi(client: OkHttpClient): QuoteApi {
        return Retrofit.Builder()
            .baseUrl(QUOTE_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(QuoteApi::class.java)
    }

    @Provides
    @Singleton
    fun provideBondYieldApi(client: OkHttpClient): BondYieldApi {
        return Retrofit.Builder()
            .baseUrl(DATACENTER_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BondYieldApi::class.java)
    }

    @Provides
    @Singleton
    fun provideMarketApi(client: OkHttpClient): MarketApi {
        return Retrofit.Builder()
            .baseUrl(QUOTE_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MarketApi::class.java)
    }

    @Provides
    @Singleton
    fun provideResearchApi(client: OkHttpClient): ResearchApi {
        return Retrofit.Builder()
            .baseUrl(REPORT_API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ResearchApi::class.java)
    }

    @Provides
    @Singleton
    fun provideAnnouncementApi(client: OkHttpClient): AnnouncementApi {
        return Retrofit.Builder()
            .baseUrl(ANNOUNCEMENT_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AnnouncementApi::class.java)
    }

    @Provides
    @Singleton
    @LlmClient
    fun provideLlmOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // web_search + reasoning 多轮搜索实测可达 60s+，给足余量避免流式 readTimeout。
            // 流式逐 token 间隔虽小（<2s），但首 token / 工具结果返回前可能较久。
            .readTimeout(180, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideLlmApi(@LlmClient client: OkHttpClient): LlmApi {
        return Retrofit.Builder()
            .baseUrl("http://localhost/")   // 占位；实际 URL 走 @Url
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LlmApi::class.java)
    }
}
