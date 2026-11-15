package com.projectmanager.mobile.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 连接密钥、待办与通知去重都落在这个加密文件里；初始化失败时退化为普通文件保证可用。 */
class SecureStore(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "pm_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e("SecureStore", "加密存储初始化失败，退化为普通存储", e)
        context.getSharedPreferences("pm_secure_plain", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun load(): StoreSnapshot {
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return StoreSnapshot()
        return try {
            json.decodeFromString<StoreSnapshot>(raw)
        } catch (e: Exception) {
            Log.e("SecureStore", "读取本地状态失败", e)
            StoreSnapshot()
        }
    }

    @Synchronized
    fun save(snapshot: StoreSnapshot) {
        try {
            // 用 commit 而不是 apply：后台服务随时可能被系统杀掉，答案与去重状态必须立即落盘
            prefs.edit().putString(KEY_SNAPSHOT, json.encodeToString(snapshot)).commit()
        } catch (e: Exception) {
            Log.e("SecureStore", "保存本地状态失败", e)
        }
    }

    private companion object {
        const val KEY_SNAPSHOT = "snapshot_v1"
    }
}
