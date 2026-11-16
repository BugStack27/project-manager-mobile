package com.projectmanager.mobile.core

import java.net.URL
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 与桌面端 `electron/services/mobile-protocol.js` 逐条对齐的协议实现。
 * 这里任何常量或算法变化都等于改协议，两端必须同时升级 v。
 */
object Protocol {
    const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    private const val TOPIC_PREFIX = "pm-"
    private const val TOPIC_RANDOM_LEN = 16
    private const val KEY_LEN = 32

    private const val KDF_SALT_PREFIX = "pm-mobile-v1:"
    private const val KDF_ITERATIONS = 100_000
    private const val KDF_KEYLEN = 32

    private const val ENVELOPE_VERSION = 1
    const val MESSAGE_VERSION = 1
    private const val IV_LEN = 12
    private const val TAG_LEN = 16

    const val KEEPALIVE_MS = 60_000L
    const val CLIENT_ONLINE_MS = 150_000L
    const val TEXT_MAX = 2000
    const val MAX_ENVELOPE_BYTES = 4000
    const val KEEPALIVE_ID_PREFIX = "keepalive:"

    private val TOPIC_RE = Regex("^pm-[a-z2-9]{16}$")
    private val KEY_RE = Regex("^[a-z2-9]{32}$")
    private val ANSWER_ID_RE = Regex("^answer:[A-Za-z0-9_-]{6,64}$")
    private val REQUIREMENT_ID_RE = Regex("^r_[A-Za-z0-9]+$")
    private val QUESTION_ID_RE = Regex("^q_[A-Za-z0-9]+$")

    private val TYPES = setOf("review", "question", "answer", "keepalive")
    private val SIDES = setOf("server", "client")
    private val DECISIONS = setOf("answer", "approve", "feedback", "reject")

    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 最近几条派生密钥，避免每分钟重算 10 万轮 PBKDF2 */
    private val keyCache = object : LinkedHashMap<String, SecretKey>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SecretKey>?): Boolean =
            size > 8
    }

    fun isValidTopic(value: String?): Boolean = value != null && TOPIC_RE.matches(value)

    fun isValidKey(value: String?): Boolean = value != null && KEY_RE.matches(value)

    fun isValidRequirementId(value: String?): Boolean =
        value != null && REQUIREMENT_ID_RE.matches(value)

    fun isValidQuestionId(value: String?): Boolean =
        value != null && QUESTION_ID_RE.matches(value)

    fun isValidAnswerId(value: String?): Boolean =
        value != null && ANSWER_ID_RE.matches(value)

    fun isValidDecision(value: String?): Boolean = value != null && DECISIONS.contains(value)

    fun randomToken(len: Int): String {
        val chars = CharArray(len)
        for (i in 0 until len) chars[i] = ALPHABET[random.nextInt(ALPHABET.length)]
        return String(chars)
    }

    fun newAnswerId(): String =
        "answer:" + System.currentTimeMillis().toString(36) + randomToken(10)

    fun newKeepaliveId(from: String): String =
        "$KEEPALIVE_ID_PREFIX$from:${System.currentTimeMillis()}"

    fun deriveKey(key: String, topic: String): SecretKey {
        // topic 在 PushGo 模式下是频道 ID，因此这里只把 topic 当作 AAD / 盐的一部分，
        // 具体格式由连接信息解析处校验。
        require(key.isNotEmpty()) { "连接密钥不合法" }
        val cacheKey = "$topic|$key"
        synchronized(keyCache) { keyCache[cacheKey]?.let { return it } }

        val spec = PBEKeySpec(
            key.toCharArray(),
            (KDF_SALT_PREFIX + topic).toByteArray(Charsets.UTF_8),
            KDF_ITERATIONS,
            KDF_KEYLEN * 8
        )
        val encoded = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val result = SecretKeySpec(encoded, "AES")
        synchronized(keyCache) { keyCache[cacheKey] = result }
        return result
    }

    /** 明文 JSON → `{"v":1,"n":"<iv>","d":"<密文+tag>"}` */
    fun seal(topic: String, key: String, message: JsonObject): String {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(key, topic), GCMParameterSpec(128, iv))
        cipher.updateAAD(topic.toByteArray(Charsets.UTF_8))
        // SunJCE 的 GCM 加密输出是「密文 + 16 字节 tag」，与桌面端 `d` 的布局一致
        val body = cipher.doFinal(message.toString().toByteArray(Charsets.UTF_8))
        return buildJsonObject {
            put("v", ENVELOPE_VERSION)
            put("n", Base64.getEncoder().encodeToString(iv))
            put("d", Base64.getEncoder().encodeToString(body))
        }.toString()
    }

    /**
     * 解信封并校验消息外形。任何一步失败都返回 null：
     * 同一 topic 可能被别的工具复用，解不开就当不是给我们的消息。
     */
    fun open(topic: String, key: String, text: String): JsonObject? {
        return try {
            val env = json.parseToJsonElement(text).jsonObject
            val version = env["v"]?.jsonPrimitive?.content?.toIntOrNull()
            if (version != ENVELOPE_VERSION) return null
            val n = env["n"]?.jsonPrimitive?.content ?: return null
            val d = env["d"]?.jsonPrimitive?.content ?: return null

            val iv = Base64.getDecoder().decode(n)
            val body = Base64.getDecoder().decode(d)
            if (iv.size != IV_LEN || body.size <= TAG_LEN) return null

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(key, topic),
                GCMParameterSpec(128, iv)
            )
            cipher.updateAAD(topic.toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(body)
            val message = json.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonObject

            val messageVersion = message["v"]?.jsonPrimitive?.content?.toIntOrNull()
            if (messageVersion != MESSAGE_VERSION) return null
            if (message["type"]?.jsonPrimitive?.content !in TYPES) return null
            if (message["from"]?.jsonPrimitive?.content !in SIDES) return null
            if (message["id"]?.jsonPrimitive?.content.isNullOrEmpty()) return null
            message
        } catch (_: Exception) {
            null
        }
    }

    fun parseConnectInfo(text: String): ConnectInfo? {
        return try {
            val obj = json.parseToJsonElement(text.trim()).jsonObject
            val version = obj["v"]?.jsonPrimitive?.content?.toIntOrNull()
            val type = obj["type"]?.jsonPrimitive?.content
            if (type != "pm-mobile") return null

            // 只认 v2 / pushgo：v1（ntfy）那套订阅协议已经停用，扫旧码应当直接拒绝
            val provider = obj["provider"]?.jsonPrimitive?.content?.trim()?.lowercase() ?: ""
            if (version == 2 && provider == "pushgo") {
                val gateway = obj["gateway"]?.jsonPrimitive?.content?.trim()?.trimEnd('/')
                val channel = obj["channel"]?.jsonPrimitive?.content?.trim()
                val password = obj["password"]?.jsonPrimitive?.content
                if (gateway.isNullOrEmpty() || gateway.length > 300) return null
                if (channel.isNullOrEmpty() || channel.length !in 10..80) return null
                if (password == null || password.toByteArray(Charsets.UTF_8).size !in 8..128) return null
                if (!channel.matches(Regex("^[A-Za-z0-9]+$"))) return null
                val url = URL(gateway)
                if (url.protocol != "http" && url.protocol != "https") return null
                if (url.host.isNullOrEmpty()) return null
                return ConnectInfo(
                    provider = "pushgo",
                    server = gateway,
                    topic = channel,
                    key = password
                )
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    fun parseTime(iso: String?): Long? {
        if (iso.isNullOrEmpty()) return null
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    fun nowIso(): String = Instant.now().toString()
}

data class ConnectInfo(
    val server: String,
    val topic: String,
    val key: String,
    val provider: String = "pushgo"
)
