package com.projectmanager.mobile.ui

import com.projectmanager.mobile.data.ItemKind
import com.projectmanager.mobile.data.PendingItem
import com.projectmanager.mobile.data.RemoteProject
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 项目卡片上的待审核 / 待回答标记：按连接和项目归属，只算还没处理的待办。
 */
class ProjectPendingTest {

    private val project = RemoteProject(id = "p1", name = "手机端", path = "D:/job/mobile")

    private fun iso(offsetMs: Long): String =
        Instant.ofEpochMilli(System.currentTimeMillis() + offsetMs).toString()

    private fun item(
        kind: ItemKind,
        connId: String = "c1",
        projectId: String? = "p1",
        projectName: String = "",
        sentAt: Long? = null,
        expiresAt: String? = null
    ) = PendingItem(
        connId = connId,
        messageId = "m-${kind}-${projectId ?: projectName}-${sentAt ?: "unsent"}",
        kind = kind,
        requirementId = "r1",
        projectId = projectId,
        projectName = projectName,
        sentAt = sentAt,
        expiresAt = expiresAt
    )

    @Test
    fun `counts reviews and questions of the same project`() {
        val pending = projectPending(
            listOf(
                item(ItemKind.REVIEW),
                item(ItemKind.REVIEW),
                item(ItemKind.QUESTION)
            ),
            connId = "c1",
            project = project
        )
        assertEquals(2, pending.reviews)
        assertEquals(1, pending.questions)
    }

    @Test
    fun `ignores other desktops and other projects`() {
        val pending = projectPending(
            listOf(
                item(ItemKind.REVIEW, connId = "c2"),
                item(ItemKind.REVIEW, projectId = "p2"),
                item(ItemKind.REVIEW)
            ),
            connId = "c1",
            project = project
        )
        assertEquals(1, pending.reviews)
        assertEquals(0, pending.questions)
    }

    @Test
    fun `falls back to project name when desktop sends no id`() {
        val pending = projectPending(
            listOf(
                item(ItemKind.REVIEW, projectId = null, projectName = "手机端"),
                item(ItemKind.REVIEW, projectId = null, projectName = "别的项目")
            ),
            connId = "c1",
            project = project
        )
        assertEquals(1, pending.reviews)
    }

    @Test
    fun `submitted or expired items are not counted`() {
        val pending = projectPending(
            listOf(
                item(ItemKind.QUESTION, sentAt = 1L, expiresAt = iso(3_600_000)),
                item(ItemKind.QUESTION, expiresAt = iso(-1_000)),
                item(ItemKind.REVIEW)
            ),
            connId = "c1",
            project = project
        )
        assertEquals(1, pending.reviews)
        assertEquals(0, pending.questions)
    }
}
