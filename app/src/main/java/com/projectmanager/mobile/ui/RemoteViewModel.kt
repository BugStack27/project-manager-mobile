package com.projectmanager.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.projectmanager.mobile.PmApp
import com.projectmanager.mobile.data.RemoteApi
import com.projectmanager.mobile.data.RemoteChangeFile
import com.projectmanager.mobile.data.RemoteChanges
import com.projectmanager.mobile.data.RemoteCommand
import com.projectmanager.mobile.data.RemoteConfig
import com.projectmanager.mobile.data.RemoteDiff
import com.projectmanager.mobile.data.RemoteLogs
import com.projectmanager.mobile.data.RemoteProject
import com.projectmanager.mobile.data.RemoteRequirement
import com.projectmanager.mobile.data.RemoteTicket
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 项目 / 需求这条线的状态：走桌面端 /remote 接口，与 PushGo 待办互不相干。
 *
 * 待办仍由 LinkManager 走 PushGo 收：那条通道负责「桌面端主动推送」；
 * 这里的长轮询负责「手机主动要」，两者看到的是同一份待办，只是入口不同。
 */
data class RemoteState(
    val config: RemoteConfig = RemoteConfig(),
    val projects: List<RemoteProject> = emptyList(),
    val requirements: List<RemoteRequirement> = emptyList(),
    val tickets: List<RemoteTicket> = emptyList(),
    val loading: Boolean = false,
    val online: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val lastSyncAt: Long? = null
) {
    val ready: Boolean get() = config.ready
    val pendingCount: Int get() = tickets.size
}

class RemoteViewModel(private val app: PmApp) : ViewModel() {

    private val repo = app.container.repo
    private val notifier = app.container.notifier

    private val _state = MutableStateFlow(RemoteState(config = repo.snapshot.value.remote))
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    /** 已经弹过通知的待办 id：长轮询会反复拿到同一批，只提醒新增的 */
    private val notified = mutableSetOf<String>()
    private var pollJob: Job? = null
    private var loadedProject: String = ""

    init {
        viewModelScope.launch {
            repo.snapshot.collect { snap ->
                val incoming = snap.remote
                if (incoming != _state.value.config) {
                    _state.update { it.copy(config = incoming) }
                    restart()
                }
            }
        }
        restart()
    }

    private fun api(): RemoteApi? =
        _state.value.config.takeIf { it.ready }?.let { RemoteApi(it) }

    private fun restart() {
        pollJob?.cancel()
        if (!_state.value.config.ready) {
            _state.update { it.copy(tickets = emptyList(), online = false) }
            return
        }
        loadProjects()
        startPolling()
    }

    /**
     * 长轮询：挂 25 秒没变化就返回，拿到结果立刻发下一轮。
     *
     * 不用 WebSocket / SSE：公网隧道对单条 HTTP 请求有 100 秒上限，长连接会被周期性掐断；
     * 长轮询每轮都是一条普通请求，穿任何代理都安全，断网重连也只是下一轮的事。
     */
    private fun startPolling() {
        val api = api() ?: return
        pollJob = viewModelScope.launch {
            var key = ""
            while (isActive) {
                try {
                    val res = api.wait(key, 25)
                    key = res.key
                    _state.update {
                        it.copy(
                            tickets = res.messages,
                            online = true,
                            error = null,
                            lastSyncAt = System.currentTimeMillis()
                        )
                    }
                    announce(res.messages)
                } catch (e: Exception) {
                    _state.update { it.copy(online = false, error = e.message ?: "同步失败") }
                    delay(5_000)
                }
            }
        }
    }

    private fun announce(tickets: List<RemoteTicket>) {
        for (t in tickets) {
            if (t.id.isEmpty() || notified.contains(t.id)) continue
            notified.add(t.id)
            notifier.notifyRemote(
                ticketId = t.id,
                title = if (t.isReview) "待审核 · ${t.payload.projectName.ifBlank { "桌面端" }}" else "待回答 · ${t.payload.projectName.ifBlank { "桌面端" }}",
                body = buildString {
                    if (t.payload.seq != null) append("#${t.payload.seq} ")
                    append(t.payload.title)
                    val detail = if (t.isReview) t.payload.note else t.payload.text
                    if (detail.isNotBlank()) append("\n\n").append(detail)
                }
            )
        }
    }

    fun saveConfig(baseUrl: String, token: String) {
        repo.updateRemote(RemoteConfig(baseUrl = baseUrl, token = token, updatedAt = System.currentTimeMillis()))
    }

    fun clearConfig() {
        notified.clear()
        repo.updateRemote(RemoteConfig())
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun refresh() {
        loadProjects()
        viewModelScope.launch {
            val api = api() ?: return@launch
            runCatching { api.pending() }.onSuccess { res ->
                _state.update { it.copy(tickets = res.messages, online = true) }
            }
        }
    }

    fun loadProjects() {
        val api = api() ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val projects = api.projects()
                _state.update { it.copy(projects = projects, loading = false, online = true) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "读取项目失败", online = false) }
            }
        }
    }

    fun loadRequirements(projectId: String, force: Boolean = false) {
        if (!force && loadedProject == projectId && _state.value.requirements.isNotEmpty()) return
        loadedProject = projectId
        val api = api() ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, requirements = emptyList()) }
            try {
                val list = api.requirements(projectId)
                _state.update { it.copy(requirements = list, loading = false, online = true) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "读取需求失败") }
            }
        }
    }

    fun setStatus(requirementId: String, status: String) {
        val api = api() ?: return
        viewModelScope.launch {
            try {
                api.setStatus(requirementId, status)
                _state.update { it.copy(notice = if (status == "todo") "已采纳" else "已拒绝") }
                if (loadedProject.isNotEmpty()) loadRequirements(loadedProject, force = true)
            } catch (e: Exception) {
                _state.update { it.copy(notice = e.message ?: "操作失败") }
            }
        }
    }

    fun answer(
        requirementId: String,
        decision: String,
        text: String?,
        questionId: String?,
        onResult: (Boolean) -> Unit
    ) {
        val api = api() ?: run {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                val res = api.answer(requirementId, decision, text, questionId)
                val commit = res.commit
                val message = when {
                    res.applied && commit != null && commit.committed ->
                        "已通过并提交 ${commit.shortSha}" + if (commit.pushError.isNotBlank()) "（推送失败）" else ""
                    res.applied -> "已提交"
                    res.duplicate -> "这条已经处理过了"
                    else -> res.message ?: "未生效：${res.reason ?: "状态已变化"}"
                }
                _state.update { it.copy(notice = message) }
                if (res.applied && loadedProject.isNotEmpty()) loadRequirements(loadedProject, force = true)
                onResult(res.applied)
            } catch (e: Exception) {
                _state.update { it.copy(notice = e.message ?: "提交失败") }
                onResult(false)
            }
        }
    }

    /* ── 只读抓取：给需求详情页按需加载，不进全局状态，避免互相覆盖 ── */

    suspend fun fetchChanges(projectId: String): RemoteChanges =
        api()?.changes(projectId) ?: throw IllegalStateException("未配置桌面端")

    suspend fun fetchDiff(projectId: String, file: RemoteChangeFile): RemoteDiff =
        api()?.diff(projectId, file.path, file.untracked) ?: throw IllegalStateException("未配置桌面端")

    suspend fun fetchCommands(projectId: String): List<RemoteCommand> =
        api()?.commands(projectId) ?: throw IllegalStateException("未配置桌面端")

    suspend fun fetchLogs(commandId: String): RemoteLogs =
        api()?.logs(commandId) ?: throw IllegalStateException("未配置桌面端")

    companion object {
        fun factory(app: PmApp): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RemoteViewModel(app) as T
        }
    }
}
