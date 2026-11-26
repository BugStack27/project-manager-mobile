package com.projectmanager.mobile.ui

import com.projectmanager.mobile.data.RemoteRequirement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 未完成列表的展示顺序：待审核最靠前，同档按项目名与编号稳定排列。
 */
class UnfinishedSortTest {

    private fun entry(
        status: String,
        seq: Int,
        project: String = "项目A",
        connId: String = "c1"
    ) = RemoteUnfinishedEntry(
        connectionId = connId,
        connectionName = "工作电脑",
        projectId = "p-$project",
        projectName = project,
        requirement = RemoteRequirement(
            id = "$connId|$project|$seq",
            projectId = "p-$project",
            seq = seq,
            status = status
        )
    )

    @Test
    fun `review first then doing then todo then unknown`() {
        val sorted = sortedUnfinished(
            listOf(
                entry("todo", 3),
                entry("done", 4),
                entry("doing", 2),
                entry("review", 1)
            )
        )
        assertEquals(listOf("review", "doing", "todo", "done"), sorted.map { it.requirement.status })
    }

    @Test
    fun `same status sorts by project name then newest seq first`() {
        val sorted = sortedUnfinished(
            listOf(
                entry("todo", 1, project = "B 项目"),
                entry("todo", 2, project = "A 项目"),
                entry("todo", 5, project = "A 项目", connId = "c2")
            )
        )
        assertEquals(
            listOf("A 项目|5", "A 项目|2", "B 项目|1"),
            sorted.map { "${it.projectName}|${it.requirement.seq}" }
        )
    }
}