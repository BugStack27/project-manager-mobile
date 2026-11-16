package com.projectmanager.mobile.link

import com.projectmanager.mobile.data.AppRepository
import com.projectmanager.mobile.data.RemoteApi
import com.projectmanager.mobile.data.RemoteTicket
import com.projectmanager.mobile.data.toPendingItem
import com.projectmanager.mobile.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 远程面（HTTP 长轮询）的常驻订阅者 —— 现在也是**唯一**的待办来源。
 *
 * 为什么放在前台服务里而不是界面的 ViewModel 里：ViewModel 跟着 Activity 走，退到后台被回收后
 * 长轮询就断了，「后台收不到通知」就是这么来的。放进服务，与前台服务同生命周期常驻；
 * 服务里持有唤醒锁，息屏后定时器照常触发。
 *
 * 待办同时写进本地库（`connId = "remote"`），所以待办页、详情页沿用原来的 UI，
 * 只是提交时改走 `/remote/requirement/:rid/answer`。
 */
class RemoteWatcher(
    private val repo: AppRepository,
    private val notifier: Notifier
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        var key = ""
        var backoff = 5_000L

        while (currentCoroutineContext().isActive) {
            val config = repo.snapshot.value.remote
            if (!config.ready) {
                // 还没配对 / 桌面端还没下发地址：别空转，但也别退出，配置一到就接着跑
                delay(3_000)
                continue
            }

            try {
                val api = RemoteApi(config)
                val res = api.wait(key, 25)
                key = res.key
                backoff = 5_000L

                // 先落库再提醒：通知点进去要有对应的条目，顺序反了会点开空白
                val fresh = repo.syncRemoteTickets(res.messages.map { it.toPendingItem() })
                announce(res.messages, fresh.map { it.messageId }.toSet())
            } catch (e: Exception) {
                // 地址失效、桌面端关了、隧道重建中：都按「稍后再试」处理，不打扰用户
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    /**
     * 只提醒「这一轮新出现、且历史上没提醒过」的待办。
     * 已提醒集合落盘，所以进程被杀后重开：积压的旧待办补一次通知，不会每轮重放。
     */
    private fun announce(tickets: List<RemoteTicket>, freshIds: Set<String>) {
        if (freshIds.isEmpty()) return
        val newly = ArrayList<String>(freshIds.size)
        for (t in tickets) {
            if (t.id.isBlank() || t.id !in freshIds) continue
            if (repo.isRemoteNotified(t.id)) continue
            newly.add(t.id)
            notifier.notifyRemote(
                ticketId = t.id,
                messageId = t.id,
                title = if (t.isReview) {
                    "待审核 · ${t.payload.projectName.ifBlank { "桌面端" }}"
                } else {
                    "待回答 · ${t.payload.projectName.ifBlank { "桌面端" }}"
                },
                body = buildString {
                    if (t.payload.seq != null) append("#${t.payload.seq} ")
                    append(t.payload.title)
                    val detail = if (t.isReview) t.payload.note else t.payload.text
                    if (detail.isNotBlank()) append("\n\n").append(detail)
                }
            )
        }
        repo.markRemoteNotified(newly)
    }
}
