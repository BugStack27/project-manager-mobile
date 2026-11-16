package com.projectmanager.mobile.notify

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** 「去开启」这一步该做什么：弹系统授权框，还是直接跳到系统设置页。 */
enum class NotificationPrompt {
    /** 还能弹运行时授权框（Android 13+，没问过或系统仍允许再问）。 */
    RUNTIME_DIALOG,

    /** 弹不了授权框：跳系统通知设置页。 */
    SYSTEM_SETTINGS,

    /** 已经能收到通知，不需要动作。 */
    NONE
}

/**
 * 通知权限的判定与入口。
 *
 * Android 13 引入了 `POST_NOTIFICATIONS` 运行时权限：一次拒绝后系统仍可能再弹，
 * 二次拒绝（或用户选了「不再询问」）后 `launch` 会静默返回、什么都不发生；
 * Android 12 及以下根本没有这个运行时权限，通知只能由用户在系统设置里开关。
 * 所以「去开启」必须在弹不了框时落到系统设置页，而不是只调一次 launcher。
 */
object NotificationAccess {

    private const val PREFS = "notification_permission"
    private const val KEY_ASKED = "asked"
    private const val SDK_TIRAMISU = 33

    fun isEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun hasRuntimePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 是否已经主动请求过一次通知权限，用于区分「从未请求」与「已被拒绝且不再询问」。 */
    fun wasAsked(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ASKED, true)
            .apply()
    }

    /** 系统是否还愿意为这个权限弹框（「不再询问 / 二次拒绝」后为 false）。 */
    fun canAskAgain(activity: Activity?): Boolean =
        activity != null &&
            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            )

    fun decide(
        sdkInt: Int,
        runtimeGranted: Boolean,
        notificationsEnabled: Boolean,
        asked: Boolean,
        canAsk: Boolean
    ): NotificationPrompt {
        // 12 及以下没有运行时权限；13+ 已授权但应用通知被单独关掉，两者都只能去系统设置。
        if (sdkInt < SDK_TIRAMISU || runtimeGranted) {
            return if (notificationsEnabled) NotificationPrompt.NONE else NotificationPrompt.SYSTEM_SETTINGS
        }
        // 没问过，或系统仍允许再问：还能弹授权框。
        if (!asked || canAsk) return NotificationPrompt.RUNTIME_DIALOG
        // 二次拒绝后系统不再弹框，launch 只会静默返回。
        return NotificationPrompt.SYSTEM_SETTINGS
    }

    /** 打开本应用的通知设置页；逐级降级到应用详情页、系统设置首页。 */
    fun openAppNotificationSettings(context: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ),
            Intent(Settings.ACTION_SETTINGS)
        )
        return startFirstAvailable(context, intents)
    }

    /** 打开指定渠道的通知设置（渠道整体关闭 / 铃声与震动全关时用）。 */
    fun openChannelNotificationSettings(context: Context, channelId: String): Boolean =
        startFirstAvailable(
            context,
            listOf(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            )
        ) || openAppNotificationSettings(context)

    private fun startFirstAvailable(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // 该 ROM 没有这个页面，试下一个
            } catch (_: Exception) {
            }
        }
        return false
    }
}

/** 从 Compose 的 LocalContext 找到宿主 Activity。 */
fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
