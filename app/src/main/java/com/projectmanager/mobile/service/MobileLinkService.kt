package com.projectmanager.mobile.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.projectmanager.mobile.PmApp
import com.projectmanager.mobile.data.AppRepository
import com.projectmanager.mobile.link.LinkManager
import com.projectmanager.mobile.link.RemoteWatcher
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
    private lateinit var remoteWatcher: RemoteWatcher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundEntered = false

    override fun onCreate() {
        super.onCreate()
        val container = (application as PmApp).container
        repo = container.repo
        linkManager = container.linkManager
        notifier = container.notifier
        remoteWatcher = RemoteWatcher(repo, notifier)

        // 先拉起接收链路：即使这次进不了前台（后台被系统重启时可能被拒），
        // 服务仍能立刻补拉一轮并补发通知，而不是整个后台接收直接停摆。
        linkManager.start()
        // 远程面（HTTP 长轮询）与 PushGo 同生命周期：界面被回收后，这条通道仍然收得到待办
        remoteWatcher.start(scope)
        holdCpuAwake()
        notifier.updateSummary()
        enterForeground()
        linkManager.setForeground(foregroundEntered)
        notificationJob = scope.launch {
            combine(repo.snapshot, linkManager.runtimes) { _, _ -> currentText() }
                .distinctUntilChanged()
                .collect { updateNotification(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次被拉起（包括用户重新打开 App）都重试进入前台，失败过也能自己恢复
        enterForeground()
        linkManager.setForeground(foregroundEntered)
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
        remoteWatcher.stop()
        scope.cancel()
        releaseCpuAwake()
        linkManager.setForeground(false)
        linkManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Pull 轮询靠 15 秒定时器推进：息屏后系统会挂起 CPU，定时器不再触发，
     * 待办要等用户点亮屏幕 / 打开应用才被拉走，通知自然就迟到了。
     * 前台服务里持有部分唤醒锁，让轮询在息屏后继续跑。
     */
    private fun holdCpuAwake() {
        if (wakeLock != null) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseCpuAwake() {
        wakeLock?.let { runCatching { it.release() } }
        wakeLock = null
    }

    private fun currentText(): String {
        val connections = repo.connections.size
        if (connections == 0) return "暂无连接"
        val runtimes = linkManager.runtimes.value
        val online = runtimes.values.count { it.connected }
        val items = repo.items.count { it.isActionable() }
        return when {
            online > 0 -> "$online 台在线 · $items 条待办"
            else -> "连接中 · $items 条待办"
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

    /**
     * 进入前台状态。Android 12 起，进程在后台被系统重启时这一步可能被拒
     * （ForegroundServiceStartNotAllowedException）；此时不终止服务，只记日志，
     * 让轮询继续跑完这一轮、把待办通知发出去，下次 onStartCommand 再重试。
     */
    private fun enterForeground(): Boolean {
        if (foregroundEntered) return true
        return try {
            ServiceCompat.startForeground(
                this,
                Notifier.SERVICE_NOTIFICATION_ID,
                notifier.serviceNotification(currentText()),
                foregroundType()
            )
            foregroundEntered = true
            true
        } catch (e: Exception) {
            Log.e("MobileLinkService", "进入前台失败，暂以后台服务继续接收", e)
            false
        }
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // remoteMessaging 与「接收配对设备的审核/提问」语义一致，且允许开机恢复
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }

    private companion object {
        const val WAKE_LOCK_TAG = "pm-mobile:link"
    }
}
