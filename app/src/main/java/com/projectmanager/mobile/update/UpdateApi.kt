package com.projectmanager.mobile.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    val sha256Url: String?
)

/**
 * 检查 GitHub Release 上的新版本。
 *
 * 发布流水线（.github/workflows/release.yml）把 APK 与同名 .sha256 挂到 Release，
 * 这里只认正式发布：草稿与预发布一律跳过。未登录调用限 60 次/小时，
 * 自动检查有节流（见 UpdateViewModel），正常使用远低于限制。
 */
object UpdateApi {

    /** 与 release.yml 推送到 GitHub 的仓库保持一致 */
    private const val REPO = "BugStack27/project-manager-mobile"
    const val USER_AGENT = "project-manager-mobile"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 返回可更新的版本；已是最新（或没有可下载的 APK）时返回 null */
    suspend fun latest(installedCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        val release = try {
            client.newCall(request).execute().use { res ->
                if (res.code == 404) return@withContext null
                if (!res.isSuccessful) throw IOException("检查更新失败（HTTP ${res.code}）")
                json.decodeFromString<Release>(res.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw IOException("检查更新失败：${e.message ?: "网络不可用"}", e)
        } catch (e: Exception) {
            throw IOException("检查更新失败：无法解析发布信息", e)
        }

        if (release.draft || release.prerelease) return@withContext null

        val versionName = release.tagName.trim().removePrefix("v").removePrefix("V")
        val versionCode = parseVersionCode(versionName) ?: return@withContext null
        if (versionCode <= installedCode) return@withContext null

        val apk = release.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.isNotBlank()
        } ?: return@withContext null
        val sha = release.assets.firstOrNull { it.name == "${apk.name}.sha256" }

        UpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            notes = release.body.trim(),
            apkUrl = apk.downloadUrl,
            apkName = apk.name,
            sizeBytes = apk.size,
            sha256Url = sha?.downloadUrl
        )
    }

    @Serializable
    private data class Release(
        @kotlinx.serialization.SerialName("tag_name") val tagName: String = "",
        val body: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList()
    )

    @Serializable
    private data class Asset(
        val name: String = "",
        val size: Long = 0,
        @kotlinx.serialization.SerialName("browser_download_url") val downloadUrl: String = ""
    )
}

/**
 * tag 去掉 v 后按 major.minor.patch 换算 versionCode，与 release.yml 的
 * `major*10000 + minor*100 + patch` 一致。
 */
fun parseVersionCode(tag: String): Int? {
    val parts = tag.trim().removePrefix("v").removePrefix("V").split('.')
    if (parts.isEmpty() || parts.size > 3) return null
    val nums = parts.map { part ->
        val n = part.toIntOrNull() ?: return null
        if (n < 0) return null
        n
    }
    val major = nums.getOrElse(0) { 0 }
    val minor = nums.getOrElse(1) { 0 }
    val patch = nums.getOrElse(2) { 0 }
    return major * 10000 + minor * 100 + patch
}
