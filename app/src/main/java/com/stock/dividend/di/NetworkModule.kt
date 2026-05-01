package com.stock.dividend.di

import com.stock.dividend.data.remote.DividendApi
import com.stock.dividend.data.remote.QuoteApi
import com.stock.dividend.data.remote.SearchApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val SEARCH_BASE_URL = "https://searchapi.eastmoney.com/"
    private const val DATA_BASE_URL = "https://datacenter-web.eastmoney.com/"
    private const val QUOTE_BASE_URL = "https://push2.eastmoney.com/"

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
    fun provideDividendApi(client: OkHttpClient): DividendApi {
        return Retrofit.Builder()
            .baseUrl(DATA_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DividendApi::class.java)
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
