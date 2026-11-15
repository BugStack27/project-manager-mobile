package com.projectmanager.mobile.link

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** ntfy 的发布 / 订阅；订阅用 NDJSON 流，读超时兜住半死连接。 */
class NtfyClient {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // 服务端每 60 秒发一次 keepalive；150 秒没有任何数据就认为连接已死
        .readTimeout(150, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun subscribeUrl(server: String, topic: String): String =
        server.trimEnd('/') + "/" + topic + "/json?since=10m"

    fun subscribeRequest(server: String, topic: String): Request =
        Request.Builder()
            .url(subscribeUrl(server, topic))
            .header("Accept", "application/json")
            .get()
            .build()

    fun publish(server: String, topic: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BODY_BYTES) {
            throw IOException("消息超过 ntfy 4KB 限制（${bytes.size} 字节）")
        }
        val request = Request.Builder()
            .url(server.trimEnd('/') + "/" + topic)
            .post(bytes.toRequestBody(TEXT_PLAIN))
            .header("X-Ttl", "600")
            .header("X-Priority", "3")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = runCatching { response.body?.string()?.take(120) }.getOrNull()
                throw IOException("ntfy 发布失败：HTTP ${response.code}${detail?.let { " $it" } ?: ""}")
            }
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 4000
        val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()
    }
}
