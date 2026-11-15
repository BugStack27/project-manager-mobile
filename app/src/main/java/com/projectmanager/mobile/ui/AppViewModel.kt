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
import com.projectmanager.mobile.service.MobileLinkService
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

    fun answer(item: PendingItem, decision: String, text: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    linkManager.sendAnswer(item, decision, text)
                }
                val name = repo.connection(item.connId)?.name ?: "桌面端"
                _events.emit(UiEvent.Message("已提交给 $name"))
                onResult(true)
            } catch (e: Exception) {
                _events.emit(UiEvent.Message(e.message ?: "提交失败", error = true))
                onResult(false)
            }
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
