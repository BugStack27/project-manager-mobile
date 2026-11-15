package com.projectmanager.mobile.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
            description = "保持与多台桌面端的 ntfy 连接"
            setShowBadge(false)
        }
        val todo = NotificationChannel(
            TODO_CHANNEL,
            "待审核与提问",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "桌面端有交付等待审核，或 agent 在等你回答"
            enableVibration(true)
        }
        nm.createNotificationChannels(listOf(service, todo))
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

    fun cancelItem(item: PendingItem) {
        try {
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

    private fun notificationId(item: PendingItem): Int {
        val id = item.key.hashCode() and 0x7fffffff
        return if (id == SUMMARY_ID) id + 1 else id
    }

    private fun openItemIntent(item: PendingItem): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intents.ACTION_OPEN_ITEM
            putExtra(Intents.EXTRA_CONN_ID, item.connId)
            putExtra(Intents.EXTRA_MESSAGE_ID, item.messageId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            notificationId(item),
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
        const val TODO_CHANNEL = "todo_alerts"
        const val GROUP_TODO = "pm_todo"
        const val SERVICE_NOTIFICATION_ID = 1001
        const val SUMMARY_ID = 1000
    }
}
