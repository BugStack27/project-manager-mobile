package com.projectmanager.mobile.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 桌面端 /remote 接口的客户端。
 *
 * 只碰远程面（`/remote/` 下那几个接口）：本机 agent 用的 `/claim`、`/next`、`/complete` 在公网侧被服务端
 * 直接 403，这里也不提供入口 —— 手机上本来就不存在「取任务」这个动作。
 *
 * 全部请求带 `X-PM-Token`；令牌由桌面端生成，随配对信息送到手机。
 */
class RemoteApi(private val config: RemoteConfig) {

    private val base = config.baseUrl.trim().trimEnd('/')

    private fun http(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        var url = (base + path).toHttpUrlOrNull()
            ?: throw RemoteApiException("桌面端地址不合法：$base", "bad_base_url", 0)
        query.forEach { (k, v) ->
            if (v != null) url = url.newBuilder().addQueryParameter(k, v).build()
        }
        return url
    }

    private suspend fun exec(
        path: String,
        method: String = "GET",
        query: Map<String, String?> = emptyMap(),
        body: String? = null
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(http(path, query))
            .header("X-PM-Token", config.token)
        when (method) {
            "POST" -> builder.post(
                (body ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            else -> builder.get()
        }
        val response = try {
            CLIENT.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw RemoteApiException("连不上桌面端：${e.message ?: "网络不可用"}", "network", 0)
        }
        response.use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val message = runCatching {
                    JSON.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                throw RemoteApiException(
                    message ?: "请求失败（HTTP ${res.code}）",
                    "http_${res.code}",
                    res.code
                )
            }
            text
        }
    }

    private inline fun <reified T> decode(text: String): T = try {
        JSON.decodeFromString<T>(text)
    } catch (e: Exception) {
        throw RemoteApiException("桌面端返回的数据看不懂：${e.message}", "bad_payload", 0)
    }

    suspend fun hello(): String = decode<RemoteHello>(exec("/remote/hello")).version

    suspend fun projects(): List<RemoteProject> =
        decode<RemoteProjectList>(exec("/remote/projects", query = mapOf("includeArchived" to "0"))).projects

    suspend fun requirements(projectId: String, pendingOnly: Boolean = false): List<RemoteRequirement> =
        decode<RemoteRequirementList>(
            exec(
                "/remote/project/$projectId/requirements",
                query = mapOf("pending" to if (pendingOnly) "1" else null)
            )
        ).requirements

    suspend fun changes(projectId: String): RemoteChanges =
        decode<RemoteChanges>(exec("/remote/project/$projectId/changes"))

    suspend fun diff(projectId: String, path: String, untracked: Boolean): RemoteDiff =
        decode<RemoteDiff>(
            exec(
                "/remote/project/$projectId/diff",
                query = mapOf("path" to path, "untracked" to if (untracked) "1" else null)
            )
        )

    suspend fun commits(projectId: String, limit: Int = 20): List<RemoteCommitEntry> =
        decode<RemoteCommitList>(
            exec("/remote/project/$projectId/commits", query = mapOf("limit" to "$limit"))
        ).commits

    suspend fun commitDiff(projectId: String, sha: String, path: String): RemoteDiff =
        decode<RemoteDiff>(
            exec(
                "/remote/project/$projectId/commit-diff",
                query = mapOf("sha" to sha, "path" to path)
            )
        )

    suspend fun commands(projectId: String): List<RemoteCommand> =
        decode<RemoteCommandList>(exec("/remote/project/$projectId/commands")).commands

    suspend fun runs(commandId: String): List<RemoteRun> =
        decode<RemoteRunList>(exec("/remote/command/$commandId/runs")).runs

    suspend fun logs(commandId: String, runId: String? = null): RemoteLogs =
        decode<RemoteLogs>(
            exec("/remote/command/$commandId/logs", query = mapOf("runId" to runId))
        )

    /** 待办快照：进页面或长轮询超时后重新对齐用 */
    suspend fun pending(): RemotePending = decode<RemotePending>(exec("/remote/pending"))

    /**
     * 长轮询：有变化立即返回，没变化最多挂 wait 秒。
     * key 传上一轮的指纹，服务端比对后决定继续等还是立刻返回 —— 客户端不必自己算 diff。
     */
    suspend fun wait(key: String, waitSeconds: Int = 25): RemotePending =
        decode<RemotePending>(
            exec("/remote/wait", query = mapOf("key" to key, "wait" to "$waitSeconds"))
        )

    /** 答复：decision = answer / approve / feedback / reject */
    suspend fun answer(
        requirementId: String,
        decision: String,
        text: String? = null,
        questionId: String? = null
    ): RemoteAnswerResult {
        val body = buildJsonObject {
            put("decision", decision)
            if (!text.isNullOrBlank()) put("text", text)
            if (!questionId.isNullOrBlank()) put("questionId", questionId)
        }
        return decode<RemoteAnswerResult>(
            exec("/remote/requirement/$requirementId/answer", method = "POST", body = body.toString())
        )
    }

    /** 采纳 / 拒绝候选需求：只接受 todo 与 rejected，其它状态在服务端会被拒 */
    suspend fun setStatus(requirementId: String, status: String, note: String? = null) {
        val body = buildJsonObject {
            put("status", status)
            if (!note.isNullOrBlank()) put("note", note)
        }
        exec("/remote/requirement/$requirementId/status", method = "POST", body = body.toString())
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        /**
         * 读超时 45 秒：长轮询服务端最多挂 30 秒，再留 15 秒给移动网络的抖动。
         * 比这更短会让正常的等待被自己掐断，更长则断网时白等。
         */
        val CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}

class RemoteApiException(
    override val message: String,
    val code: String = "",
    val httpStatus: Int = 0
) : Exception(message)

@Serializable
data class RemoteHello(val name: String = "", val version: String = "", val remote: Boolean = false)

@Serializable
data class RemoteProject(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val archived: Boolean = false
)

@Serializable
data class RemoteProjectList(val projects: List<RemoteProject> = emptyList())

@Serializable
data class RemoteQuestion(
    val id: String = "",
    val agentName: String = "",
    val text: String = "",
    val options: List<String> = emptyList(),
    val default: String = "",
    val expiresAt: String? = null
)

@Serializable
data class RemoteCompletion(
    val agent: String = "",
    val agentName: String = "",
    val note: String = "",
    val at: String = ""
)

@Serializable
data class RemoteRequirement(
    val id: String = "",
    val projectId: String = "",
    val seq: Int? = null,
    val title: String = "",
    val body: String = "",
    val note: String = "",
    val status: String = "",
    val source: String = "manual",
    val questions: List<RemoteQuestion> = emptyList(),
    val completion: RemoteCompletion? = null
) {
    val statusLabel: String
        get() = when (status) {
            "candidate" -> "候选"
            "todo" -> "未开始"
            "doing" -> "进行中"
            "review" -> "待审核"
            "done" -> "已完成"
            "rejected" -> "已拒绝"
            else -> status
        }
}

@Serializable
data class RemoteRequirementList(val requirements: List<RemoteRequirement> = emptyList())

@Serializable
data class RemoteChangeCounts(
    val total: Int = 0,
    val staged: Int = 0,
    val unstaged: Int = 0,
    val untracked: Int = 0,
    val added: Int = 0,
    val removed: Int = 0
)

@Serializable
data class RemoteChangeFile(
    val path: String = "",
    val status: String = "",
    val added: Int = 0,
    val removed: Int = 0,
    val untracked: Boolean = false,
    val binary: Boolean = false
)

@Serializable
data class RemoteChanges(
    val isRepo: Boolean = false,
    val branch: String? = null,
    val clean: Boolean = true,
    val counts: RemoteChangeCounts = RemoteChangeCounts(),
    val files: List<RemoteChangeFile> = emptyList()
)

@Serializable
data class RemoteDiff(
    val isRepo: Boolean = true,
    val path: String = "",
    val untracked: Boolean = false,
    val binary: Boolean = false,
    val diff: String = "",
    val content: String = "",
    val truncated: Boolean = false
) {
    val text: String get() = if (untracked) content else diff
}

@Serializable
data class RemoteCommitEntry(
    val sha: String = "",
    val shortSha: String = "",
    val subject: String = "",
    val author: String = "",
    val at: String = ""
)

@Serializable
data class RemoteCommitList(val commits: List<RemoteCommitEntry> = emptyList())

@Serializable
data class RemoteCommand(
    val id: String = "",
    val name: String = "",
    val text: String = "",
    val running: Boolean = false
)

@Serializable
data class RemoteCommandList(val commands: List<RemoteCommand> = emptyList())

@Serializable
data class RemoteRun(
    val id: String = "",
    val startedAt: String? = null,
    val endedAt: String? = null,
    val status: String = "",
    val exitCode: Int? = null
)

@Serializable
data class RemoteRunList(val runs: List<RemoteRun> = emptyList())

@Serializable
data class RemoteLogLine(
    val t: String = "",
    val level: String = "info",
    val text: String = ""
)

@Serializable
data class RemoteLogs(
    val lines: List<RemoteLogLine> = emptyList(),
    val truncated: Boolean = false,
    val running: Boolean = false,
    val run: RemoteRun? = null
)

@Serializable
data class RemoteTicketPayload(
    val requirementId: String = "",
    val seq: Int? = null,
    val title: String = "",
    val projectId: String? = null,
    val projectName: String = "",
    val agent: String = "",
    val agentName: String = "",
    val note: String = "",
    val completedAt: String? = null,
    val questionId: String = "",
    val text: String = "",
    val options: List<String> = emptyList(),
    val default: String = "",
    val expiresAt: String? = null
)

@Serializable
data class RemoteTicket(
    val id: String = "",
    val type: String = "",
    val from: String = "",
    val at: String = "",
    val payload: RemoteTicketPayload = RemoteTicketPayload()
) {
    val isReview: Boolean get() = type == "review"
}

@Serializable
data class RemotePending(
    val key: String = "",
    val messages: List<RemoteTicket> = emptyList(),
    val count: Int = 0,
    val timedOut: Boolean = false
)

@Serializable
data class RemoteCommit(
    val committed: Boolean = false,
    val shortSha: String = "",
    val pushError: String = ""
)

@Serializable
data class RemoteAnswerResult(
    val id: String = "",
    val applied: Boolean = false,
    val duplicate: Boolean = false,
    val reason: String? = null,
    val message: String? = null,
    val commit: RemoteCommit? = null
)
