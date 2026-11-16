package com.projectmanager.mobile.link

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class PushGoPullItem(
    val deliveryId: String,
    val payload: Map<String, String>
)

/**
 * 网关回了业务错误。
 *
 * 单独成型是为了让界面能按 `code` / `detail` 判类别（密码错、频道失效、网关 5xx），
 * 而不是只拿到一句拼好的「PushGo HTTP 401：invalid password」去正则。
 */
class PushGoHttpException(val code: Int, val detail: String?) : IOException(
    "PushGo HTTP $code" + (detail?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
)

/**
 * PushGo Gateway 的最小客户端：设备注册、FCM 路由（为 Pull 准备）、频道订阅、发送、Pull、ACK。
 * 这是 Pull 原型：消息通过 FCM 路由入队，但不依赖真实 FCM 送达，靠 /v2/messages/pull 拉取。
 */
class PushGoClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun registerDevice(gateway: String, existingDeviceKey: String? = null): String {
        val body = buildJsonObject {
            put("platform", "android")
            if (!existingDeviceKey.isNullOrBlank()) put("device_key", existingDeviceKey)
        }
        val data = post(gateway, "/device/register", body)
        return data.str("device_key") ?: throw IOException("PushGo 未返回 device_key")
    }

    fun ensureFcmRoute(gateway: String, deviceKey: String, providerToken: String) {
        post(
            gateway,
            "/channel/device",
            buildJsonObject {
                put("device_key", deviceKey)
                put("channel_type", "fcm")
                put("provider_token", providerToken)
            }
        )
    }

    fun subscribe(gateway: String, deviceKey: String, channelId: String, password: String) {
        post(
            gateway,
            "/channel/subscribe",
            buildJsonObject {
                put("device_key", deviceKey)
                put("channel_id", channelId)
                put("password", password)
            }
        )
    }

    fun send(
        gateway: String,
        channelId: String,
        password: String,
        title: String,
        body: String,
        ciphertext: String,
        opId: String,
        metadata: JsonObject
    ) {
        post(
            gateway,
            "/message",
            buildJsonObject {
                put("channel_id", channelId)
                put("password", password)
                put("title", title)
                put("body", body)
                put("ciphertext", ciphertext)
                put("op_id", opId)
                put("metadata", metadata)
            }
        )
    }

    fun pull(gateway: String, deviceKey: String): List<PushGoPullItem> {
        val all = ArrayList<PushGoPullItem>()
        var pageToken: String? = null
        var pages = 0
        do {
            val body = buildJsonObject {
                put("device_key", deviceKey)
                if (!pageToken.isNullOrBlank()) put("page_token", pageToken)
            }
            val data = post(gateway, "/v2/messages/pull", body)
            val items = data["items"] as? JsonArray ?: JsonArray(emptyList())
            all += items.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val deliveryId = item.str("delivery_id") ?: return@mapNotNull null
                val payloadObject = item["payload"] as? JsonObject ?: return@mapNotNull null
                PushGoPullItem(
                    deliveryId = deliveryId,
                    payload = payloadObject.mapValues { (_, value) ->
                        (value as? JsonPrimitive)?.contentOrNull.orEmpty()
                    }
                )
            }
            val hasMore = (data["has_more"] as? JsonPrimitive)?.contentOrNull == "true"
            pageToken = if (hasMore) (data["next_page_token"] as? JsonPrimitive)?.contentOrNull else null
            pages += 1
        } while (!pageToken.isNullOrBlank() && pages < 10)
        return all
    }

    fun ack(gateway: String, deviceKey: String, deliveryIds: List<String>) {
        if (deliveryIds.isEmpty()) return
        post(
            gateway,
            "/v2/messages/ack",
            buildJsonObject {
                put("device_key", deviceKey)
                put("delivery_ids", buildJsonArray { deliveryIds.forEach { add(JsonPrimitive(it)) } })
            }
        )
    }

    private fun post(gateway: String, path: String, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(gateway.trimEnd('/') + path)
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val success = (parsed?.get("success") as? JsonPrimitive)?.contentOrNull == "true"
            if (!response.isSuccessful || !success) {
                val error = (parsed?.get("error") as? JsonPrimitive)?.contentOrNull
                    ?: ((parsed?.get("problem") as? JsonObject)?.get("detail") as? JsonPrimitive)?.contentOrNull
                throw PushGoHttpException(response.code, error)
            }
            return (parsed?.get("data") as? JsonObject) ?: buildJsonObject {}
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
