package com.projectmanager.mobile.data

import com.projectmanager.mobile.core.ConnectInfo
import com.projectmanager.mobile.core.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

class AppRepository(private val store: SecureStore) {

    private val _snapshot = MutableStateFlow(store.load())
    val snapshot: StateFlow<StoreSnapshot> = _snapshot.asStateFlow()

    val connections: List<ConnectionRecord> get() = _snapshot.value.connections
    val items: List<PendingItem> get() = _snapshot.value.items
    val settings: AppSettings get() = _snapshot.value.settings

    @Synchronized
    fun addConnection(info: ConnectInfo, name: String): AddConnectionResult {
        val current = _snapshot.value
        val existing = current.connections.firstOrNull {
            it.server == info.server && it.topic == info.topic && it.key == info.key
        }
        if (existing != null) return AddConnectionResult(existing, true)

        val record = ConnectionRecord(
            id = "c_" + Protocol.randomToken(12),
            name = name.trim().ifEmpty { "桌面端 ${current.connections.size + 1}" },
            server = info.server,
            topic = info.topic,
            key = info.key,
            addedAt = System.currentTimeMillis(),
            provider = info.provider
        )
        update(current.copy(connections = current.connections + record))
        return AddConnectionResult(record, false)
    }

    @Synchronized
    fun removeConnection(id: String) {
        val current = _snapshot.value
        update(
            current.copy(
                connections = current.connections.filterNot { it.id == id },
                items = current.items.filterNot { it.connId == id },
                notified = current.notified.filterNot { it.startsWith("$id|") }
            )
        )
    }

    @Synchronized
    fun renameConnection(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val current = _snapshot.value
        update(
            current.copy(
                connections = current.connections.map {
                    if (it.id == id) it.copy(name = trimmed) else it
                }
            )
        )
    }

    @Synchronized
    fun replaceSession(id: String, info: ConnectInfo) {
        val current = _snapshot.value
        update(
            current.copy(
                connections = current.connections.map {
                    if (it.id != id) return@map it
                    val resetDevice = it.provider != info.provider || it.server != info.server
                    it.copy(
                        provider = info.provider,
                        server = info.server,
                        topic = info.topic,
                        key = info.key,
                        deviceKey = if (resetDevice) null else it.deviceKey,
                        providerToken = if (resetDevice) null else it.providerToken
                    )
                }
            )
        )
    }

    @Synchronized
    fun setPushGoDevice(id: String, deviceKey: String, providerToken: String) {
        val current = _snapshot.value
        update(
            current.copy(
                connections = current.connections.map {
                    if (it.id == id) it.copy(deviceKey = deviceKey, providerToken = providerToken) else it
                }
            )
        )
    }

    @Synchronized
    fun upsertIncoming(connId: String, message: JsonObject): UpsertResult? {
        val type = message.optString("type") ?: return null
        val messageId = message.optString("id") ?: return null
        val payload = (message["payload"] as? JsonObject) ?: return null
        val requirementId = payload.optString("requirementId") ?: return null
        if (!Protocol.isValidRequirementId(requirementId)) return null

        val kind: ItemKind
        val questionId: String?
        when (type) {
            "review" -> {
                kind = ItemKind.REVIEW
                questionId = null
            }
            "question" -> {
                kind = ItemKind.QUESTION
                questionId = payload.optString("questionId")
                if (!Protocol.isValidQuestionId(questionId)) return null
            }
            else -> return null
        }

        val now = System.currentTimeMillis()
        val existing = items.firstOrNull { it.connId == connId && it.messageId == messageId }
        val seq = (payload["seq"] as? JsonPrimitive)?.intOrNull
        val options = (payload["options"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()

        val item = PendingItem(
            connId = connId,
            messageId = messageId,
            kind = kind,
            requirementId = requirementId,
            seq = seq,
            title = payload.optString("title") ?: "",
            projectName = payload.optString("projectName") ?: "",
            agent = payload.optString("agent") ?: "",
            agentName = payload.optString("agentName") ?: "",
            note = payload.optString("note") ?: "",
            completedAt = payload.optString("completedAt"),
            questionId = questionId,
            questionText = payload.optString("text") ?: "",
            options = options,
            defaultAnswer = payload.optString("default") ?: "",
            expiresAt = payload.optString("expiresAt"),
            at = message.optString("at") ?: Protocol.nowIso(),
            receivedAt = existing?.receivedAt ?: now,
            lastPushedAt = now,
            answeredDecision = existing?.answeredDecision,
            answerText = existing?.answerText ?: "",
            answerId = existing?.answerId,
            sentAt = existing?.sentAt,
            answerError = existing?.answerError
        )

        val nextItems = if (existing == null) {
            items + item
        } else {
            items.map { if (it.connId == connId && it.messageId == messageId) item else it }
        }
        update(_snapshot.value.copy(items = nextItems))

        val needsResend = item.isAnswered && item.sentAt != null &&
            now - item.sentAt >= Protocol.KEEPALIVE_MS
        return UpsertResult(item = item, isNew = existing == null, needsResend = needsResend)
    }

    @Synchronized
    fun markAnswerAttempt(connId: String, messageId: String, answerId: String, decision: String, text: String) {
        mutateItem(connId, messageId) {
            it.copy(
                answeredDecision = decision,
                answerText = text,
                answerId = answerId,
                sentAt = null,
                answerError = null
            )
        }
    }

    @Synchronized
    fun markAnswerSent(connId: String, messageId: String, sentAt: Long) {
        mutateItem(connId, messageId) {
            it.copy(sentAt = sentAt, answerError = null)
        }
    }

    @Synchronized
    fun markAnswerError(connId: String, messageId: String, error: String) {
        mutateItem(connId, messageId) {
            it.copy(answerError = error, sentAt = null)
        }
    }

    @Synchronized
    fun removeItems(keys: Collection<String>): List<PendingItem> {
        if (keys.isEmpty()) return emptyList()
        val current = _snapshot.value
        val removed = current.items.filter { it.key in keys }
        if (removed.isEmpty()) return emptyList()
        update(current.copy(items = current.items.filterNot { it.key in keys }))
        return removed
    }

    /** 服务端已停止推送的条目：视为桌面端已处理，移出待办。 */
    @Synchronized
    fun removeStale(connId: String, pushedBefore: Long): List<PendingItem> {
        val current = _snapshot.value
        val removed = current.items.filter {
            it.connId == connId && it.lastPushedAt < pushedBefore
        }
        if (removed.isEmpty()) return emptyList()
        val keys = removed.map { it.key }.toSet()
        update(current.copy(items = current.items.filterNot { it.key in keys }))
        return removed
    }

    @Synchronized
    fun removeAllItems(connId: String): List<PendingItem> {
        val current = _snapshot.value
        val removed = current.items.filter { it.connId == connId }
        if (removed.isEmpty()) return emptyList()
        update(current.copy(items = current.items.filterNot { it.connId == connId }))
        return removed
    }

    @Synchronized
    fun isNotified(key: String): Boolean = _snapshot.value.notified.contains(key)

    @Synchronized
    fun markNotified(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val current = _snapshot.value
        val merged = (current.notified + keys).distinct().takeLast(500)
        update(current.copy(notified = merged))
    }

    @Synchronized
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val current = _snapshot.value
        update(current.copy(settings = transform(current.settings)))
    }

    fun findItem(connId: String, messageId: String): PendingItem? =
        items.firstOrNull { it.connId == connId && it.messageId == messageId }

    fun connection(id: String): ConnectionRecord? =
        connections.firstOrNull { it.id == id }

    fun itemsForConnection(connId: String): List<PendingItem> =
        items.filter { it.connId == connId }

    @Synchronized
    private fun mutateItem(connId: String, messageId: String, transform: (PendingItem) -> PendingItem) {
        val current = _snapshot.value
        var changed = false
        val next = current.items.map {
            if (it.connId == connId && it.messageId == messageId) {
                changed = true
                transform(it)
            } else {
                it
            }
        }
        if (changed) update(current.copy(items = next))
    }

    @Synchronized
    private fun update(next: StoreSnapshot) {
        _snapshot.value = next
        store.save(next)
    }

    private fun JsonObject.optString(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.contentOrNull
    }
}

data class AddConnectionResult(
    val record: ConnectionRecord,
    val alreadyExists: Boolean
)
