package com.projectmanager.mobile.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationAccessTest {

    private fun decide(
        sdkInt: Int = 34,
        runtimeGranted: Boolean = false,
        notificationsEnabled: Boolean = false,
        asked: Boolean = false,
        canAsk: Boolean = false
    ) = NotificationAccess.decide(sdkInt, runtimeGranted, notificationsEnabled, asked, canAsk)

    @Test
    fun `android 12 and below always opens system settings`() {
        // 12 及以下没有运行时权限，弹框无从谈起，只能去系统设置开
        assertEquals(NotificationPrompt.SYSTEM_SETTINGS, decide(sdkInt = 31))
        assertEquals(
            NotificationPrompt.SYSTEM_SETTINGS,
            decide(sdkInt = 26, asked = true, canAsk = true)
        )
        assertEquals(
            NotificationPrompt.NONE,
            decide(sdkInt = 31, notificationsEnabled = true)
        )
    }

    @Test
    fun `android 13 first ask shows runtime dialog`() {
        assertEquals(NotificationPrompt.RUNTIME_DIALOG, decide(sdkInt = 33))
    }

    @Test
    fun `android 13 after one denial can still ask again`() {
        assertEquals(
            NotificationPrompt.RUNTIME_DIALOG,
            decide(sdkInt = 33, asked = true, canAsk = true)
        )
    }

    @Test
    fun `android 13 second denial goes to system settings`() {
        // 二次拒绝后 launch 静默返回，必须落到设置页
        assertEquals(
            NotificationPrompt.SYSTEM_SETTINGS,
            decide(sdkInt = 33, asked = true, canAsk = false)
        )
        assertEquals(
            NotificationPrompt.SYSTEM_SETTINGS,
            decide(sdkInt = 34, asked = true, canAsk = false)
        )
    }

    @Test
    fun `granted but app notifications switched off goes to system settings`() {
        assertEquals(
            NotificationPrompt.SYSTEM_SETTINGS,
            decide(sdkInt = 34, runtimeGranted = true, notificationsEnabled = false)
        )
    }

    @Test
    fun `already enabled needs no action`() {
        assertEquals(
            NotificationPrompt.NONE,
            decide(sdkInt = 34, runtimeGranted = true, notificationsEnabled = true)
        )
    }
}
