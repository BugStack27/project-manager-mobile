package com.projectmanager.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.projectmanager.mobile.PmApp
import com.projectmanager.mobile.data.AppRepository
import com.projectmanager.mobile.data.ConnectionRecord
import com.projectmanager.mobile.data.RemoteApi
import com.projectmanager.mobile.data.RemoteChangeFile
import com.projectmanager.mobile.data.RemoteChanges
import com.projectmanager.mobile.data.RemoteCommand
import com.projectmanager.mobile.data.RemoteConfig
import com.projectmanager.mobile.data.RemoteDiff
import com.projectmanager.mobile.data.RemoteLogs
import com.projectmanager.mobile.data.RemoteProject
import com.projectmanager.mobile.data.RemoteRequirement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 项目 / 需求这条线的状态：走各台桌面端的 /remote 接口，与 PushGo 通道互不相干。
 *
 * 每台电脑各有一份接入点，项目按来源电脑分组：同名的两个项目也能看出分别在哪台机器上。
 * 待办由前台服务里的 RemoteWatcher 常驻长轮询，这里只负责项目 / 需求这类按需读取的页面。
 */
data class RemoteProjectEntry(
    val connectionId: String,
    val connectionName: String,
    val project: RemoteProject
)

data class RemoteDesktopState(
    val connectionId: String,
    val connectionName: String,
    val ready: Boolean = false,
    val projects: List<RemoteProject> = emptyList(),
    val loading: Boolean = false,
    val online: Boolean = false,
    val error: String? = null
)

data class RemoteState(
    val desktops: List<RemoteDesktopState> = emptyList(),
    val requirements: List<RemoteRequirement> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null
) {
    /** 至少有一台电脑已经拿到接入点 */
    val ready: Boolean get() = desktops.any { it.ready }
    val online: Boolean get() = desktops.any { it.online }
    val projectCount: Int get() = desktops.sumOf { it.projects.size }
}

class RemoteViewModel(private val app: PmApp) : ViewModel() {

    private val repo: AppRepository = app.container.repo

    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    /** 已经成功加载过项目的接入点：地址 / 令牌变了要重拉 */
    private val loadedConfigs = HashMap<String, RemoteConfig>()
    private val loadingProjects = HashSet<String>()
    private val lastAttemptAt = HashMap<String, Long>()

    private var loadedConnId: String = ""
    private var loadedProjectId: String = ""

    init {
        viewModelScope.launch {
            repo.snapshot.collect { snap -> syncDesktops(snap.connections) }
        }
    }

    private fun apiFor(connId: String): RemoteApi? =
        repo.connection(connId)?.remote?.takeIf { it.ready }?.let { RemoteApi(it) }

    /**
     * 连接列表 → 项目页的分组。
     *
     * 还没下发接入点的电脑也保留一行（提示等待配对），否则用户完全看不出这台机器为什么没有项目。
     */
    private fun syncDesktops(connections: List<ConnectionRecord>) {
        val previous = _state.value.desktops.associateBy { it.connectionId }
        val next = connections.map { conn ->
            val old = previous[conn.id]
            val ready = conn.remote.ready
            RemoteDesktopState(
                connectionId = conn.id,
                connectionName = conn.name,
                ready = ready,
                projects = if (ready) old?.projects.orEmpty() else emptyList(),
                loading = ready && old?.loading == true,
                online = ready && old?.online == true,
                error = if (ready) old?.error else null
            )
        }
        _state.update { it.copy(desktops = next) }
        for (conn in connections) {
            if (conn.remote.ready) loadProjects(conn) else loadedConfigs.remove(conn.id)
        }
    }

    fun refresh() {
        repo.connections.forEach { loadProjects(it, force = true) }
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    /**
     * 拉某一台电脑的项目列表。
     *
     * 失败后快照变化（长轮询落库、连接改名等）会再次触发，用 [lastAttemptAt] 限速，
     * 避免桌面端关着时每秒都去撞一次超时。
     */
    private fun loadProjects(connection: ConnectionRecord, force: Boolean = false) {
        if (!connection.remote.ready) return
        val connId = connection.id
        if (loadingProjects.contains(connId)) return
        if (!force && loadedConfigs[connId] == connection.remote) return
        val now = System.currentTimeMillis()
        if (!force && now - (lastAttemptAt[connId] ?: 0) < RETRY_MS) return

        lastAttemptAt[connId] = now
        loadingProjects.add(connId)
        updateDesktop(connId) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val projects = RemoteApi(connection.remote).projects()
                loadedConfigs[connId] = connection.remote
                updateDesktop(connId) {
                    it.copy(projects = projects, loading = false, online = true, error = null)
                }
            } catch (e: Exception) {
                loadedConfigs.remove(connId)
                updateDesktop(connId) {
                    it.copy(loading = false, online = false, error = e.message ?: "读取项目失败")
                }
            } finally {
                loadingProjects.remove(connId)
            }
        }
    }

    private fun updateDesktop(connId: String, transform: (RemoteDesktopState) -> RemoteDesktopState) {
        _state.update { st ->
            st.copy(desktops = st.desktops.map { if (it.connectionId == connId) transform(it) else it })
        }
    }

    fun loadRequirements(connId: String, projectId: String, force: Boolean = false) {
        if (!force && loadedConnId == connId && loadedProjectId == projectId &&
            _state.value.requirements.isNotEmpty()
        ) {
            return
        }
        val api = apiFor(connId)
        if (api == null) {
            _state.update { it.copy(requirements = emptyList(), error = "还没拿到这台电脑的地址") }
            return
        }
        loadedConnId = connId
        loadedProjectId = projectId
        _state.update { it.copy(loading = true, error = null, requirements = emptyList()) }
        viewModelScope.launch {
            try {
                val list = api.requirements(projectId)
                if (loadedConnId == connId && loadedProjectId == projectId) {
                    _state.update { it.copy(requirements = list, loading = false) }
                }
            } catch (e: Exception) {
                if (loadedConnId == connId && loadedProjectId == projectId) {
                    _state.update { it.copy(loading = false, error = e.message ?: "读取需求失败") }
                }
            }
        }
    }

    fun setStatus(connId: String, requirementId: String, status: String) {
        val api = apiFor(connId) ?: return
        viewModelScope.launch {
            try {
                api.setStatus(requirementId, status)
                _state.update { it.copy(notice = if (status == "todo") "已采纳" else "已拒绝") }
                reloadLoadedRequirements(connId)
            } catch (e: Exception) {
                _state.update { it.copy(notice = e.message ?: "操作失败") }
            }
        }
    }

    fun answer(
        connId: String,
        requirementId: String,
        decision: String,
        text: String?,
        questionId: String?,
        onResult: (Boolean) -> Unit
    ) {
        val api = apiFor(connId)
        if (api == null) {
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
                if (res.applied) reloadLoadedRequirements(connId)
                onResult(res.applied)
            } catch (e: Exception) {
                _state.update { it.copy(notice = e.message ?: "提交失败") }
                onResult(false)
            }
        }
    }

    private fun reloadLoadedRequirements(connId: String) {
        if (loadedConnId == connId && loadedProjectId.isNotEmpty()) {
            loadRequirements(connId, loadedProjectId, force = true)
        }
    }

    /* ── 只读抓取：给需求详情页按需加载，不进全局状态，避免互相覆盖 ── */

    suspend fun fetchChanges(connId: String, projectId: String): RemoteChanges =
        apiFor(connId)?.changes(projectId) ?: throw IllegalStateException("这台电脑还没接入")

    suspend fun fetchDiff(connId: String, projectId: String, file: RemoteChangeFile): RemoteDiff =
        apiFor(connId)?.diff(projectId, file.path, file.untracked)
            ?: throw IllegalStateException("这台电脑还没接入")

    suspend fun fetchCommands(connId: String, projectId: String): List<RemoteCommand> =
        apiFor(connId)?.commands(projectId) ?: throw IllegalStateException("这台电脑还没接入")

    suspend fun fetchLogs(connId: String, commandId: String): RemoteLogs =
        apiFor(connId)?.logs(commandId) ?: throw IllegalStateException("这台电脑还没接入")

    companion object {
        /** 项目列表失败后的重试间隔：桌面端关着时别每次都撞满超时 */
        private const val RETRY_MS = 30_000L

        fun factory(app: PmApp): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RemoteViewModel(app) as T
        }
    }
}
