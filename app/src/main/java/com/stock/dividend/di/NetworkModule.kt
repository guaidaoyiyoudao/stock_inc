package com.stock.dividend.di

import com.stock.dividend.data.remote.DividendApi
import com.stock.dividend.data.remote.QuoteApi
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

/** 标记腾讯财经股息接口（主源）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TencentDividendSource

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val SEARCH_BASE_URL = "https://searchapi.eastmoney.com/"
    private const val DATA_BASE_URL = "https://datacenter-web.eastmoney.com/"
    private const val QUOTE_BASE_URL = "https://push2.eastmoney.com/"
    private const val TENCENT_KLINE_BASE_URL = "https://web.ifzq.gtimg.cn/"

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
}
