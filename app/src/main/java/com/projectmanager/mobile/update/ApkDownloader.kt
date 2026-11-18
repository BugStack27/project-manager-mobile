package com.projectmanager.mobile.update

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** 把 Release 上的 APK 下载到 cacheDir/updates，下载过程回调 0..1 进度（总大小未知时为 null）。 */
object ApkDownloader {

    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float?) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, info.apkName)
        try {
            val request = Request.Builder()
                .url(info.apkUrl)
                .header("User-Agent", UpdateApi.USER_AGENT)
                .build()
            UpdateApi.client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw IOException("下载失败（HTTP ${res.code}）")
                val body = res.body ?: throw IOException("下载失败：响应为空")
                val total = body.contentLength().takeIf { it > 0 }
                    ?: info.sizeBytes.takeIf { it > 0 }
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        var lastPercent = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            if (total != null) {
                                val percent = ((read * 100) / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }
                    }
                }
            }
            val expected = info.sha256Url?.let { fetchChecksum(it) }
            if (expected != null && !sha256(target).equals(expected, ignoreCase = true)) {
                throw IOException("安装包校验失败，请稍后重试")
            }
            target
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    /** 校验文件由 CI 生成，内容形如 `<sha256>  <文件名>`；取不到时不阻断更新 */
    private fun fetchChecksum(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UpdateApi.USER_AGENT)
            .build()
        UpdateApi.client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) {
                null
            } else {
                Regex("[0-9a-fA-F]{64}").find(res.body?.string().orEmpty())?.value
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
