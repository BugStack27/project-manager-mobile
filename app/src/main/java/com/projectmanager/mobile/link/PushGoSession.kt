package com.projectmanager.mobile.link

import com.projectmanager.mobile.core.Protocol
import com.projectmanager.mobile.data.AppRepository
import com.projectmanager.mobile.data.ConnectionRecord
import com.projectmanager.mobile.data.ConnectionRuntime
import com.projectmanager.mobile.data.PendingItem
import com.projectmanager.mobile.notify.Notifier
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * PushGo Pull 版会话：不维持长连接，每 15 秒拉一次消息，处理后按 delivery_id ACK。
 * 消息仍走既有 AES-GCM 信封，信封放在 PushGo 的 ciphertext 字段里。
 */
class PushGoSession(
    private val connection: ConnectionRecord,
    private val repo: AppRepository,
    private val notifier: Notifier,
    private val onRuntime: (ConnectionRuntime) -> Unit
) : TransportSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = PushGoClient()
    private val answerMutex = Mutex()

    @Volatile
    private var deviceKey: String? = connection.deviceKey

    @Volatile
    private var providerToken: String? = connection.providerToken

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

    private var routeReady = false
    private var subscribed = false
    private val startedAt = System.currentTimeMillis()
    private val seen = LinkedHashMap<String, Long>()

    override fun requiresRestart(other: ConnectionRecord): Boolean =
        other.provider != connection.provider ||
            other.server != connection.server || other.topic != connection.topic || other.key != connection.key

    override fun start() {
        scope.launch { loop() }
        scope.launch { keepaliveLoop() }
        publishRuntime()
    }

    override fun stop() {
        scope.cancel()
    }

    override fun reconnectNow() {
        // Pull 是短请求，下一轮自动重试；无需中断长连接
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
            ensureReady()
            val answerId = item.answerId?.takeIf { Protocol.isValidAnswerId(it) } ?: Protocol.newAnswerId()
            repo.markAnswerAttempt(item.connId, item.messageId, answerId, decision, trimmed)
            val message = buildAnswerMessage(item, answerId, decision, trimmed)
            try {
                publish(message, answerId)
                repo.markAnswerSent(item.connId, item.messageId, System.currentTimeMillis())
                notifier.cancelItem(item)
                notifier.updateSummary()
            } catch (e: Exception) {
                repo.markAnswerError(item.connId, item.messageId, describe(e))
                throw e
            }
        }
    }

    private suspend fun loop() {
        var backoff = RECONNECT_MIN_MS
        while (currentCoroutineContext().isActive) {
            try {
                ensureReady()
                connected = true
                lastError = null
                reconnectAt = null
                backoff = RECONNECT_MIN_MS
                publishRuntime()
                while (currentCoroutineContext().isActive) {
                    pollOnce()
                    delay(POLL_MS)
                }
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) break
                connected = false
                lastError = describe(e)
                reconnectAt = System.currentTimeMillis() + backoff
                publishRuntime()
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
            }
        }
    }

    private fun ensureReady() {
        if (connection.server.isBlank() || connection.topic.isBlank()) {
            throw IOException("PushGo 连接信息不完整")
        }
        val gateway = connection.server
        if (deviceKey.isNullOrBlank()) {
            deviceKey = client.registerDevice(gateway, deviceKey)
            repo.setPushGoDevice(connection.id, deviceKey!!, currentProviderToken())
        }
        if (providerToken.isNullOrBlank()) {
            providerToken = newProviderToken()
            repo.setPushGoDevice(connection.id, deviceKey!!, providerToken!!)
            routeReady = false
        }
        if (!routeReady) {
            client.ensureFcmRoute(gateway, deviceKey!!, providerToken!!)
            routeReady = true
        }
        if (!subscribed) {
            client.subscribe(gateway, deviceKey!!, connection.topic, connection.key)
            subscribed = true
        }
    }

    private fun currentProviderToken(): String {
        val existing = providerToken
        if (!existing.isNullOrBlank()) return existing
        val created = newProviderToken()
        providerToken = created
        return created
    }

    private fun newProviderToken(): String = "pm-pull-" + Protocol.randomToken(24)

    private fun pollOnce() {
        val gateway = connection.server
        val key = deviceKey ?: throw IOException("PushGo 设备尚未注册")
        val items = client.pull(gateway, key)
        if (items.isEmpty()) return

        val ackIds = ArrayList<String>(items.size)
        val now = System.currentTimeMillis()
        for (item in items) {
            try {
                val ciphertext = item.payload["ciphertext"]
                val message = ciphertext?.let { Protocol.open(connection.topic, connection.key, it) }
                if (message == null) {
                    ackIds += item.deliveryId
                    continue
                }
                val messageId = message.str("id")
                if (messageId.isNullOrBlank()) {
                    ackIds += item.deliveryId
                    continue
                }
                if (seen.containsKey(messageId)) {
                    ackIds += item.deliveryId
                    continue
                }
                rememberSeen(messageId, now)

                when (message.str("from")) {
                    "server" -> {
                        lastInboundAt = now
                        when (message.str("type")) {
                            "review", "question" -> {
                                val result = repo.upsertIncoming(connection.id, message)
                                publishRuntime()
                                if (result != null && result.isNew && !result.item.isAnswered) {
                                    notifier.notifyItem(
                                        result.item,
                                        repo.connection(connection.id) ?: connection
                                    )
                                }
                            }

                            "keepalive" -> {
                                val payload = message["payload"] as? JsonObject
                                pending = (payload?.get("pending") as? JsonPrimitive)?.intOrNull ?: pending
                                val resolved = payload?.get("resolvedIds") as? JsonArray
                                if (resolved != null) {
                                    val keys = resolved.mapNotNull { element ->
                                        (element as? JsonPrimitive)?.contentOrNull
                                    }.map { "${connection.id}|$it" }
                                    val removed = repo.removeItems(keys)
                                    removed.forEach { notifier.cancelItem(it) }
                                    if (removed.isNotEmpty()) notifier.updateSummary()
                                }
                                publishRuntime()
                            }
                        }
                    }

                    "client" -> {
                        // 自己发出的 answer/keepalive 会广播回本设备，过滤即可
                    }
                }
                ackIds += item.deliveryId
            } catch (e: Exception) {
                lastError = describe(e)
                ackIds += item.deliveryId
            }
        }
        if (ackIds.isNotEmpty()) client.ack(gateway, key, ackIds)
    }

    private suspend fun keepaliveLoop() {
        while (currentCoroutineContext().isActive) {
            delay(Protocol.KEEPALIVE_MS)
            if (!connected) continue
            try {
                val keepaliveId = Protocol.newKeepaliveId("client")
                val message = buildJsonObject {
                    put("v", Protocol.MESSAGE_VERSION)
                    put("type", "keepalive")
                    put("from", "client")
                    put("id", keepaliveId)
                    put("at", Protocol.nowIso())
                    put("payload", buildJsonObject { put("online", true) })
                }
                publish(message, keepaliveId)
                lastPublishAt = System.currentTimeMillis()
                publishRuntime()
                retryFailedAnswers()
            } catch (e: Exception) {
                lastError = describe(e)
                publishRuntime()
            }
        }
    }

    private suspend fun retryFailedAnswers() {
        repo.itemsForConnection(connection.id)
            .filter { it.answerError != null && it.answerId != null && it.answeredDecision != null }
            .forEach { item ->
                try {
                    sendAnswer(item, item.answeredDecision!!, item.answerText)
                } catch (_: Exception) {
                    // 保持错误状态，下一轮再试
                }
            }
    }

    private suspend fun publish(message: JsonObject, opId: String) {
        ensureReady()
        val ciphertext = Protocol.seal(connection.topic, connection.key, message)
        val metadata = buildJsonObject {
            put("from", message.str("from") ?: "")
            put("kind", message.str("type") ?: "")
        }
        client.send(
            gateway = connection.server,
            channelId = connection.topic,
            password = connection.key,
            title = "项目管理台",
            body = "",
            ciphertext = ciphertext,
            opId = opId,
            metadata = metadata
        )
    }

    private fun buildAnswerMessage(item: PendingItem, answerId: String, decision: String, text: String): JsonObject =
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

    private fun rememberSeen(id: String, at: Long) {
        seen[id] = at
        while (seen.size > SEEN_MAX) {
            seen.remove(seen.keys.first())
        }
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

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun describe(e: Exception): String {
        val message = e.message?.takeIf { it.isNotBlank() }
        return message ?: e.javaClass.simpleName
    }

    private companion object {
        const val POLL_MS = 15_000L
        const val RECONNECT_MIN_MS = 2000L
        const val RECONNECT_MAX_MS = 30000L
        const val SEEN_MAX = 500
    }
}
