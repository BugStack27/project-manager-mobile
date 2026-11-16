package com.projectmanager.mobile.link

import com.projectmanager.mobile.core.Protocol
import com.projectmanager.mobile.data.ConnectionRuntime
import com.projectmanager.mobile.data.LinkFailureKind
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkFailuresTest {

    @Test
    fun `dns and connect errors are network`() {
        assertEquals(LinkFailureKind.NETWORK, LinkFailures.kindOf(UnknownHostException("gw.pushgo.cn")))
        assertEquals(LinkFailureKind.NETWORK, LinkFailures.kindOf(ConnectException("Connection refused")))
        assertEquals(LinkFailureKind.NETWORK, LinkFailures.kindOf(SocketTimeoutException("timeout")))
    }

    @Test
    fun `wrong password asks for rescan`() {
        assertEquals(LinkFailureKind.PASSWORD, LinkFailures.kindOf(PushGoHttpException(401, "invalid password")))
        assertEquals(LinkFailureKind.PASSWORD, LinkFailures.kindOf(PushGoHttpException(403, null)))
        assertTrue(LinkFailureKind.PASSWORD.needsRescan)
    }

    @Test
    fun `missing channel asks for rescan`() {
        assertEquals(LinkFailureKind.CHANNEL, LinkFailures.kindOf(PushGoHttpException(404, "channel not found")))
        assertEquals(
            LinkFailureKind.CHANNEL,
            LinkFailures.kindOf(PushGoHttpException(400, "channel_id does not exist"))
        )
        assertTrue(LinkFailureKind.CHANNEL.needsRescan)
    }

    @Test
    fun `bad request mentioning password is a password error`() {
        assertEquals(
            LinkFailureKind.PASSWORD,
            LinkFailures.kindOf(PushGoHttpException(400, "password mismatch"))
        )
    }

    @Test
    fun `other gateway errors are not user actionable`() {
        assertEquals(LinkFailureKind.GATEWAY, LinkFailures.kindOf(PushGoHttpException(502, "bad gateway")))
        assertEquals(LinkFailureKind.GATEWAY, LinkFailures.kindOf(PushGoHttpException(429, "rate limited")))
        assertFalse(LinkFailureKind.GATEWAY.needsRescan)
    }

    @Test
    fun `incomplete local pairing is a config error`() {
        assertEquals(LinkFailureKind.CONFIG, LinkFailures.kindOf(IOException("PushGo 连接信息不完整")))
        assertTrue(LinkFailureKind.CONFIG.needsRescan)
        assertEquals(LinkFailureKind.GATEWAY, LinkFailures.kindOf(IOException("PushGo 未返回 device_key")))
    }

    @Test
    fun `unknown messages fall back to unknown and keep the raw text`() {
        val failure = LinkFailures.classify(IllegalStateException("boom"), now = 42L)
        assertEquals(LinkFailureKind.UNKNOWN, failure.kind)
        assertEquals("boom", failure.detail)
        assertEquals(42L, failure.at)
    }

    @Test
    fun `http detail with password is password but 5xx stays gateway`() {
        assertEquals(LinkFailureKind.PASSWORD, LinkFailures.kindOfHttp(400, "密码错误"))
        assertEquals(LinkFailureKind.GATEWAY, LinkFailures.kindOfHttp(503, "密码错误"))
    }

    @Test
    fun `runtime shows failure reason instead of generic reconnecting`() {
        val failure = LinkFailures.classify(PushGoHttpException(401, "invalid password"))
        val runtime = ConnectionRuntime(connected = false, failure = failure)
        assertEquals("频道密码不对", runtime.statusText)
        assertEquals("密码错误", runtime.chipText)
        assertEquals(LinkFailureKind.PASSWORD, runtime.adviceKind)
    }

    @Test
    fun `connected channel is online even with a stale heartbeat`() {
        // 心跳只用来更新通道链接，早就不参与在线判定：通道通着就该显示已连接
        val stale = System.currentTimeMillis() - Protocol.CLIENT_ONLINE_MS - 1000
        val runtime = ConnectionRuntime(connected = true, lastInboundAt = stale)
        assertNull(runtime.adviceKind)
        assertEquals("在线", runtime.chipText)
        assertEquals("已连接", runtime.statusText)
        assertTrue(runtime.connected)
    }

    @Test
    fun `connected channel is online before any inbound message`() {
        val runtime = ConnectionRuntime(connected = true)
        assertNull(runtime.adviceKind)
        assertEquals("在线", runtime.chipText)
        assertEquals("已连接", runtime.statusText)
    }

    @Test
    fun `healthy connection has no advice`() {
        val runtime = ConnectionRuntime(connected = true, lastInboundAt = System.currentTimeMillis())
        assertNull(runtime.adviceKind)
        assertEquals("在线", runtime.chipText)
    }
}
