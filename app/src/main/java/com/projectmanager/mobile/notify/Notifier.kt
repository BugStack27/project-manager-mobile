package com.projectmanager.mobile.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.projectmanager.mobile.MainActivity
import com.projectmanager.mobile.R
import com.projectmanager.mobile.core.Intents
import com.projectmanager.mobile.data.AppRepository
import com.projectmanager.mobile.data.ConnectionRecord
import com.projectmanager.mobile.data.ItemKind
import com.projectmanager.mobile.data.PendingItem
import com.projectmanager.mobile.data.REMOTE_CONN_ID

/**
 * 通知是手机端的主要入口：新审核 / 新提问各发一条，同一消息 id 只提醒一次；
 * 另有一条汇总通知，按「N 条待办 · M 台桌面端」聚合。
 */
class Notifier(
    private val context: Context,
    private val repo: AppRepository
) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val service = NotificationChannel(
            SERVICE_CHANNEL,
            "后台接收",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "接收待办提醒并保持与桌面端的连接"
            setShowBadge(false)
        }
        val todo = NotificationChannel(
            TODO_CHANNEL,
            "待审核与提问",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "桌面端有交付等待审核，或 agent 在等你回答"
            // 铃声与震动必须写在渠道上：Android 8 起通知的声音 / 震动由渠道决定，Builder 上的设置会被忽略
            setSound(alertSound(), alertAudioAttributes())
            enableVibration(true)
            vibrationPattern = TODO_VIBRATION_PATTERN
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannels(listOf(service, todo))
        // 旧渠道是无声的；已创建的渠道不允许再改铃声 / 震动，只能删掉旧渠道让新渠道生效
        runCatching { nm.deleteNotificationChannel(LEGACY_TODO_CHANNEL) }
    }

    /** 试听提醒：用与真实待办完全相同的渠道发一条通知，方便用户当场确认铃声与震动。 */
    fun notifyTest(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val notification = NotificationCompat.Builder(context, TODO_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle("提醒试听")
            .setContentText("收到新的待审核 / 待回答时，会这样响铃并震动")
            .setStyle(NotificationCompat.BigTextStyle().bigText("收到新的待审核 / 待回答时，会这样响铃并震动。\n\n如果没有声音或震动，请检查系统「勿扰 / 静音」模式与「待审核与提问」通知渠道。"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(TEST_ID))
            .build()
        return try {
            manager.notify(TEST_ID, notification)
            true
        } catch (e: SecurityException) {
            Log.w("Notifier", "通知权限缺失", e)
            false
        }
    }

    /** 渠道被系统关掉铃声与震动（或整个渠道被关闭）时，提醒用户去系统设置里恢复。 */
    fun isAlertChannelMuted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = nm.getNotificationChannel(TODO_CHANNEL) ?: return false
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return true
        return channel.sound == null && !channel.shouldVibrate()
    }

    fun serviceNotification(text: String): Notification =
        NotificationCompat.Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle("项目管理台")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent(0))
            .build()

    fun notifyItem(item: PendingItem, connection: ConnectionRecord) {
        if (!repo.settings.notificationsEnabled) return
        if (!manager.areNotificationsEnabled()) return
        if (repo.isNotified(item.key)) return

        repo.markNotified(listOf(item.key))
        val title = when (item.kind) {
            ItemKind.REVIEW -> "待审核 · ${connection.name}"
            ItemKind.QUESTION -> "待回答 · ${connection.name}"
        }
        val source = item.sourceLabel(connection.name)
        val big = when (item.kind) {
            ItemKind.REVIEW -> item.note.ifBlank { item.title }
            ItemKind.QUESTION -> item.questionText.ifBlank { item.title }
        }
        val builder = NotificationCompat.Builder(context, TODO_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle(title)
            .setContentText("$source · ${item.title}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$source\n\n$big"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_TODO)
            .setContentIntent(openItemIntent(item))
        try {
            manager.notify(notificationId(item), builder.build())
        } catch (e: SecurityException) {
            Log.w("Notifier", "通知权限缺失", e)
        }
        updateSummary()
    }

    /**
     * 远程面（HTTP 长轮询）发现的新待办。
     *
     * 与 PushGo 那条通道各自提醒、各自去重：可能同一条待办两条路都收到，但提醒内容一致、
     * 点进去都是同一个需求，重复弹一次比漏掉强。id 用待办指纹，重启后同一条不会重复提醒。
     */
    fun notifyRemote(ticketId: String, messageId: String, title: String, body: String) {
        if (!repo.settings.notificationsEnabled) return
        if (!manager.areNotificationsEnabled()) return
        val builder = NotificationCompat.Builder(context, TODO_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_TODO)
            // 点通知直接进详情：与老通道同一条深链，connId 固定 remote
            .setContentIntent(openItemIntent(REMOTE_CONN_ID, messageId))
        try {
            manager.notify(ticketId.hashCode(), builder.build())
        } catch (e: SecurityException) {
            Log.w("Notifier", "通知权限缺失", e)
        }
    }

    fun cancelItem(item: PendingItem) {        try {
            manager.cancel(notificationId(item))
        } catch (_: Exception) {
        }
        updateSummary()
    }

    fun cancelAllTodos() {
        repo.items.forEach { item ->
            try {
                manager.cancel(notificationId(item))
            } catch (_: Exception) {
            }
        }
        try {
            manager.cancel(SUMMARY_ID)
        } catch (_: Exception) {
        }
    }

    fun updateSummary() {
        val items = repo.items.filter { it.sentAt == null }
        if (!repo.settings.notificationsEnabled || items.isEmpty() || !manager.areNotificationsEnabled()) {
            try {
                manager.cancel(SUMMARY_ID)
            } catch (_: Exception) {
            }
            return
        }
        val connections = items.map { it.connId }.distinct().size
        val title = "${items.size} 条待办 · ${connections} 台桌面端"
        val lines = items.sortedByDescending { it.lastPushedAt }.take(5).map { it.title }
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.forEach { style.addLine(it) }

        val notification = NotificationCompat.Builder(context, TODO_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(style)
            .setNumber(items.size)
            .setGroup(GROUP_TODO)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(SUMMARY_ID))
            .build()
        try {
            manager.notify(SUMMARY_ID, notification)
        } catch (e: SecurityException) {
            Log.w("Notifier", "通知权限缺失", e)
        }
    }

    fun areNotificationsEnabled(): Boolean = manager.areNotificationsEnabled()

    private fun alertSound(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI

    private fun alertAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun notificationId(item: PendingItem): Int {
        val id = item.key.hashCode() and 0x7fffffff
        return if (id == SUMMARY_ID) id + 1 else id
    }

    private fun openItemIntent(item: PendingItem): PendingIntent =
        openItemIntent(item.connId, item.messageId)

    private fun openItemIntent(connId: String, messageId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intents.ACTION_OPEN_ITEM
            putExtra(Intents.EXTRA_CONN_ID, connId)
            putExtra(Intents.EXTRA_MESSAGE_ID, messageId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            // 请求码用两个 id 的稳定散列：不同待办点开各自的详情，不会复用同一个 PendingIntent
            (connId + "|" + messageId).hashCode() and 0x7fffffff,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val SERVICE_CHANNEL = "service_state"
        /** v2：v1 渠道在部分机型上被建成无声渠道，且已创建渠道无法改写铃声，故换新 id */
        const val TODO_CHANNEL = "todo_alerts_v2"
        const val LEGACY_TODO_CHANNEL = "todo_alerts"
        const val GROUP_TODO = "pm_todo"
        const val SERVICE_NOTIFICATION_ID = 1001
        const val SUMMARY_ID = 1000
        const val TEST_ID = 1002

        /** 响铃后连续两次「震 400ms、停 200ms」，避免只震一下被忽略 */
        private val TODO_VIBRATION_PATTERN = longArrayOf(0, 400, 200, 400)
    }
}
