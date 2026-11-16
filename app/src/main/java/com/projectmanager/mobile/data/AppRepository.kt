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

    init {
        migrateLegacyRemote()
        dropUnattributedItems()
    }

    /**
     * 老版本只存一条全局接入点。升级后按连接分开存，这里把它交给唯一的那台电脑；
     * 多台连接时无法判断归属，只能等各自的心跳重新下发（桌面端每 10 分钟必发一次）。
     */
    private fun migrateLegacyRemote() {
        val current = _snapshot.value
        val legacy = current.remote
        if (!legacy.ready) return
        val target = current.connections.singleOrNull()
        val connections = if (target != null && !target.remote.ready) {
            current.connections.map { if (it.id == target.id) it.copy(remote = legacy) else it }
        } else {
            current.connections
        }
        update(current.copy(connections = connections, remote = RemoteConfig()))
    }

    /**
     * 老版本只有一个全局接入点，待办统一挂在 `remote` 这个假连接下。
     * 现在每条待办都要归到具体某台电脑；这些旧条目找不到归属，先清掉，下一轮长轮询会按连接重新落库。
     */
    private fun dropUnattributedItems() {
        val current = _snapshot.value
        val stale = current.items.filter { it.connId == LEGACY_REMOTE_CONN_ID }
        if (stale.isEmpty()) return
        update(
            current.copy(
                items = current.items.filterNot { it.connId == LEGACY_REMOTE_CONN_ID },
                notified = current.notified.filterNot { it.startsWith("$LEGACY_REMOTE_CONN_ID|") }
            )
        )
    }

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

    /** 桌面端下发的接入点：只写它自己的连接，多台电脑互不覆盖 */
    @Synchronized
    fun updateRemoteEndpoint(connId: String, baseUrl: String, token: String) {
        val next = RemoteConfig(baseUrl = baseUrl, token = token, updatedAt = System.currentTimeMillis()).normalized()
        val current = _snapshot.value
        val target = current.connections.firstOrNull { it.id == connId } ?: return
        if (target.remote.baseUrl == next.baseUrl && target.remote.token == next.token) return
        update(
            current.copy(
                connections = current.connections.map { if (it.id == connId) it.copy(remote = next) else it }
            )
        )
    }

    /**
     * 用一台桌面端的一轮长轮询结果替换它自己的远程待办。
     *
     * 只替换这台连接的条目，其他电脑的待办原样保留；本地的答复状态（已提交 / 失败原因）
     * 按 messageId 继承，避免重拉把「刚点过通过」的痕迹冲掉。
     */
    @Synchronized
    fun syncRemoteTickets(connId: String, incoming: List<PendingItem>): List<PendingItem> {
        val current = _snapshot.value
        val existing = current.items.filter { it.connId == connId }.associateBy { it.messageId }
        val merged = incoming.map { fresh ->
            val old = existing[fresh.messageId]
            if (old == null) {
                fresh
            } else {
                fresh.copy(
                    answeredDecision = old.answeredDecision,
                    answerText = old.answerText,
                    answerId = old.answerId,
                    sentAt = old.sentAt,
                    answerError = old.answerError
                )
            }
        }
        val fresh = merged.filter { existing[it.messageId] == null }
        val untouched = current.items.filter { it.connId != connId }
        update(current.copy(items = untouched + merged))
        return fresh
    }

    /**
     * 远程待办的提醒记录：新格式带连接 id，避免两台电脑的同名消息互相顶掉；
     * 老存档只存过 messageId，一并认，升级后不会把还挂着的待办再响一遍。
     */
    fun isRemoteNotified(connId: String, messageId: String): Boolean {
        val ids = _snapshot.value.remoteNotified
        return ids.contains("$connId|$messageId") || ids.contains(messageId)
    }

    @Synchronized
    fun markRemoteNotified(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val current = _snapshot.value
        val merged = (current.remoteNotified + ids).distinct().takeLast(500)
        update(current.copy(remoteNotified = merged))
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

    private companion object {
        /** 老版本的全局远程配置在待办里留下的固定 connId，只用于清理旧数据 */
        const val LEGACY_REMOTE_CONN_ID = "remote"
    }
}

data class AddConnectionResult(
    val record: ConnectionRecord,
    val alreadyExists: Boolean
)
