package com.stock.dividend.di

import com.stock.dividend.data.notification.AndroidDividendAlertNotifier
import com.stock.dividend.data.notification.DividendAlertNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationModule {
    @Binds
    @Singleton
    abstract fun bindDividendAlertNotifier(
        notifier: AndroidDividendAlertNotifier
    ): DividendAlertNotifier
}
