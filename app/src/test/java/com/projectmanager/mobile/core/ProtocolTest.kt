package com.projectmanager.mobile.core

import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    private val topic = "pm-" + Protocol.randomToken(16)
    private val key = Protocol.randomToken(32)

    private fun message(type: String = "keepalive", from: String = "client"): kotlinx.serialization.json.JsonObject =
        buildJsonObject {
            put("v", 1)
            put("type", type)
            put("from", from)
            put("id", "keepalive:$from:1")
            put("at", Protocol.nowIso())
            put("payload", buildJsonObject { put("online", true) })
        }

    @Test
    fun `seal and open are inverse`() {
        val sealed = Protocol.seal(topic, key, message())
        val opened = Protocol.open(topic, key, sealed)
        assertNotNull(opened)
        assertEquals("keepalive", opened!!["type"]?.jsonPrimitive?.content)
        assertEquals("client", opened["from"]?.jsonPrimitive?.content)
    }

    @Test
    fun `wrong topic cannot open`() {
        val sealed = Protocol.seal(topic, key, message())
        val otherTopic = "pm-" + Protocol.randomToken(16)
        assertNull(Protocol.open(otherTopic, key, sealed))
    }

    @Test
    fun `wrong key cannot open`() {
        val sealed = Protocol.seal(topic, key, message())
        assertNull(Protocol.open(topic, Protocol.randomToken(32), sealed))
    }

    @Test
    fun `tampered ciphertext cannot open`() {
        val sealed = Protocol.seal(topic, key, message())
        val env = kotlinx.serialization.json.Json.parseToJsonElement(sealed).jsonObject
        val raw = Base64.getDecoder().decode(env["d"]!!.jsonPrimitive.content)
        raw[raw.size / 2] = (raw[raw.size / 2].toInt() xor 0x01).toByte()
        val tampered = buildJsonObject {
            put("v", 1)
            put("n", env["n"]!!.jsonPrimitive.content)
            put("d", Base64.getEncoder().encodeToString(raw))
        }.toString()
        assertNull(Protocol.open(topic, key, tampered))
    }

    @Test
    fun `plain json is not an envelope`() {
        assertNull(Protocol.open(topic, key, "{\"v\":1}"))
        assertNull(Protocol.open(topic, key, "not json"))
    }

    @Test
    fun `v1 ntfy connect info is rejected`() {
        // v1（ntfy）已经停用：扫到旧二维码必须拒绝，而不是当成可用接入点
        val legacy = """{"v":1,"type":"pm-mobile","server":"https://ntfy.sh","topic":"$topic","key":"$key"}"""
        assertNull(Protocol.parseConnectInfo(legacy))
    }

    @Test
    fun `pushgo connect info maps gateway channel and password`() {
        val channel = "AQK63PX88SQVBM7G24GBXS45GC"
        val password = "abcdefghijkmnpqrstuvwxyz23456789"
        val text = """{"v":2,"type":"pm-mobile","provider":"pushgo","gateway":"https://gateway.pushgo.dev/","channel":"$channel","password":"$password"}"""
        val info = Protocol.parseConnectInfo(text)
        assertNotNull(info)
        assertEquals("pushgo", info!!.provider)
        assertEquals("https://gateway.pushgo.dev", info.server)
        assertEquals(channel, info.topic)
        assertEquals(password, info.key)
        assertEquals(null, Protocol.parseConnectInfo(text.replace("\"pushgo\"", "\"other\"")))
        assertEquals(null, Protocol.parseConnectInfo(text.replace(channel, "short")))
        assertEquals(null, Protocol.parseConnectInfo(text.replace("https://gateway.pushgo.dev", "ftp://gateway.pushgo.dev")))
    }

    @Test
    fun `pushgo envelope binds channel as aad`() {
        val channel = "AQK63PX88SQVBM7G24GBXS45GC"
        val password = "abcdefghijkmnpqrstuvwxyz23456789"
        val sealed = Protocol.seal(channel, password, message())
        val opened = Protocol.open(channel, password, sealed)
        assertNotNull(opened)
        assertEquals("keepalive", opened!!["type"]?.jsonPrimitive?.content)
        assertNull(Protocol.open("BQK63PX88SQVBM7G24GBXS45GC", password, sealed))
    }

    @Test
    fun `answer ids match desktop regex`() {
        repeat(20) {
            assertTrue(Protocol.isValidAnswerId(Protocol.newAnswerId()))
        }
        assertFalse(Protocol.isValidAnswerId("answer:123"))
        assertFalse(Protocol.isValidAnswerId("nope"))
    }

    @Test
    fun `derived keys are stable per topic and differ across topics`() {
        val a = Protocol.deriveKey(key, topic).encoded
        val b = Protocol.deriveKey(key, topic).encoded
        assertArrayEqualsCompat(a, b)
        val other = Protocol.deriveKey(key, "pm-" + Protocol.randomToken(16)).encoded
        assertFalse(a.contentEquals(other))
    }

    @Test
    fun `sealed envelope carries iv and ciphertext`() {
        val sealed = Protocol.seal(topic, key, message())
        val env = kotlinx.serialization.json.Json.parseToJsonElement(sealed).jsonObject
        assertEquals(12, Base64.getDecoder().decode(env["n"]!!.jsonPrimitive.content).size)
        assertTrue(Base64.getDecoder().decode(env["d"]!!.jsonPrimitive.content).size > 16)
    }

    private fun assertArrayEqualsCompat(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals(expected[i], actual[i])
    }
}
