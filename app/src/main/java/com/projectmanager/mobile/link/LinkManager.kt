package com.projectmanager.mobile.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.projectmanager.mobile.PmApp
import com.projectmanager.mobile.core.Protocol
import com.projectmanager.mobile.data.AppRepository
import com.projectmanager.mobile.data.ConnectionRecord
import com.projectmanager.mobile.data.ConnectionRuntime
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 多连接管理：每条连接一个接入点会话，网络恢复时全部立即重连。 */
class LinkManager(
    private val context: Context,
    private val repo: AppRepository
) {
    private val sessions = ConcurrentHashMap<String, TransportSession>()

    private val _runtimes = MutableStateFlow<Map<String, ConnectionRuntime>>(emptyMap())
    val runtimes: StateFlow<Map<String, ConnectionRuntime>> = _runtimes.asStateFlow()

    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    @Volatile
    private var started = false
    private var scope: CoroutineScope? = null
    private var watchJob: Job? = null

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        syncSessions(repo.connections)

        watchJob = repo.snapshot
            .onEach { syncSessions(it.connections) }
            .launchIn(newScope)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                sessions.values.forEach { it.reconnectNow() }
            }
        }
        networkCallback = callback
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w("LinkManager", "网络回调注册失败", it) }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        watchJob?.cancel()
        watchJob = null
        networkCallback?.let { callback ->
            runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        sessions.values.forEach { it.stop() }
        sessions.clear()
        _runtimes.value = emptyMap()
        scope?.cancel()
        scope = null
    }

    fun setForeground(value: Boolean) {
        _foreground.value = value
    }

    /** 远程面长轮询的结果：只改这一条连接，并刷新对应的成功/失败时间 */
    fun markRemoteReachable(id: String, reachable: Boolean) {
        val now = System.currentTimeMillis()
        _runtimes.update { current ->
            val runtime = current[id] ?: return@update current
            val updated = runtime.copy(
                remoteReachable = reachable,
                remoteOkAt = if (reachable) now else runtime.remoteOkAt,
                remoteFailAt = if (reachable) runtime.remoteFailAt else now
            )
            current + (id to updated)
        }
    }

    /** 手动重新同步：断开当前订阅，按 since=10m 重新拉取所有连接。 */
    fun refreshAll() {
        sessions.values.forEach { it.reconnectNow() }
    }

    fun summaryText(): String {
        val connections = repo.connections.size
        if (connections == 0) return "暂无连接"
        val runtimes = _runtimes.value
        val online = runtimes.values.count { it.isOnline() }
        val items = repo.items.size
        return "$online 台在线 · $items 条待办"
    }

    @Synchronized
    private fun syncSessions(connections: List<ConnectionRecord>) {
        val wanted = connections.map { it.id }.toSet()
        for (id in sessions.keys.toList()) {
            if (id !in wanted) {
                sessions.remove(id)?.stop()
                _runtimes.update { it - id }
            }
        }
        for (connection in connections) {
            val existing = sessions[connection.id]
            if (existing == null) {
                sessions[connection.id] = createSession(connection).also { it.start() }
            } else if (existing.requiresRestart(connection)) {
                existing.stop()
                sessions[connection.id] = createSession(connection).also { it.start() }
            }
        }
    }

    private fun createSession(connection: ConnectionRecord): TransportSession {
        val onRuntime: (ConnectionRuntime) -> Unit = { runtime ->
            _runtimes.update { current ->
                // PushGo 每轮都会重报运行态，远程面结论要保留，否则会被心跳覆盖回未知
                val existing = current[connection.id]
                current + (connection.id to runtime.copy(
                    remoteReachable = existing?.remoteReachable,
                    remoteOkAt = existing?.remoteOkAt,
                    remoteFailAt = existing?.remoteFailAt
                ))
            }
        }
        return PushGoSession(connection, repo, onRuntime)
    }
}

interface TransportSession {
    fun start()
    fun stop()
    fun reconnectNow()
    fun requiresRestart(other: ConnectionRecord): Boolean
}
