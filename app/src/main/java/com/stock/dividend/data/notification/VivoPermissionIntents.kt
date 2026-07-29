package com.stock.dividend.data.notification

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Vivo OriginOS 私有设置页 intent 构造。非 Vivo 机型调用会抛 ActivityNotFoundException，调用方需 try/catch。 */
object VivoPermissionIntents {

    /** Vivo 自启动管理页（私有 ComponentName） */
    fun bgStartUp(): Intent = Intent().apply {
        component = ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        )
    }

    /** 通用应用详情页兜底 */
    fun appDetails(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))

    /** 应用通知设置页（用于引导开通知权限） */
    fun appNotificationSettings(packageName: String): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
}
