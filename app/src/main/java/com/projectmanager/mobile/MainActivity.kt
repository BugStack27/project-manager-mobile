package com.projectmanager.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.projectmanager.mobile.core.Intents
import com.projectmanager.mobile.notify.NotificationAccess
import com.projectmanager.mobile.ui.AppRoot
import com.projectmanager.mobile.ui.OpenTarget
import com.projectmanager.mobile.ui.PmTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val openTarget = MutableStateFlow<OpenTarget?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            PmTheme {
                AppRoot(
                    app = application as PmApp,
                    openTarget = openTarget,
                    onConsumeTarget = { openTarget.value = null }
                )
            }
        }
        requestNotificationsIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * 前台服务只能在前台启动：在 Application.onCreate 里启动会被系统以
     * ForegroundServiceStartNotAllowedException 拒绝，进程重建后服务就起不来。
     * 放到 Activity 前台时再拉起，失败时下次回到前台还会重试。
     */
    override fun onStart() {
        super.onStart()
        val app = application as PmApp
        if (app.container.repo.connections.isNotEmpty()) {
            app.startLinkService()
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intents.ACTION_OPEN_ITEM) return
        val connId = intent.getStringExtra(Intents.EXTRA_CONN_ID)
        val messageId = intent.getStringExtra(Intents.EXTRA_MESSAGE_ID)
        if (!connId.isNullOrEmpty() && !messageId.isNullOrEmpty()) {
            openTarget.value = OpenTarget(connId, messageId)
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val app = application as PmApp
        if (app.container.repo.connections.isEmpty()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // 记一次「已请求过」：二次拒绝后系统不再弹框，页面里的「去开启」据此直接跳系统设置
            NotificationAccess.markAsked(this)
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
