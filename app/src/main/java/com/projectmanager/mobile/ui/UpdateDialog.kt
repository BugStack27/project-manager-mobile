package com.projectmanager.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmanager.mobile.BuildConfig

@Composable
fun UpdateDialog(
    state: UpdateUiState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val info = state.info ?: return
    val downloading = state.phase == UpdatePhase.DOWNLOADING

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("发现新版本 v${info.versionName}") },
        text = {
            Column {
                Text(
                    "当前 v${BuildConfig.VERSION_NAME}" +
                        if (info.sizeBytes > 0) " · 安装包 ${formatSize(info.sizeBytes)}" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                if (info.notes.isNotBlank()) {
                    Text(
                        info.notes,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    )
                } else {
                    Text(
                        "修复问题并带来体验改进，安装后自动保留现有连接与设置。",
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (downloading) {
                    Spacer(Modifier.height(14.dp))
                    val progress = state.progress
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "正在下载 ${progress?.let { "${(it * 100).toInt()}%" } ?: "…"}",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.phase == UpdatePhase.FAILED && state.error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        state.error,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.DOWNLOADING -> TextButton(onClick = {}, enabled = false) {
                    Text("下载中")
                }
                UpdatePhase.DOWNLOADED -> Button(onClick = onInstall) {
                    Text("安装", fontWeight = FontWeight.SemiBold)
                }
                UpdatePhase.FAILED -> Button(onClick = onDownload) {
                    Text("重试", fontWeight = FontWeight.SemiBold)
                }
                else -> Button(onClick = onDownload) {
                    Text("下载更新", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !downloading) { Text("稍后") }
        }
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
