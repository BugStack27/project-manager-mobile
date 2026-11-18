package com.projectmanager.mobile.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.projectmanager.mobile.BuildConfig
import com.projectmanager.mobile.PmApp
import com.projectmanager.mobile.update.ApkDownloader
import com.projectmanager.mobile.update.ApkInstaller
import com.projectmanager.mobile.update.UpdateApi
import com.projectmanager.mobile.update.UpdateInfo
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, DOWNLOADED, FAILED }

data class UpdateUiState(
    val info: UpdateInfo? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val progress: Float? = null,
    val error: String? = null,
    val dialogDismissed: Boolean = false
) {
    val showDialog: Boolean
        get() = info != null && !dialogDismissed
}

class UpdateViewModel(private val app: PmApp) : ViewModel() {

    private val prefs = app.getSharedPreferences("pm_update", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UpdateUiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var job: Job? = null
    private var downloadedFile: File? = null

    /** 启动时自动检查；同一版本不会每次打开都请求，6 小时内只查一次 */
    fun autoCheck() {
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < AUTO_CHECK_INTERVAL) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        val phase = _state.value.phase
        if (phase == UpdatePhase.CHECKING || phase == UpdatePhase.DOWNLOADING) return
        job = viewModelScope.launch {
            _state.update { it.copy(phase = UpdatePhase.CHECKING, error = null) }
            try {
                val info = UpdateApi.latest(BuildConfig.VERSION_CODE)
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                if (info != null) {
                    downloadedFile = null
                    _state.update {
                        it.copy(
                            info = info,
                            phase = UpdatePhase.AVAILABLE,
                            progress = null,
                            dialogDismissed = false
                        )
                    }
                } else {
                    _state.update {
                        it.copy(info = null, phase = UpdatePhase.UP_TO_DATE, progress = null)
                    }
                    if (manual) _events.emit("已是最新版本 v${BuildConfig.VERSION_NAME}")
                }
            } catch (e: Exception) {
                val message = e.message ?: "检查更新失败"
                _state.update { it.copy(phase = UpdatePhase.FAILED, error = message) }
                if (manual) _events.emit(message)
            }
        }
    }

    /** 下载完成后直接调起安装器；没给「安装未知应用」权限时先带去授权 */
    fun download() {
        val info = _state.value.info ?: return
        if (_state.value.phase == UpdatePhase.DOWNLOADING) return
        job = viewModelScope.launch {
            _state.update { it.copy(phase = UpdatePhase.DOWNLOADING, progress = 0f, error = null) }
            try {
                val file = ApkDownloader.download(app, info) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
                if (!ApkInstaller.verifyPackage(app, file)) {
                    file.delete()
                    throw IOException("下载的安装包不完整，请重试")
                }
                downloadedFile = file
                _state.update { it.copy(phase = UpdatePhase.DOWNLOADED, progress = 1f) }
                install()
            } catch (e: Exception) {
                val message = e.message ?: "下载失败"
                _state.update { it.copy(phase = UpdatePhase.FAILED, error = message, progress = null) }
                _events.emit(message)
            }
        }
    }

    fun install() {
        val file = downloadedFile ?: return
        if (!file.exists()) {
            _events.tryEmit("安装包已被系统清理，请重新下载")
            _state.update { it.copy(phase = UpdatePhase.AVAILABLE, progress = null) }
            downloadedFile = null
            return
        }
        if (!ApkInstaller.canInstall(app)) {
            ApkInstaller.openInstallSettings(app)
            _events.tryEmit("请允许本项目安装应用，返回后再点「安装」")
            return
        }
        runCatching { ApkInstaller.install(app, file) }
            .onFailure { _events.tryEmit("无法启动安装器：${it.message ?: "请重试"}") }
    }

    fun dismissDialog() {
        _state.update { it.copy(dialogDismissed = true) }
    }

    fun reopenDialog() {
        if (_state.value.info != null) _state.update { it.copy(dialogDismissed = false) }
    }

    companion object {
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val AUTO_CHECK_INTERVAL = 6 * 60 * 60 * 1000L

        fun factory(app: PmApp): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdateViewModel(app) as T
        }
    }
}
