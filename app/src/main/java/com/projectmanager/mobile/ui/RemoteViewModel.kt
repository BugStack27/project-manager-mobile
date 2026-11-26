package com.projectmanager.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.projectmanager.mobile.PmApp
import com.projectmanager.mobile.data.AppRepository
import com.projectmanager.mobile.data.ConnectionRecord
import com.projectmanager.mobile.data.ConnectionRuntime
import com.projectmanager.mobile.data.RemoteApi
import com.projectmanager.mobile.data.RemoteChangeFile
import com.projectmanager.mobile.data.RemoteChanges
import com.projectmanager.mobile.data.RemoteCommand
import com.projectmanager.mobile.data.RemoteConfig
import com.projectmanager.mobile.data.RemoteDiff
import com.projectmanager.mobile.data.RemoteLogs
import com.projectmanager.mobile.data.RemoteProject
import com.projectmanager.mobile.data.RemoteRequirement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /** 页面标题用的统一结论：已连接 / 等待桌面端 / 桌面端不可达 / 频道失败原因 */
    val status: String = "连接中",
    val error: String? = null
)

/** 跨项目的未完成需求：带上所属电脑与项目，列表里直接说明来源 */
data class RemoteUnfinishedEntry(
    val connectionId: String,
    val connectionName: String,
    val projectId: String,
    val projectName: String,
    val requirement: RemoteRequirement
)

data class RemoteState(
    val desktops: List<RemoteDesktopState> = emptyList(),
    val requirements: List<RemoteRequirement> = emptyList(),
    val loading: Boolean = false,
    /** 当前项目的需求列表成功加载过至少一次：用来区分「加载中 / 加载失败」与「真的没有了」 */
    val requirementsLoaded: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** 跨项目的未完成需求（未开始 / 进行中 / 待审核），进「未完成」视图时按需拉取 */
    val unfinished: List<RemoteUnfinishedEntry> = emptyList(),
    val unfinishedLoading: Boolean = false,
    val unfinishedLoaded: Boolean = false,
    val unfinishedError: String? = null
) {
    /** 至少有一台电脑已经拿到接入点 */
    val ready: Boolean get() = desktops.any { it.ready }
    val online: Boolean get() = desktops.any { it.online }
    val projectCount: Int get() = desktops.sumOf { it.projects.size }
}

class RemoteViewModel(private val app: PmApp) : ViewModel() {

    private val repo: AppRepository = app.container.repo
    private val linkManager = app.container.linkManager

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
            combine(repo.snapshot, linkManager.runtimes) { snap, runtimes ->
                snap.connections to runtimes
            }.collect { (connections, runtimes) -> syncDesktops(connections, runtimes) }
        }
    }

    private fun apiFor(connId: String): RemoteApi? =
        repo.connection(connId)?.remote?.takeIf { it.ready }?.let { RemoteApi(it) }

    /**
     * 连接列表 → 项目页的分组。
     *
     * 还没下发接入点的电脑也保留一行（提示等待配对），否则用户完全看不出这台机器为什么没有项目。
     */
    private fun syncDesktops(
        connections: List<ConnectionRecord>,
        runtimes: Map<String, ConnectionRuntime>
    ) {
        val previous = _state.value.desktops.associateBy { it.connectionId }
        val next = connections.map { conn ->
            val old = previous[conn.id]
            val ready = conn.remote.ready
            val runtime = runtimes[conn.id]
            val online = ready && runtime?.isOnline() == true
            val status = when {
                !ready -> "等待地址"
                online -> "已连接"
                runtime?.remoteReachable == false -> "桌面端不可达"
                runtime?.connected == true -> runtime.statusText
                runtime?.failure != null -> runtime.statusText
                else -> "连接中"
            }
            RemoteDesktopState(
                connectionId = conn.id,
                connectionName = conn.name,
                ready = ready,
                projects = if (ready) old?.projects.orEmpty() else emptyList(),
                loading = ready && old?.loading == true,
                online = online,
                status = status,
                error = when {
                    !ready -> null
                    runtime?.remoteReachable == false -> "桌面端暂不可达"
                    online -> null
                    else -> old?.error
                }
            )
        }
        _state.update {
            val liveIds = connections.map { conn -> conn.id }.toSet()
            it.copy(
                desktops = next,
                unfinished = it.unfinished.filter { entry -> entry.connectionId in liveIds }
            )
        }
        for (conn in connections) {
            if (conn.remote.ready) loadProjects(conn) else loadedConfigs.remove(conn.id)
        }
    }

    fun refresh() {
        repo.connections.forEach { loadProjects(it, force = true) }
    }

    /**
     * 拉取所有电脑、所有项目的未完成需求（`pending=1`，即未开始 / 进行中 / 待审核）。
     *
     * 每台电脑一个协程并行，项目之间串行：项目多时请求数就是项目数，但不会把一条隧道打满。
     * 单个项目失败只记为这台电脑的部分失败，其余结果照常展示 —— 「看不到全部」比「一个失败就整页空白」有用。
     */
    fun loadUnfinished() {
        if (_state.value.unfinishedLoading) return
        val desktops = _state.value.desktops.filter { it.ready }
        if (desktops.isEmpty()) {
            _state.update {
                it.copy(
                    unfinished = emptyList(),
                    unfinishedLoading = false,
                    unfinishedLoaded = false,
                    unfinishedError = null
                )
            }
            return
        }
        _state.update { it.copy(unfinishedLoading = true, unfinishedError = null) }
        viewModelScope.launch {
            val results = coroutineScope {
                desktops.map { desktop -> async { fetchUnfinished(desktop) } }.awaitAll()
            }
            val entries = results.flatMap { it.entries }
                .distinctBy { "${it.connectionId}|${it.requirement.id}" }
            val errors = results.mapNotNull { it.error }
            _state.update {
                it.copy(
                    unfinished = sortedUnfinished(entries),
                    unfinishedLoading = false,
                    unfinishedLoaded = true,
                    unfinishedError = errors.takeIf { list -> list.isNotEmpty() }?.joinToString("；")
                )
            }
        }
    }

    private class DesktopUnfinished(
        val entries: List<RemoteUnfinishedEntry>,
        val error: String?
    )

    private suspend fun fetchUnfinished(desktop: RemoteDesktopState): DesktopUnfinished {
        val api = apiFor(desktop.connectionId)
            ?: return DesktopUnfinished(emptyList(), "${desktop.connectionName}：还没拿到远程地址")
        if (desktop.projects.isEmpty()) {
            return DesktopUnfinished(emptyList(), "${desktop.connectionName}：项目列表还没加载")
        }
        val entries = mutableListOf<RemoteUnfinishedEntry>()
        var failed = 0
        var firstMessage = ""
        for (project in desktop.projects) {
            try {
                api.requirements(project.id, pendingOnly = true).forEach { req ->
                    entries += RemoteUnfinishedEntry(
                        connectionId = desktop.connectionId,
                        connectionName = desktop.connectionName,
                        projectId = project.id,
                        projectName = project.name.ifBlank { "未命名项目" },
                        requirement = req
                    )
                }
            } catch (e: Exception) {
                failed++
                if (firstMessage.isBlank()) firstMessage = e.message ?: "读取失败"
            }
        }
        val error = if (failed == 0) null else "${desktop.connectionName}：$failed 个项目读取失败（$firstMessage）"
        return DesktopUnfinished(entries, error)
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
                    it.copy(projects = projects, loading = false, error = null)
                }
            } catch (e: Exception) {
                loadedConfigs.remove(connId)
                updateDesktop(connId) {
                    it.copy(loading = false, error = e.message ?: "读取项目失败")
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
            _state.update {
                it.copy(
                    requirements = emptyList(),
                    loading = false,
                    requirementsLoaded = false,
                    error = "还没拿到这台电脑的地址"
                )
            }
            return
        }
        val switched = loadedConnId != connId || loadedProjectId != projectId
        loadedConnId = connId
        loadedProjectId = projectId
        // 同一项目重载（采纳 / 拒绝 / 回答之后）保留旧列表：详情页在刷新期间仍能按 id 取到需求，
        // 不会先被清空而误报「需求已不存在」。只有换项目才清空，避免看到别的项目的需求。
        _state.update {
            it.copy(
                loading = true,
                error = null,
                requirements = if (switched) emptyList() else it.requirements,
                requirementsLoaded = if (switched) false else it.requirementsLoaded
            )
        }
        viewModelScope.launch {
            try {
                val list = api.requirements(projectId)
                if (loadedConnId == connId && loadedProjectId == projectId) {
                    _state.update {
                        it.copy(requirements = list, loading = false, requirementsLoaded = true, error = null)
                    }
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

/**
 * 未完成需求的展示顺序：待审核最靠前（停在用户手上），其次进行中，最后未开始；
 * 同档按项目名、编号倒序，跨项目时也有稳定顺序。
 */
internal fun sortedUnfinished(entries: List<RemoteUnfinishedEntry>): List<RemoteUnfinishedEntry> {
    val rank = mapOf("review" to 0, "doing" to 1, "todo" to 2)
    return entries.sortedWith(
        compareBy<RemoteUnfinishedEntry> { rank[it.requirement.status] ?: 3 }
            .thenBy { it.projectName }
            .thenByDescending { it.requirement.seq ?: 0 }
    )
}
