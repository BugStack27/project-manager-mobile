package com.projectmanager.mobile.link

import com.projectmanager.mobile.core.Protocol
import com.projectmanager.mobile.data.AppRepository
import com.projectmanager.mobile.data.ConnectionRecord
import com.projectmanager.mobile.data.ConnectionRuntime
import com.projectmanager.mobile.data.LinkFailure
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
 * PushGo 的接入点通道：每 15 秒拉一次，只关心心跳里的 `endpoint`。
 *
 * 2026-11-16 收窄：这条通道以前还负责接收审核/提问、回传答案，与 HTTP 长轮询并行维护两套业务状态。
 * 现在业务全部走 `/remote`（由前台服务里的 RemoteWatcher 常驻长轮询），这里只做一件事：
 * 把桌面端下发的隧道地址与令牌落盘，供远程面使用。
 *
 * 心跳同时是桌面端存活的推送信号：桌面端退出后频道仍在，靠 150 秒内是否收到心跳判断在线；
 * 远程面可达时则直接算在线，不必再等心跳。
 */
class PushGoSession(
    private val connection: ConnectionRecord,
    private val repo: AppRepository,
    private val onRuntime: (ConnectionRuntime) -> Unit
) : TransportSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = PushGoClient()

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

    /** 最近一次连接失败；成功接上（拿到网关会话）就清空，界面据此显示具体原因 */
    @Volatile
    private var failure: LinkFailure? = null

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
        publishRuntime()
    }

    override fun stop() {
        scope.cancel()
    }

    override fun reconnectNow() {
        // Pull 是短请求，下一轮自动重试；无需中断长连接
    }

    private suspend fun loop() {
        var backoff = RECONNECT_MIN_MS
        while (currentCoroutineContext().isActive) {
            try {
                ensureReady()
                connected = true
                failure = null
                reconnectAt = null
                backoff = RECONNECT_MIN_MS
                publishRuntime()
                while (currentCoroutineContext().isActive) {
                    pollOnce()
                    // 心跳过期是随时间发生的：每轮都上报一次，界面才能自己从在线翻成等待桌面端
                    publishRuntime()
                    delay(POLL_MS)
                }
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) break
                connected = false
                if (LinkFailures.isDeviceGone(e)) {
                    // 设备记录被网关清掉：下一轮 ensureReady 会重新注册、建路由、重新订阅
                    deviceKey = null
                    routeReady = false
                    subscribed = false
                }
                failure = LinkFailures.classify(e)
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

                if (message.str("from") == "server" && message.str("type") == "keepalive") {
                    // 心跳是桌面端存活的唯一推送信号：刷新最近更新并参与在线判定
                    lastInboundAt = now
                    applyEndpoint(message["payload"] as? JsonObject)
                    publishRuntime()
                }
                ackIds += item.deliveryId
            } catch (e: Exception) {
                // 单条消息解不开（topic 被别的工具复用、密文坏掉）不影响连接状态，照旧 ack 掉即可
                ackIds += item.deliveryId
            }
        }
        if (ackIds.isNotEmpty()) client.ack(gateway, key, ackIds)
    }

    /**
     * 桌面端下发的接入点：地址与令牌同时齐全才落盘；缺一个就保持原样（旧地址可能还在用）。
     * 只写这条连接自己的配置：多台电脑各自心跳，不能互相覆盖。
     */
    private fun applyEndpoint(payload: JsonObject?) {
        val endpoint = payload?.get("endpoint") as? JsonObject ?: return
        val url = endpoint.str("url")?.trim().orEmpty()
        val token = endpoint.str("token")?.trim().orEmpty()
        if (url.isBlank() || token.isBlank()) return
        repo.updateRemoteEndpoint(connection.id, url, token)
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
                failure = failure,
                sessionStartedAt = startedAt,
                lastPublishAt = lastPublishAt,
                reconnectAt = reconnectAt,
                checkedAt = System.currentTimeMillis()
            )
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val POLL_MS = 15_000L
        const val RECONNECT_MIN_MS = 2000L
        const val RECONNECT_MAX_MS = 30000L
        const val SEEN_MAX = 500
    }
}
