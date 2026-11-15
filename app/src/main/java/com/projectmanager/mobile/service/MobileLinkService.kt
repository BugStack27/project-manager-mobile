package com.projectmanager.mobile.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.projectmanager.mobile.PmApp
import com.projectmanager.mobile.data.AppRepository
import com.projectmanager.mobile.link.LinkManager
import com.projectmanager.mobile.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 常驻前台服务：持有所有连接的订阅流与心跳，保证退到后台甚至息屏后仍能收到审核 / 提问。
 * 使用 remoteMessaging 类型：与「接收配对设备消息」语义一致，也允许开机后恢复。
 */
class MobileLinkService : Service() {

    private lateinit var repo: AppRepository
    private lateinit var linkManager: LinkManager
    private lateinit var notifier: Notifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as PmApp).container
        repo = container.repo
        linkManager = container.linkManager
        notifier = container.notifier
        linkManager.setForeground(true)

        try {
            ServiceCompat.startForeground(
                this,
                Notifier.SERVICE_NOTIFICATION_ID,
                notifier.serviceNotification(currentText()),
                foregroundType()
            )
        } catch (e: Exception) {
            Log.e("MobileLinkService", "进入前台失败", e)
            stopSelf()
            return
        }

        linkManager.start()
        notifier.updateSummary()
        notificationJob = scope.launch {
            combine(repo.snapshot, linkManager.runtimes) { _, _ -> currentText() }
                .distinctUntilChanged()
                .collect { updateNotification(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        linkManager.start()
        if (repo.connections.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        updateNotification(currentText())
        return START_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        scope.cancel()
        linkManager.setForeground(false)
        linkManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun currentText(): String {
        val connections = repo.connections.size
        if (connections == 0) return "暂无连接"
        val runtimes = linkManager.runtimes.value
        val online = runtimes.values.count { it.desktopOnline() }
        val items = repo.items.count { it.sentAt == null }
        return when {
            online > 0 -> "$online 台在线 · $items 条待办"
            else -> "等待桌面端心跳 · $items 条待办"
        }
    }

    private fun updateNotification(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(
                Notifier.SERVICE_NOTIFICATION_ID,
                notifier.serviceNotification(text)
            )
        } catch (e: SecurityException) {
            Log.w("MobileLinkService", "更新服务通知失败", e)
        }
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // remoteMessaging 与「接收配对设备的审核/提问」语义一致，且允许开机恢复
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
}
