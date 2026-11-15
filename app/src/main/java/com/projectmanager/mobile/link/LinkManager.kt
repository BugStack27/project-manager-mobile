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
import com.projectmanager.mobile.data.PendingItem
import com.projectmanager.mobile.notify.Notifier
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call

/** 多连接管理：每条连接一个传输会话，网络恢复时全部立即重连。 */
class LinkManager(
    private val context: Context,
    private val repo: AppRepository,
    private val notifier: Notifier
) {
    private val ntfy = NtfyClient()
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

    /** 手动重新同步：断开当前订阅，按 since=10m 重新拉取所有连接。 */
    fun refreshAll() {
        sessions.values.forEach { it.reconnectNow() }
    }

    fun summaryText(): String {
        val connections = repo.connections.size
        if (connections == 0) return "暂无连接"
        val runtimes = _runtimes.value
        val online = runtimes.values.count { it.desktopOnline() }
        val items = repo.items.size
        return "$online 台在线 · $items 条待办"
    }

    suspend fun sendAnswer(item: PendingItem, decision: String, text: String) {
        var session = sessions[item.connId]
        if (session == null) {
            (context.applicationContext as? PmApp)?.startLinkService()
            withTimeoutOrNull(3000) {
                while (sessions[item.connId] == null) delay(50)
            }
            session = sessions[item.connId]
        }
        val ready = session ?: throw IOException("连接尚未就绪，请稍后重试")
        ready.sendAnswer(item, decision, text)
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
            _runtimes.update { it + (connection.id to runtime) }
        }
        return if (connection.isPushGo) {
            PushGoSession(connection, repo, notifier, onRuntime)
        } else {
            ConnectionSession(connection, repo, notifier, ntfy, onRuntime)
        }
    }
}

interface TransportSession {
    fun start()
    fun stop()
    fun reconnectNow()
    fun requiresRestart(other: ConnectionRecord): Boolean
    suspend fun sendAnswer(item: PendingItem, decision: String, text: String)
}

/**
 * 单条桌面端连接：订阅 NDJSON、解信封、落待办、回答案、发心跳。
 * 服务端每 60 秒重发待推送消息，所以心跳和答案重发都挂在同一个节奏上。
 */
class ConnectionSession(
    private val connection: ConnectionRecord,
    private val repo: AppRepository,
    private val notifier: Notifier,
    private val ntfy: NtfyClient,
    private val onRuntime: (ConnectionRuntime) -> Unit
) : TransportSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val answerMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var call: Call? = null

    @Volatile
    private var connected = false

    @Volatile
    private var lastInboundAt: Long? = null

    @Volatile
    private var lastPublishAt: Long? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var pending = 0

    @Volatile
    private var reconnectAt: Long? = null

    private val startedAt = System.currentTimeMillis()

    override fun requiresRestart(other: ConnectionRecord): Boolean =
        other.provider != connection.provider ||
            other.server != connection.server || other.topic != connection.topic || other.key != connection.key

    override fun start() {
        scope.launch { connectLoop() }
        scope.launch { keepaliveLoop() }
        scope.launch { cleanupLoop() }
        publishRuntime()
    }

    override fun stop() {
        scope.cancel()
        call?.cancel()
    }

    override fun reconnectNow() {
        call?.cancel()
    }

    override suspend fun sendAnswer(item: PendingItem, decision: String, text: String) {
        if (!Protocol.isValidDecision(decision)) throw IOException("未知操作")
        val trimmed = text.trim()
        if (trimmed.length > Protocol.TEXT_MAX) throw IOException("内容过长（上限 ${Protocol.TEXT_MAX} 字符）")
        if (decision == "answer") {
            if (!Protocol.isValidQuestionId(item.questionId)) throw IOException("缺少合法的提问 id")
            if (trimmed.isEmpty()) throw IOException("答案内容不能为空")
        }
        if (decision == "feedback" && trimmed.isEmpty()) throw IOException("打回意见不能为空")

        answerMutex.withLock {
            val answerId = item.answerId?.takeIf { Protocol.isValidAnswerId(it) } ?: Protocol.newAnswerId()
            repo.markAnswerAttempt(item.connId, item.messageId, answerId, decision, trimmed)
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    publishMessage(answerMessage(item, answerId, decision, trimmed))
                }
                repo.markAnswerSent(item.connId, item.messageId, System.currentTimeMillis())
                notifier.cancelItem(item)
                notifier.updateSummary()
            } catch (e: Exception) {
                Log.e("ConnectionSession", "发送答案失败", e)
                repo.markAnswerError(item.connId, item.messageId, describe(e))
                throw e
            }
        }
    }

    private suspend fun connectLoop() {
        var backoff = RECONNECT_MIN_MS
        while (currentCoroutineContext().isActive) {
            var error: String? = null
            try {
                val request = ntfy.subscribeRequest(connection.server, connection.topic)
                val currentCall = ntfy.client.newCall(request)
                call = currentCall
                currentCall.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("ntfy 订阅失败：HTTP ${response.code}")
                    }
                    connected = true
                    lastError = null
                    reconnectAt = null
                    backoff = RECONNECT_MIN_MS
                    publishRuntime()

                    val source = response.body?.source() ?: throw IOException("ntfy 响应为空")
                    while (currentCoroutineContext().isActive) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isNotBlank()) handleLine(line)
                    }
                }
                error = "连接已断开，正在重连"
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    error = e.message ?: e.javaClass.simpleName
                }
            } finally {
                call = null
                connected = false
                if (error != null) lastError = error
                publishRuntime()
            }
            if (!currentCoroutineContext().isActive) break

            reconnectAt = System.currentTimeMillis() + backoff
            publishRuntime()
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
        }
    }

    private fun handleLine(line: String) {
        val event = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        if (event.str("event") != "message") return
        val text = event.str("message") ?: return
        if (text.trimStart().firstOrNull() != '{') return

        val message = Protocol.open(connection.topic, connection.key, text) ?: return
        if (message.str("from") != "server") return

        lastInboundAt = System.currentTimeMillis()
        when (message.str("type")) {
            "keepalive" -> {
                val payload = message["payload"] as? JsonObject
                pending = (payload?.get("pending") as? JsonPrimitive)?.intOrNull ?: pending
                publishRuntime()
                if (pending == 0) {
                    val removed = repo.removeAllItems(connection.id)
                    removed.forEach { notifier.cancelItem(it) }
                    if (removed.isNotEmpty()) notifier.updateSummary()
                }
            }

            "review", "question" -> {
                val result = repo.upsertIncoming(connection.id, message) ?: return
                publishRuntime()
                if (result.isNew && !result.item.isAnswered) {
                    val current = repo.connection(connection.id) ?: connection
                    notifier.notifyItem(result.item, current)
                }
                if (result.needsResend) {
                    scope.launch { resendAnswer(result.item.connId, result.item.messageId) }
                }
            }
        }
    }

    private suspend fun keepaliveLoop() {
        while (currentCoroutineContext().isActive) {
            delay(Protocol.KEEPALIVE_MS)
            if (!connected) continue
            try {
                publishMessage(
                    buildJsonObject {
                        put("v", Protocol.MESSAGE_VERSION)
                        put("type", "keepalive")
                        put("from", "client")
                        put("id", Protocol.newKeepaliveId("client"))
                        put("at", Protocol.nowIso())
                        put("payload", buildJsonObject { put("online", true) })
                    }
                )
                lastPublishAt = System.currentTimeMillis()
                publishRuntime()
            } catch (e: Exception) {
                lastError = "心跳发送失败：${e.message}"
                publishRuntime()
                reconnectNow()
            }
        }
    }

    private suspend fun cleanupLoop() {
        while (currentCoroutineContext().isActive) {
            delay(Protocol.KEEPALIVE_MS)
            val inbound = lastInboundAt
            if (connected && inbound != null &&
                System.currentTimeMillis() - inbound <= Protocol.CLIENT_ONLINE_MS
            ) {
                val removed = repo.removeStale(connection.id, System.currentTimeMillis() - ITEM_STALE_MS)
                removed.forEach { notifier.cancelItem(it) }
                if (removed.isNotEmpty()) notifier.updateSummary()
            }
            publishRuntime()
        }
    }

    private suspend fun resendAnswer(connId: String, messageId: String) {
        answerMutex.withLock {
            val item = repo.findItem(connId, messageId) ?: return
            val decision = item.answeredDecision ?: return
            val answerId = item.answerId ?: return
            if (!Protocol.isValidAnswerId(answerId)) return
            val sentAt = item.sentAt
            if (sentAt != null && System.currentTimeMillis() - sentAt < Protocol.KEEPALIVE_MS) return
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    publishMessage(answerMessage(item, answerId, decision, item.answerText))
                }
                repo.markAnswerSent(connId, messageId, System.currentTimeMillis())
                notifier.cancelItem(item)
                notifier.updateSummary()
            } catch (e: Exception) {
                Log.e("ConnectionSession", "重发答案失败", e)
                repo.markAnswerError(connId, messageId, describe(e))
            }
        }
    }

    private fun answerMessage(item: PendingItem, answerId: String, decision: String, text: String): JsonObject =
        buildJsonObject {
            put("v", Protocol.MESSAGE_VERSION)
            put("type", "answer")
            put("from", "client")
            put("id", answerId)
            put("at", Protocol.nowIso())
            put(
                "payload",
                buildJsonObject {
                    put("requirementId", item.requirementId)
                    if (decision == "answer") put("questionId", item.questionId)
                    put("decision", decision)
                    if (text.isNotEmpty()) put("text", text)
                }
            )
        }

    private fun publishMessage(message: JsonObject) {
        val sealed = Protocol.seal(connection.topic, connection.key, message)
        ntfy.publish(connection.server, connection.topic, sealed)
    }

    private fun publishRuntime() {
        onRuntime(
            ConnectionRuntime(
                connected = connected,
                lastInboundAt = lastInboundAt,
                lastError = lastError,
                pending = pending,
                sessionStartedAt = startedAt,
                lastPublishAt = lastPublishAt,
                reconnectAt = reconnectAt
            )
        )
    }

    private fun JsonObject.str(key: String): String? {
        val element = this[key] ?: return null
        return (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private fun describe(e: Exception): String {
        val message = e.message?.takeIf { it.isNotBlank() }
        return message ?: e.javaClass.simpleName
    }

    private companion object {
        const val RECONNECT_MIN_MS = 2000L
        const val RECONNECT_MAX_MS = 30000L
        const val ITEM_STALE_MS = 180_000L
    }
}
