package com.stock.dividend.data.notification

import android.content.ComponentName
import android.provider.Settings
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VivoPermissionIntentsTest {

    @Test
    fun bgStartUp_targets_vivo_permissionmanager() {
        val intent = VivoPermissionIntents.bgStartUp()
        assertThat(intent.component).isEqualTo(
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
        )
    }

    @Test
    fun appDetails_targets_given_package() {
        val intent = VivoPermissionIntents.appDetails("com.stock.dividend")
        assertThat(intent.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(intent.data).isEqualTo(Uri.fromParts("package", "com.stock.dividend", null))
    }

    @Test
    fun notificationSettings_targets_app_notifications() {
        val intent = VivoPermissionIntents.appNotificationSettings("com.stock.dividend")
        assertThat(intent.action).isEqualTo(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        assertThat(intent.getStringExtra(Settings.EXTRA_APP_PACKAGE)).isEqualTo("com.stock.dividend")
    }
}
