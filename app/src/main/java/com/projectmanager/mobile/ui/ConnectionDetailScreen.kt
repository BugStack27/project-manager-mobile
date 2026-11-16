package com.projectmanager.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.projectmanager.mobile.core.ConnectInfo
import com.projectmanager.mobile.core.Protocol
import com.projectmanager.mobile.data.ConnectionRecord
import com.projectmanager.mobile.data.ConnectionRuntime
import com.projectmanager.mobile.data.LinkFailureKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConnectionDetailScreen(
    connection: ConnectionRecord,
    runtime: ConnectionRuntime?,
    itemCount: Int,
    foreground: Boolean,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onRescan: (ConnectInfo) -> Unit,
    onRemove: () -> Unit,
    onNotify: (String, Boolean) -> Unit
) {
    var showRename by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    var rescanInfo by remember { mutableStateOf<ConnectInfo?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) return@rememberLauncherForActivityResult
        val info = Protocol.parseConnectInfo(contents)
        if (info == null) {
            onNotify("不是项目管理台的连接二维码", true)
        } else {
            rescanInfo = info
        }
    }

    val startScan: () -> Unit = {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("扫描新的连接二维码")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        scanLauncher.launch(options)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        BackBar(title = connection.name, onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            val online = runtime?.connected == true
            val advice = runtime?.adviceKind
            val danger = advice?.needsRescan == true
            val statusColor = when {
                online -> successColor()
                runtime == null -> MaterialTheme.colorScheme.outline
                danger -> MaterialTheme.colorScheme.error
                else -> warningColor()
            }
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(statusColor)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            online -> "已连接"
                            runtime == null -> "连接中…"
                            else -> runtime.statusText
                        },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        runtime?.lastInboundAt != null -> "通道链接更新于 ${relativeTime(runtime.lastInboundAt)}"
                        online -> "通道已连接"
                        else -> "还没有连上通道"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (advice != null) {
                    Spacer(Modifier.height(12.dp))
                    FailureNote(
                        advice = advice,
                        detail = runtime?.failure?.detail,
                        retrying = runtime?.reconnectAt != null,
                        onRescan = startScan
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(0.dp)
                        )
                        .padding(top = 12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
                ) {
                    Metric("$itemCount", "本地待办", Modifier.weight(1f))
                    Metric("${runtime?.pending ?: 0}", "服务端待推送", Modifier.weight(1f))
                    Metric(clockTime(runtime?.sessionStartedAt), "本次连接", Modifier.weight(1f))
                }
            }

            DetailGroup("会话")
            DetailRow("网关", connection.server, mono = true)
            DetailRow("频道", connection.maskedTopic(), mono = true)
            DetailRow("会话建立", SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(connection.addedAt)))
            DetailRow("后台接收", if (foreground) "前台服务运行中" else "未运行", valueColor = if (foreground) successColor() else warningColor())

            DetailGroup("操作")
            ActionRow(
                icon = Icons.Outlined.Edit,
                title = "重命名",
                subtitle = "只改本地显示名",
                onClick = { showRename = true }
            )
            ActionRow(
                icon = Icons.Outlined.QrCodeScanner,
                title = "重新扫码",
                subtitle = "覆盖此连接的频道与密码",
                onClick = startScan
            )
            ActionRow(
                icon = Icons.Outlined.Delete,
                title = "移除连接",
                subtitle = "清除密钥与本地缓存",
                danger = true,
                onClick = { showRemove = true }
            )

            Text(
                "重新扫码只影响这一台桌面端；移除后它的待办会从列表消失，其他连接不受影响。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
            )
        }
    }

    if (showRename) {
        var name by remember { mutableStateOf(connection.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名连接") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(name)
                        showRename = false
                    },
                    enabled = name.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } }
        )
    }

    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text("移除 ${connection.name}？") },
            text = { Text("会清除这台桌面端的连接密钥与本地待办，其他连接不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemove = false
                        onRemove()
                    }
                ) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showRemove = false }) { Text("取消") } }
        )
    }

    rescanInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { rescanInfo = null },
            title = { Text("更新 ${connection.name} 的会话？") },
            text = {
                Text(
                    "将用新二维码里的频道与密码覆盖这一台桌面端；旧频道会失效，其他连接不受影响。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        rescanInfo = null
                        onRescan(info)
                    }
                ) { Text("更新") }
            },
            dismissButton = { TextButton(onClick = { rescanInfo = null }) { Text("取消") } }
        )
    }
}
/**
 * 失败说明：告诉用户「为什么连不上、下一步做什么」。
 * 传输层原文只在最后一行小字露出，方便对日志；需要重新扫码的类别直接给按钮，不必滚到下面的操作区。
 */
@Composable
private fun FailureNote(
    advice: LinkFailureKind,
    detail: String?,
    retrying: Boolean,
    onRescan: () -> Unit
) {
    val accent = if (advice.needsRescan) MaterialTheme.colorScheme.error else warningColor()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(horizontal = 13.dp, vertical = 12.dp)
    ) {
        Text(advice.hint, fontSize = 12.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface)
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                detail,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }
        if (advice.needsRescan) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRescan, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                Text("重新扫码", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = accent)
            }
        } else if (retrying) {
            Spacer(Modifier.height(5.dp))
            Text("正在自动重试…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailGroup(title: String) {
    Text(
        title.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 7.dp)
    )
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    mono: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = if (mono) 11.5.sp else 12.5.sp,
            color = valueColor,
            fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null
        )
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
        }
    }
}
