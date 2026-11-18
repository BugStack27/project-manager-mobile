package com.projectmanager.mobile.link

import com.projectmanager.mobile.data.LinkFailure
import com.projectmanager.mobile.data.LinkFailureKind
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 把传输层的异常翻成「人话」：一类错误对应一句原因 + 一句下一步。
 *
 * 判定逻辑是纯函数，不碰网络也不读时钟以外的状态，所以能在 JVM 单测里直接覆盖；
 * 界面只认 [LinkFailureKind]，不再自己拼字符串。
 */
object LinkFailures {

    fun classify(e: Throwable, now: Long = System.currentTimeMillis()): LinkFailure =
        LinkFailure(kindOf(e), detailOf(e), now)

    fun kindOf(e: Throwable): LinkFailureKind {
        if (e is PushGoHttpException) return kindOfHttp(e.code, e.detail)
        // 具体网络异常优先于 message 判断：OkHttp 的 message 常常只有一句 "Failed to connect to …"
        if (e is UnknownHostException) return LinkFailureKind.NETWORK
        if (e is SocketTimeoutException) return LinkFailureKind.NETWORK
        if (e is InterruptedIOException) return LinkFailureKind.NETWORK
        if (e is ConnectException || e is NoRouteToHostException || e is SocketException) return LinkFailureKind.NETWORK
        if (e is SSLException) return LinkFailureKind.NETWORK
        val text = e.message.orEmpty()
        return when {
            text.contains("连接信息不完整") -> LinkFailureKind.CONFIG
            text.contains("device_key") || text.contains("设备尚未注册") -> LinkFailureKind.GATEWAY
            else -> LinkFailureKind.UNKNOWN
        }
    }

    /** 网关错误码：401/403 是密码，404 是频道，5xx 与其余 4xx 归网关 */
    fun kindOfHttp(code: Int, detail: String?): LinkFailureKind {
        if (code == 401 || code == 403) return LinkFailureKind.PASSWORD
        if (code == 404) return LinkFailureKind.CHANNEL
        if (code >= 500) return LinkFailureKind.GATEWAY
        val text = detail.orEmpty().lowercase()
        return when {
            text.contains("password") || text.contains("密码") -> LinkFailureKind.PASSWORD
            text.contains("channel") || text.contains("topic") || text.contains("频道") -> LinkFailureKind.CHANNEL
            text.contains("subscribe") || text.contains("订阅") -> LinkFailureKind.PASSWORD
            else -> LinkFailureKind.GATEWAY
        }
    }

    /** 网关把设备记录清了：重新注册即可，不该让用户重扫码 */
    fun isDeviceGone(e: Throwable): Boolean =
        e is PushGoHttpException &&
            (e.errorCode == "device_key_not_found" ||
                e.detail.orEmpty().contains("device_key_not_found"))

    private fun detailOf(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: (e.javaClass.simpleName.ifBlank { "未知错误" })
}
