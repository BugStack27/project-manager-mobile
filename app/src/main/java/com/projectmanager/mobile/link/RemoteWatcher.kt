package com.projectmanager.mobile.link

import com.projectmanager.mobile.data.AppRepository
import com.projectmanager.mobile.data.ConnectionRecord
import com.projectmanager.mobile.data.PendingItem
import com.projectmanager.mobile.data.RemoteApi
import com.projectmanager.mobile.data.RemoteConfig
import com.projectmanager.mobile.data.toPendingItem
import com.projectmanager.mobile.notify.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 远程面（HTTP 长轮询）的常驻订阅者 —— 现在也是**唯一**的待办来源。
 *
 * 每台桌面端一条独立长轮询：接入点按连接分别保存，谁的待办挂谁名下，
 * 多台电脑一起配对时不会互相覆盖，也不会在界面上糊成「桌面端」。
 *
 * 为什么放在前台服务里而不是界面的 ViewModel 里：ViewModel 跟着 Activity 走，退到后台被回收后
 * 长轮询就断了，「后台收不到通知」就是这么来的。放进服务，与前台服务同生命周期常驻；
 * 服务里持有唤醒锁，息屏后定时器照常触发。
 */
class RemoteWatcher(
    private val repo: AppRepository,
    private val notifier: Notifier,
    /** 每台桌面端的长轮询结果，供连接状态区分“电脑关了”和“网关频道还在” */
    private val onRemoteHealth: (String, Boolean) -> Unit = { _, _ -> }
) {
    private var snapshotJob: Job? = null
    private var scope: CoroutineScope? = null
    private val polls = LinkedHashMap<String, Poll>()

    private class Poll(val config: RemoteConfig, val job: Job)

    fun start(scope: CoroutineScope) {
        if (snapshotJob != null) return
        this.scope = scope
        snapshotJob = scope.launch {
            // 连接增删、接入点更新都会推一份新快照：按需起停各自的轮询
            repo.snapshot.collect { reconcile(it.connections) }
        }
    }

    fun stop() {
        snapshotJob?.cancel()
        snapshotJob = null
        polls.values.forEach { it.job.cancel() }
        polls.clear()
        scope = null
    }

    private fun reconcile(connections: List<ConnectionRecord>) {
        val wanted = connections.filter { it.remote.ready }
        val wantedById = wanted.associateBy { it.id }
        for ((id, poll) in polls.toList()) {
            val target = wantedById[id]
            if (target == null || target.remote != poll.config) {
                poll.job.cancel()
                polls.remove(id)
            }
        }
        val scope = scope ?: return
        for (connection in wanted) {
            if (polls.containsKey(connection.id)) continue
            val config = connection.remote
            val job = scope.launch { pollLoop(connection.id, config) }
            polls[connection.id] = Poll(config, job)
        }
    }

    /** 单台桌面端的长轮询：有变化立即返回，没变化最多挂 25 秒。 */
    private suspend fun pollLoop(connId: String, config: RemoteConfig) {
        var key = ""
        var backoff = 5_000L

        while (currentCoroutineContext().isActive) {
            try {
                val res = try {
                    RemoteApi(config).wait(key, 25)
                } catch (e: CancellationException) {
                    // 换接入点 / 停服务时取消：不是桌面端不可达
                    throw e
                } catch (e: Exception) {
                    if (currentCoroutineContext().isActive) onRemoteHealth(connId, false)
                    throw e
                }
                // 请求可能在取消后才返回：先确认自己仍是当前轮询，避免旧结果覆盖新状态
                currentCoroutineContext().ensureActive()
                onRemoteHealth(connId, true)
                key = res.key
                backoff = 5_000L

                // 先落库再提醒：通知点进去要有对应的条目，顺序反了会点开空白
                val fresh = repo.syncRemoteTickets(connId, res.messages.map { it.toPendingItem(connId) })
                announce(connId, fresh)
                // 过期是按时间变化的：每轮顺手对一次账，把刚过期的条目从角标里摘掉
                notifier.updateSummary()
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
     *
     * 离线一段时间恢复后，队列里常夹着早已过期的提问：这些点开也提交不了，直接跳过不响铃。
     */
    private fun announce(connId: String, fresh: List<PendingItem>) {
        if (fresh.isEmpty()) return
        val connectionName = repo.connection(connId)?.name ?: "桌面端"
        val now = System.currentTimeMillis()
        val newly = ArrayList<String>(fresh.size)
        for (item in fresh) {
            if (item.isExpired(now)) continue
            if (repo.isRemoteNotified(connId, item.messageId)) continue
            newly.add(item.key)
            notifier.notifyRemote(item, connectionName)
        }
        if (newly.isNotEmpty()) repo.markRemoteNotified(newly)
    }
}
