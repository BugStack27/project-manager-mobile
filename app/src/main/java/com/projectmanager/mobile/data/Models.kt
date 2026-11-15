package com.projectmanager.mobile.data

import com.projectmanager.mobile.core.Protocol
import java.net.URL
import kotlinx.serialization.Serializable

@Serializable
data class ConnectionRecord(
    val id: String,
    val name: String,
    val server: String,
    val topic: String,
    val key: String,
    val addedAt: Long = System.currentTimeMillis(),
    /** ntfy（v1 连接信息）或 pushgo（v2 连接信息） */
    val provider: String = "ntfy",
    val deviceKey: String? = null,
    val providerToken: String? = null
) {
    val isPushGo: Boolean get() = provider == "pushgo"

    val host: String
        get() = runCatching { URL(server).host }.getOrNull().orEmpty().ifEmpty { server }

    fun maskedTopic(): String =
        if (topic.length <= 12) topic else topic.take(6) + "••••" + topic.takeLast(4)
}

@Serializable
enum class ItemKind { REVIEW, QUESTION }

@Serializable
data class PendingItem(
    val connId: String,
    val messageId: String,
    val kind: ItemKind,
    val requirementId: String,
    val seq: Int? = null,
    val title: String = "",
    val projectName: String = "",
    val agent: String = "",
    val agentName: String = "",
    val note: String = "",
    val completedAt: String? = null,
    val questionId: String? = null,
    val questionText: String = "",
    val options: List<String> = emptyList(),
    val defaultAnswer: String = "",
    val expiresAt: String? = null,
    val at: String = "",
    val receivedAt: Long = 0L,
    val lastPushedAt: Long = 0L,
    val answeredDecision: String? = null,
    val answerText: String = "",
    val answerId: String? = null,
    val sentAt: Long? = null,
    val answerError: String? = null
) {
    val key: String get() = "$connId|$messageId"
    val isAnswered: Boolean get() = answeredDecision != null
    val atMillis: Long get() = Protocol.parseTime(at) ?: receivedAt
    val completedAtMillis: Long? get() = Protocol.parseTime(completedAt)
    val expiresAtMillis: Long? get() = Protocol.parseTime(expiresAt)

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        val exp = expiresAtMillis ?: return false
        return exp <= now
    }

    fun sourceLabel(connectionName: String): String {
        val proj = projectName.ifEmpty { "未命名项目" }
        return if (seq != null) "$connectionName · $proj #$seq" else "$connectionName · $proj"
    }
}

@Serializable
data class AppSettings(
    val notificationsEnabled: Boolean = true,
    val batteryPromptShown: Boolean = false
)

@Serializable
data class StoreSnapshot(
    val connections: List<ConnectionRecord> = emptyList(),
    val items: List<PendingItem> = emptyList(),
    val notified: List<String> = emptyList(),
    val settings: AppSettings = AppSettings()
)

data class ConnectionRuntime(
    val connected: Boolean = false,
    val lastInboundAt: Long? = null,
    val lastError: String? = null,
    val pending: Int = 0,
    val sessionStartedAt: Long? = null,
    val lastPublishAt: Long? = null,
    val reconnectAt: Long? = null
) {
    fun desktopOnline(now: Long = System.currentTimeMillis()): Boolean =
        lastInboundAt != null && now - lastInboundAt <= Protocol.CLIENT_ONLINE_MS

    val statusText: String
        get() = when {
            connected && desktopOnline() -> "在线"
            connected -> "已连接 ntfy · 桌面端暂无心跳"
            lastError != null -> "重连中"
            else -> "连接中…"
        }
}

data class UpsertResult(
    val item: PendingItem,
    val isNew: Boolean,
    val needsResend: Boolean
)
