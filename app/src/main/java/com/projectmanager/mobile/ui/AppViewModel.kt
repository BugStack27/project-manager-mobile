package com.projectmanager.mobile.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.projectmanager.mobile.PmApp
import com.projectmanager.mobile.core.ConnectInfo
import com.projectmanager.mobile.data.AddConnectionResult
import com.projectmanager.mobile.data.AppSettings
import com.projectmanager.mobile.data.ConnectionRecord
import com.projectmanager.mobile.data.ConnectionRuntime
import com.projectmanager.mobile.data.PendingItem
import com.projectmanager.mobile.data.RemoteApi
import com.projectmanager.mobile.service.MobileLinkService
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val connections: List<ConnectionRecord> = emptyList(),
    val items: List<PendingItem> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val runtimes: Map<String, ConnectionRuntime> = emptyMap(),
    val foreground: Boolean = false
)

sealed interface UiEvent {
    data class Message(val text: String, val error: Boolean = false) : UiEvent
}

class AppViewModel(private val app: PmApp) : ViewModel() {

    private val repo = app.container.repo
    private val linkManager = app.container.linkManager
    private val notifier = app.container.notifier

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    val state = combine(repo.snapshot, linkManager.runtimes, linkManager.foreground) { snapshot, runtimes, foreground ->
        UiState(
            connections = snapshot.connections,
            items = snapshot.items,
            settings = snapshot.settings,
            runtimes = runtimes,
            foreground = foreground
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UiState(
            connections = repo.connections,
            items = repo.items,
            settings = repo.settings,
            runtimes = linkManager.runtimes.value,
            foreground = linkManager.foreground.value
        )
    )

    fun addConnection(info: ConnectInfo, name: String): AddConnectionResult {
        val result = repo.addConnection(info, name)
        app.startLinkService()
        return result
    }

    fun renameConnection(id: String, name: String) {
        repo.renameConnection(id, name)
    }

    fun replaceSession(id: String, info: ConnectInfo) {
        repo.replaceSession(id, info)
        app.startLinkService()
    }

    fun removeConnection(id: String) {
        repo.itemsForConnection(id).forEach { notifier.cancelItem(it) }
        repo.removeConnection(id)
        notifier.updateSummary()
        if (repo.connections.isEmpty()) {
            app.stopService(Intent(app, MobileLinkService::class.java))
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        repo.updateSettings { it.copy(notificationsEnabled = enabled) }
        if (enabled) {
            notifier.updateSummary()
        } else {
            notifier.cancelAllTodos()
        }
    }

    fun markBatteryPromptShown() {
        repo.updateSettings { it.copy(batteryPromptShown = true) }
    }

    /** 发出与真实待办同渠道的通知，用于当场确认铃声与震动。 */
    fun testAlert() {
        notifier.ensureChannels()
        val sent = notifier.notifyTest()
        notify(
            if (sent) "已发送试听提醒：应有一次响铃与震动" else "通知权限未开启，无法试听",
            error = !sent
        )
    }

    /**
     * 提交答复：按待办来源连接回到那台桌面端的 /remote。
     *
     * 连接被移除、或那台电脑还没下发接入点时没有可用的提交通道，
     * 直接告诉用户忽略，而不是发一个必然失败的请求。
     */
    fun answer(item: PendingItem, decision: String, text: String, onResult: (Boolean) -> Unit) {
        if (repo.connection(item.connId)?.remote?.ready != true) {
            notify("这条待办来自已移除的连接，忽略即可", error = true)
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                val message = withContext(Dispatchers.IO) { submitRemote(item, decision, text) }
                _events.emit(UiEvent.Message(message))
                onResult(true)
            } catch (e: Exception) {
                _events.emit(UiEvent.Message(e.message ?: "提交失败", error = true))
                onResult(false)
            }
        }
    }

    /** 远程面提交：返回一句给用户看的回执（含提交短哈希） */
    private suspend fun submitRemote(item: PendingItem, decision: String, text: String): String {
        val config = repo.connection(item.connId)?.remote
        if (config == null || !config.ready) throw IOException("还没拿到桌面端地址，等配对完成后再试")
        val res = RemoteApi(config).answer(
            requirementId = item.requirementId,
            decision = decision,
            text = text.ifBlank { null },
            questionId = item.questionId
        )
        if (!res.applied) {
            throw IOException(res.message ?: "未生效：${res.reason ?: "状态已变化"}")
        }
        repo.markAnswerAttempt(item.connId, item.messageId, res.id, decision, text)
        repo.markAnswerSent(item.connId, item.messageId, System.currentTimeMillis())
        notifier.cancelItem(item)
        notifier.updateSummary()
        val commit = res.commit
        return when {
            commit != null && commit.committed && commit.pushError.isBlank() -> "已通过并提交 ${commit.shortSha}"
            commit != null && commit.committed -> "已通过并提交 ${commit.shortSha}（推送失败）"
            else -> "已提交"
        }
    }

    fun notify(text: String, error: Boolean = false) {
        viewModelScope.launch { _events.emit(UiEvent.Message(text, error)) }
    }

    fun refresh() {
        linkManager.refreshAll()
        notify("正在重新同步…")
    }

    companion object {
        fun factory(app: PmApp): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(app) as T
        }
    }
}
