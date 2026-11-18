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
    val providerToken: String? = null,
    /**
     * 这台电脑下发的 /remote 接入点。
     *
     * 每台桌面端各存一份：以前只存全局一条，多台电脑配对时后到的心跳会把地址覆盖掉，
     * 项目与待办就分不清出自哪台机器了。
     */
    val remote: RemoteConfig = RemoteConfig()
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

@Serializable
data class StoreSnapshot(
    val connections: List<ConnectionRecord> = emptyList(),
    val items: List<PendingItem> = emptyList(),
    val notified: List<String> = emptyList(),
    /** 远程待办已经提醒过的 id：落盘才能在进程被杀后「重开即补」而不重复轰炸 */
    val remoteNotified: List<String> = emptyList(),
    val settings: AppSettings = AppSettings(),
    /** 老版本的全局接入点：启动时迁移到连接上，迁移完清空。只为了升级不空窗，业务不再读它 */
    val remote: RemoteConfig = RemoteConfig()
)

data class ConnectionRuntime(
    val connected: Boolean = false,
    /** 最近一次收到 PushGo 心跳的时间：桌面端在线与否靠它和当前时间比 */
    val lastInboundAt: Long? = null,
    /** 最近一次连接失败（连上网关后清空）；没失败过就是 null */
    val failure: LinkFailure? = null,
    /** /remote 长轮询是否可达：null 表示还没轮到过一次 */
    val remoteReachable: Boolean? = null,
    /** 最近一次长轮询成功 / 失败时间，诊断用 */
    val remoteOkAt: Long? = null,
    val remoteFailAt: Long? = null,
    val pending: Int = 0,
    val sessionStartedAt: Long? = null,
    val lastPublishAt: Long? = null,
    val reconnectAt: Long? = null,
    /** 每次运行态上报的时间：让纯时间变化（心跳过期）也能触发界面重组 */
    val checkedAt: Long = 0L
) {
    /** 传输层原文，诊断用；界面展示走 [adviceKind] 的文案 */
    val lastError: String? get() = failure?.detail

    /**
     * 当前该展示哪种失败说明；连上了就没有要提示的。
     */
    val adviceKind: LinkFailureKind?
        get() = failure?.kind

    /**
     * 在线 = 网关频道接上，且远程面可达或至少心跳新鲜。
     * 远程面成功过就不必再等心跳；失败过则即使频道还在也不算在线。
     */
    fun isOnline(now: Long = System.currentTimeMillis()): Boolean =
        connected &&
            remoteReachable != false &&
            (remoteReachable == true || heartbeatFresh(now))

    private fun heartbeatFresh(now: Long): Boolean =
        lastInboundAt != null && now - lastInboundAt <= Protocol.CLIENT_ONLINE_MS

    val statusText: String
        get() = when {
            isOnline() -> "已连接"
            remoteReachable == false -> "桌面端不可达"
            connected -> "等待桌面端"
            failure != null -> failure.kind.title
            else -> "连接中…"
        }

    /** 列表 / 状态行用的短标签 */
    val chipText: String
        get() = when {
            isOnline() -> "在线"
            remoteReachable == false -> "不可达"
            connected -> "等待桌面端"
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
 * connId 用来源连接，提交时据此回到同一台桌面端的 /remote。
 */
fun RemoteTicket.toPendingItem(connId: String): PendingItem = PendingItem(
    connId = connId,
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
