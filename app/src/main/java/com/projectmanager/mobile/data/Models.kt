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
    /** 只剩 pushgo；字段保留是为了老存档还能解码（v1 的 ntfy 已停用） */
    val provider: String = "pushgo",
    val deviceKey: String? = null,
    val providerToken: String? = null
) {
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

    /**
     * 还能处理的待办：未提交且没过期。
     *
     * 提醒与角标都按这个口径统计 —— 过期提问点开也提交不了，再响铃只是打扰，
     * 计入角标也让「还有几件事要做」失真。列表仍按 [isExpired] 把它们留在原位灰显。
     */
    fun isActionable(now: Long = System.currentTimeMillis()): Boolean =
        sentAt == null && !isExpired(now)

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

/**
 * 远程访问配置：桌面端隧道地址 + 令牌。
 *
 * 与 PushGo 连接信息分开存：PushGo 负责「把地址和令牌送到手机上」，这里存的是送到之后
 * 用来直连桌面端 /remote 接口的两样东西。地址会随隧道重建而变，令牌不会——
 * 所以两者一起存，改了地址不必重新配对令牌。
 */
@Serializable
data class RemoteConfig(
    val baseUrl: String = "",
    val token: String = "",
    val updatedAt: Long = 0L
) {
    val ready: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    fun normalized(): RemoteConfig = copy(baseUrl = baseUrl.trim().trimEnd('/'), token = token.trim())
}

/** 远程待办在本地使用的固定连接 id：待办页与详情页据此判断「这条要走 HTTP 提交」 */
const val REMOTE_CONN_ID = "remote"

@Serializable
data class StoreSnapshot(
    val connections: List<ConnectionRecord> = emptyList(),
    val items: List<PendingItem> = emptyList(),
    val notified: List<String> = emptyList(),
    /** 远程待办已经提醒过的 id：落盘才能在进程被杀后「重开即补」而不重复轰炸 */
    val remoteNotified: List<String> = emptyList(),
    val settings: AppSettings = AppSettings(),
    /** 老版本的存档没有这一块：靠默认值补上，不必迁移 */
    val remote: RemoteConfig = RemoteConfig()
)

data class ConnectionRuntime(
    val connected: Boolean = false,
    /** 最近一次收到 PushGo 心跳的时间：只说明通道链接更新过，不再参与在线判定 */
    val lastInboundAt: Long? = null,
    /** 最近一次连接失败（连上网关后清空）；没失败过就是 null */
    val failure: LinkFailure? = null,
    val pending: Int = 0,
    val sessionStartedAt: Long? = null,
    val lastPublishAt: Long? = null,
    val reconnectAt: Long? = null
) {
    /** 传输层原文，诊断用；界面展示走 [adviceKind] 的文案 */
    val lastError: String? get() = failure?.detail

    /**
     * 当前该展示哪种失败说明；连上了就没有要提示的。
     *
     * 在线判定只看 [connected]（通道接上即在线）：业务已全部走远程 HTTP，PushGo 只剩
     * 「后台更新通道链接」，再拿它的心跳包判断在线会让通道明明通着却一直显示「等待心跳」。
     */
    val adviceKind: LinkFailureKind?
        get() = failure?.kind

    val statusText: String
        get() = when {
            connected -> "已连接"
            failure != null -> failure.kind.title
            else -> "连接中…"
        }

    /** 列表 / 状态行用的短标签 */
    val chipText: String
        get() = when {
            connected -> "在线"
            failure != null -> failure.kind.chip
            else -> "连接中"
        }
}

data class UpsertResult(
    val item: PendingItem,
    val isNew: Boolean,
    val needsResend: Boolean
)


/**
 * 远程待办 → 本地待办模型。
 *
 * 待办页与详情页都是按 PendingItem 写的，这里做一次映射就能复用整套 UI；
 * connId 用固定的 [REMOTE_CONN_ID]，提交时据此改走 /remote 而不是老的消息通道。
 */
fun RemoteTicket.toPendingItem(): PendingItem = PendingItem(
    connId = REMOTE_CONN_ID,
    messageId = id,
    kind = if (isReview) ItemKind.REVIEW else ItemKind.QUESTION,
    requirementId = payload.requirementId,
    seq = payload.seq,
    title = payload.title,
    projectName = payload.projectName,
    agent = payload.agent,
    agentName = payload.agentName,
    note = payload.note,
    completedAt = payload.completedAt ?: if (isReview) at else null,
    questionId = payload.questionId.ifBlank { null },
    questionText = payload.text,
    options = payload.options,
    defaultAnswer = payload.default,
    expiresAt = payload.expiresAt,
    at = at,
    receivedAt = System.currentTimeMillis(),
    lastPushedAt = System.currentTimeMillis()
)
