package com.stock.dividend.data.widget

import android.content.Context
import com.stock.dividend.data.repository.WidgetDataRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Glance 后台组件（AppWidgetReceiver / ActionCallback）不能用 @Inject，
 * 用 EntryPoint 从 applicationContext 取 [WidgetDataRepository]。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetDataRepository(): WidgetDataRepository
}

/** 从 Context 取 WidgetDataRepository 的便捷扩展。 */
fun Context.widgetDataRepository(): WidgetDataRepository =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)
        .widgetDataRepository()
