package com.projectmanager.mobile

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.projectmanager.mobile.data.AppRepository
import com.projectmanager.mobile.data.SecureStore
import com.projectmanager.mobile.link.LinkManager
import com.projectmanager.mobile.notify.Notifier
import com.projectmanager.mobile.service.MobileLinkService

class PmApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.ensureChannels()
    }

    fun startLinkService() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MobileLinkService::class.java)
            )
        } catch (e: Exception) {
            // 后台启动前台服务在部分系统版本 / 厂商 ROM 上会被拒绝；应用下次打开时还会再试
            Log.e("PmApp", "启动后台接收服务失败", e)
        }
    }
}

class AppContainer(context: Context) {
    val repo: AppRepository = AppRepository(SecureStore(context))
    val notifier: Notifier = Notifier(context, repo)
    val linkManager: LinkManager = LinkManager(context, repo, notifier)
}
