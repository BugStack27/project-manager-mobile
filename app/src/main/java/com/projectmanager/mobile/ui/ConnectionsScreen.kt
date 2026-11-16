package com.projectmanager.mobile.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.projectmanager.mobile.BuildConfig
import com.projectmanager.mobile.PmApp
import com.projectmanager.mobile.core.ConnectInfo
import com.projectmanager.mobile.core.Protocol
import com.projectmanager.mobile.data.ConnectionRecord
import com.projectmanager.mobile.data.ConnectionRuntime
import com.projectmanager.mobile.notify.Notifier

@Composable
fun ConnectionsScreen(
    state: UiState,
    onOpenConnection: (String) -> Unit,
    onAdd: (ConnectInfo, String) -> Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    onTestAlert: () -> Unit,
    onBatteryPromptShown: () -> Unit
) {
    val context = LocalContext.current

    var pendingInfo by remember { mutableStateOf<ConnectInfo?>(null) }
    var nameDraft by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var notificationsAllowed by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var alertMuted by remember { mutableStateOf(isAlertChannelMuted(context)) }
    var ignoringBattery by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }
    var showBatteryDialog by remember { mutableStateOf(false) }

    // 已有连接的用户（升级上来的）也要引导一次：后台收不到通知大多是电池优化拦了联网
    LaunchedEffect(state.connections.size, ignoringBattery, state.settings.batteryPromptShown) {
        if (state.connections.isNotEmpty() &&
            !ignoringBattery &&
            !state.settings.batteryPromptShown
        ) {
            showBatteryDialog = true
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
                alertMuted = isAlertChannelMuted(context)
                ignoringBattery = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!granted) {
            // 用户拒绝后仍可在系统设置里开启
        }
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun acceptInfo(info: ConnectInfo) {
        val duplicate = state.connections.firstOrNull {
            it.server == info.server && it.topic == info.topic && it.key == info.key
        }
        if (duplicate != null) {
            onOpenConnection(duplicate.id)
            return
        }
        pendingInfo = info
        nameDraft = "桌面端 ${state.connections.size + 1}"
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) return@rememberLauncherForActivityResult
        val info = Protocol.parseConnectInfo(contents)
        if (info == null) {
            scanError = "不是项目管理台的连接二维码，请在桌面端「手机端通信」重新生成"
        } else {
            acceptInfo(info)
        }
    }

    fun launchScan() {
        scanError = null
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("将桌面端的连接二维码放入框内")
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "项目管理台",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "连接",
                        fontSize = 30.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${state.connections.size} 台",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
            IconButton(
                onClick = { launchScan() },
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "添加桌面端", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val online = state.runtimes.values.count { it.desktopOnline() }
        val reconnecting = state.connections.size - online
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(if (online > 0) successColor() else warningColor())
            Spacer(Modifier.width(6.dp))
            Text("$online 台在线", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (reconnecting > 0) {
                Spacer(Modifier.width(10.dp))
                Text("·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Spacer(Modifier.width(10.dp))
                StatusDot(warningColor())
                Spacer(Modifier.width(6.dp))
                Text("$reconnecting 台重连中", fontSize = 12.sp, color = warningColor())
            }
            Spacer(Modifier.weight(1f))
            Text("待办 ${state.items.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            if (state.connections.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有连接桌面端",
                        subtitle = "每台桌面端有一条独立连接；扫码后可以同时接收多台电脑的待审核与提问。",
                        action = {
                            Button(onClick = { launchScan() }) { Text("扫码添加") }
                        }
                    )
                }
            } else {
                items(state.connections.size, key = { state.connections[it].id }) { index ->
                    val connection = state.connections[index]
                    ConnectionCard(
                        connection = connection,
                        runtime = state.runtimes[connection.id],
                        pending = state.items.count { it.connId == connection.id },
                        onClick = { onOpenConnection(connection.id) }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .clickable { launchScan() }
                        .padding(vertical = 15.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加桌面端", color = MaterialTheme.colorScheme.primary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showManual = true }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("手动输入连接信息", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (!notificationsAllowed) {
                item {
                    NoticeCard(
                        title = "通知权限未开启",
                        body = "后台收到待审核 / 待回答时无法提醒。开启通知，才能不盯着手机也不错过。",
                        action = "去开启",
                        onAction = { requestNotifications() }
                    )
                }
            }

            item { GroupTitle("通知") }
            item {
                SettingRow(
                    title = "新审核与提问提醒",
                    subtitle = "汇总多台桌面端的待办",
                    trailing = {
                        Switch(
                            checked = state.settings.notificationsEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) requestNotifications()
                                onToggleNotifications(enabled)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = successColor()
                            )
                        )
                    }
                )
            }
            item {
                SettingRow(
                    title = "试听提醒",
                    subtitle = "确认铃声与震动是否正常",
                    trailing = {
                        Text(
                            "试听",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    onClick = onTestAlert
                )
            }
            if (alertMuted && notificationsAllowed) {
                item {
                    NoticeCard(
                        title = "提醒被系统静音",
                        body = "「待审核与提问」渠道的铃声或震动被关掉了，收到待办时只有通知没有提醒。",
                        action = "去设置",
                        onAction = { openAlertChannelSettings(context) }
                    )
                }
            }

            item { GroupTitle("后台接收") }
            item {
                SettingRow(
                    title = "前台服务",
                    subtitle = "退出应用后仍保持 ${state.connections.size} 条连接",
                    trailing = {
                        Text(
                            if (state.foreground) "运行中" else "未运行",
                            fontSize = 12.5.sp,
                            color = if (state.foreground) successColor() else warningColor()
                        )
                    }
                )
            }
            item {
                SettingRow(
                    title = "电池优化",
                    subtitle = if (ignoringBattery) "已允许后台运行" else "部分系统会限制后台网络，建议设为不限制",
                    trailing = {
                        Text(
                            if (ignoringBattery) "不受限制" else "去设置",
                            fontSize = 12.5.sp,
                            color = if (ignoringBattery) successColor() else warningColor()
                        )
                    },
                    onClick = { requestIgnoreBattery(context) }
                )
            }
            item {
                Text(
                    "部分国产系统还需要在「手机管家 / 自启动管理」里允许本项目后台运行。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                )
            }

            item { GroupTitle("关于") }
            item {
                SettingRow(title = "加密", subtitle = "消息先在本机加密，中转服务只看到密文", trailing = { Text("AES-256-GCM", fontSize = 12.5.sp) })
            }
            item {
                SettingRow(
                    title = "版本",
                    subtitle = "协议 v1",
                    trailing = { Text("v${BuildConfig.VERSION_NAME}", fontSize = 12.5.sp) }
                )
            }
            item {
                Text(
                    "连接密钥持有即授权；二维码截图请勿外传，丢失后到桌面端重新生成会话。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                )
            }
        }
    }

    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = {
                showBatteryDialog = false
                onBatteryPromptShown()
            },
            title = { Text("让后台能收到通知") },
            text = {
                Text(
                    "系统省电策略会限制后台联网：App 退到后台后可能收不到新待办，只有打开 App 才补收到。\n\n" +
                        "把本项目设为「不限制」电池优化，后台才能继续拉取并立即提醒。部分系统还需要在「手机管家 / 自启动管理」里允许后台运行。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryDialog = false
                    onBatteryPromptShown()
                    requestIgnoreBattery(context)
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBatteryDialog = false
                    onBatteryPromptShown()
                }) { Text("稍后") }
            }
        )
    }

    if (pendingInfo != null) {
        NameConnectionDialog(
            initial = nameDraft,
            server = pendingInfo!!.server,
            topic = pendingInfo!!.topic,
            onCancel = { pendingInfo = null },
            onConfirm = { name ->
                val info = pendingInfo
                if (info != null) {
                    val added = onAdd(info, name)
                    pendingInfo = null
                    if (added) requestNotifications()
                }
            }
        )
    }

    if (showManual) {
        ManualConnectDialog(
            onCancel = { showManual = false },
            onParsed = { info ->
                showManual = false
                acceptInfo(info)
            },
            onError = { message ->
                scanError = message
            }
        )
    }

    scanError?.let { message ->
        AlertDialog(
            onDismissRequest = { scanError = null },
            title = { Text("无法添加") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { scanError = null }) { Text("知道了") } }
        )
    }
}

@Composable
private fun ConnectionCard(
    connection: ConnectionRecord,
    runtime: ConnectionRuntime?,
    pending: Int,
    onClick: () -> Unit
) {
    val online = runtime?.desktopOnline() == true
    val statusColor = when {
        online -> successColor()
        runtime == null -> MaterialTheme.colorScheme.outline
        else -> warningColor()
    }
    AppCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp).clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (online) MaterialTheme.colorScheme.surfaceVariant else warningTint()),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Computer,
                    contentDescription = null,
                    tint = if (online) MaterialTheme.colorScheme.onSurfaceVariant else warningColor(),
                    modifier = Modifier.size(21.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        connection.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (online) "在线" else if (runtime?.connected == true) "等待心跳" else "重连中",
                        fontSize = 11.sp,
                        color = statusColor
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append(connection.host)
                        append(" · 待办 ")
                        append(pending)
                        append(" · ")
                        append(runtime?.lastInboundAt?.let { relativeTime(it) } ?: "尚无心跳")
                    },
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 7.dp)
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun NoticeCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(warningTint())
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = warningColor(), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = warningColor())
            Spacer(Modifier.height(2.dp))
            Text(body, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onAction) { Text(action, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun NameConnectionDialog(
    initial: String,
    server: String,
    topic: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("命名这台桌面端") },
        text = {
            Column {
                Text(
                    "频道已识别，名称只在本机显示，之后可以随时重命名。",
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        append("网关 ")
                        append(server)
                        append(" · ")
                        append(if (topic.length > 12) topic.take(6) + "••••" + topic.takeLast(4) else topic)
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) { Text("添加并连接") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } }
    )
}

@Composable
private fun ManualConnectDialog(
    onCancel: () -> Unit,
    onParsed: (ConnectInfo) -> Unit,
    onError: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("手动输入连接信息") },
        text = {
            Column {
                Text(
                    "粘贴桌面端二维码里的整段 JSON（v2 接入点信息）。",
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 4,
                    maxLines = 7,
                    placeholder = { Text("{\"v\":2,\"type\":\"pm-mobile\",\"provider\":\"pushgo\",...}") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val info = Protocol.parseConnectInfo(text)
                    if (info == null) {
                        onError("连接信息不完整或不是项目管理台的二维码")
                    } else {
                        onParsed(info)
                    }
                },
                enabled = text.isNotBlank()
            ) { Text("下一步") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } }
    )
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return false
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

/** 「待审核与提问」渠道整体被关闭，或铃声与震动都被关掉。 */
private fun isAlertChannelMuted(context: android.content.Context): Boolean =
    (context.applicationContext as? PmApp)?.container?.notifier?.isAlertChannelMuted() ?: false

private fun openAlertChannelSettings(context: android.content.Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.TODO_CHANNEL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {
        openAppNotificationSettings(context)
    } catch (_: Exception) {
    }
}

private fun openAppNotificationSettings(context: android.content.Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
    }
}

private fun requestIgnoreBattery(context: android.content.Context) {
    try {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
        }
    } catch (_: Exception) {
    }
}
