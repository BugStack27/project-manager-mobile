package com.projectmanager.mobile.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 提醒与角标的口径：只有「未提交且没过期」的条目才算待办。
 * 列表是否灰显仍由 isExpired 单独判断，两者互不影响。
 */
class ExpiryRulesTest {

    private val now = 1_760_000_000_000L

    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun question(expiresAt: String?, sentAt: Long? = null) = PendingItem(
        connId = "c_test",
        messageId = "m1",
        kind = ItemKind.QUESTION,
        requirementId = "r1",
        expiresAt = expiresAt,
        sentAt = sentAt
    )

    private fun ticket(expiresAt: String?) = RemoteTicket(
        id = "t1",
        type = "question",
        payload = RemoteTicketPayload(requirementId = "r1", expiresAt = expiresAt)
    )

    @Test
    fun `question before deadline is actionable`() {
        val item = question(expiresAt = iso(now + 60_000))
        assertEquals(false, item.isExpired(now))
        assertEquals(true, item.isActionable(now))
    }

    @Test
    fun `expired question is not actionable but still listed`() {
        val item = question(expiresAt = iso(now - 1_000))
        assertEquals(true, item.isExpired(now))
        assertEquals(false, item.isActionable(now))
    }

    @Test
    fun `deadline exactly now counts as expired`() {
        val item = question(expiresAt = iso(now))
        assertEquals(true, item.isExpired(now))
        assertEquals(false, item.isActionable(now))
    }

    @Test
    fun `review without deadline stays actionable`() {
        val item = PendingItem(
            connId = "c_test",
            messageId = "m2",
            kind = ItemKind.REVIEW,
            requirementId = "r2"
        )
        assertEquals(true, item.isActionable(now))
    }

    @Test
    fun `submitted item is not actionable even before deadline`() {
        val item = question(expiresAt = iso(now + 60_000), sentAt = now - 5_000)
        assertEquals(false, item.isActionable(now))
    }

    @Test
    fun `remote ticket expiry mirrors local rule`() {
        assertEquals(true, ticket(iso(now - 1)).isExpired(now))
        assertEquals(false, ticket(iso(now + 60_000)).isExpired(now))
        // 没有截止时间 / 时间串坏了：按「不过期」处理，宁可提醒也不漏
        assertEquals(false, ticket(null).isExpired(now))
        assertEquals(false, ticket("").isExpired(now))
        assertEquals(false, ticket("not-a-time").isExpired(now))
    }
}
