package com.projectmanager.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.projectmanager.mobile.PmApp

/** 重启后自动恢复接收；被系统拦截时静默失败，用户下次打开应用会再次启动服务。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? PmApp ?: return
        if (app.container.repo.connections.isEmpty()) return
        try {
            app.startLinkService()
        } catch (e: Exception) {
            Log.e("BootReceiver", "开机恢复接收失败", e)
        }
    }
}
