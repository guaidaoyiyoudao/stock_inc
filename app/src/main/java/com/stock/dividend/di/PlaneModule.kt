package com.stock.dividend.di

import com.stock.dividend.data.plane.DividendFreshnessStore
import com.stock.dividend.data.plane.PrefsDividendFreshnessStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 数据平面（MarketDataPlane）相关绑定。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlaneModule {
    @Binds
    @Singleton
    abstract fun bindDividendFreshnessStore(
        impl: PrefsDividendFreshnessStore
    ): DividendFreshnessStore
}
