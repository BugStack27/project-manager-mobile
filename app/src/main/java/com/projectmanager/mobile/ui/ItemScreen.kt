package com.projectmanager.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmanager.mobile.core.Protocol
import com.projectmanager.mobile.data.ItemKind
import com.projectmanager.mobile.data.PendingItem
import com.projectmanager.mobile.data.RemoteChanges
import com.projectmanager.mobile.data.RemoteDiff
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemScreen(
    item: PendingItem?,
    connectionName: String,
    vm: RemoteViewModel,
    onBack: () -> Unit,
    onSubmit: (decision: String, text: String, onResult: (Boolean) -> Unit) -> Unit
) {
    if (item == null) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            BackBar(title = "待办", onBack = onBack)
            EmptyState("这条待办已处理", "桌面端已经收走它，或连接已被移除。")
        }
        return
    }

    when (item.kind) {
        ItemKind.REVIEW -> ReviewDetail(item, connectionName, vm, onBack, onSubmit)
        ItemKind.QUESTION -> QuestionDetail(item, connectionName, onBack, onSubmit)
    }
}

@Composable
private fun ReviewDetail(
    item: PendingItem,
    connectionName: String,
    vm: RemoteViewModel,
    onBack: () -> Unit,
    onSubmit: (String, String, (Boolean) -> Unit) -> Unit
) {
    var submitting by remember { mutableStateOf(false) }
    var showFeedback by remember { mutableStateOf(false) }
    var showReject by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val projectId = item.projectId
    var changes by remember(item.key) { mutableStateOf<RemoteChanges?>(null) }
    var changesLoading by remember(item.key) { mutableStateOf(false) }
    var changesError by remember(item.key) { mutableStateOf<String?>(null) }
    var diff by remember(item.key) { mutableStateOf<RemoteDiff?>(null) }
    var diffFailure by remember(item.key) { mutableStateOf<Pair<String, String>?>(null) }
    var changesReload by remember(item.key) { mutableIntStateOf(0) }

    LaunchedEffect(item.key, projectId, changesReload) {
        if (projectId == null) return@LaunchedEffect
        changesLoading = true
        changesError = null
        runCatching { vm.fetchChanges(item.connId, projectId) }
            .onSuccess { changes = it }
            .onFailure { changesError = it.message ?: "读取改动失败" }
        changesLoading = false
    }

    val answered = item.isAnswered && item.sentAt != null
    val sendError = item.answerError

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        BackBar(title = "待审核", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag("待审核", warningColor(), warningTint())
                Spacer(Modifier.width(8.dp))
                Text(
                    item.sourceLabel(connectionName),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                item.title,
                fontSize = 25.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp
            )
            Spacer(Modifier.height(16.dp))

            AppCard {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                (item.agentName.ifEmpty { item.agent }.ifEmpty { "?" }).take(1).uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${item.agentName.ifEmpty { item.agent }.ifEmpty { "Agent" }} 已交付",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            item.completedAtMillis?.let { relativeTime(it) } ?: "—",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Text("需求状态", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "进行中 → 待审核",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            if (answered || sendError != null) {
                Spacer(Modifier.height(12.dp))
                SubmissionBanner(
                    answered = answered,
                    error = sendError,
                    onRetry = {
                        val decision = item.answeredDecision ?: "approve"
                        submitting = true
                        onSubmit(decision, item.answerText) { ok ->
                            submitting = false
                            if (ok) onBack()
                        }
                    }
                )
            }

            SectionTitle("完成说明")
            Text(
                item.note.ifBlank { "（没有填写说明）" },
                fontSize = 14.5.sp,
                lineHeight = 25.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (item.note.endsWith("…")) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "说明已按 500 字截断，完整内容请到桌面端查看",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }

            if (projectId != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "本次改动" + (changes?.let { c -> if (c.isRepo && c.files.isNotEmpty()) " · ${c.files.size} 个文件" else "" } ?: ""),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                    Spacer(Modifier.weight(1f))
                    if (!changesLoading) {
                        Text(
                            "刷新",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { changesReload++ }
                        )
                    }
                }
                if (changesError != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "读取改动失败：$changesError",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { changesReload++ }) { Text("重试", fontSize = 12.sp) }
                    }
                }
                val snapshot = changes
                when {
                    snapshot == null && changesLoading -> SmallLoading()
                    snapshot == null -> Unit
                    !snapshot.isRepo -> Hint("这个项目不是 git 仓库，看不到代码改动。")
                    snapshot.files.isEmpty() -> Hint("工作区没有未提交的改动。")
                    else -> snapshot.files.forEach { file ->
                        FileRow(file) {
                            scope.launch {
                                runCatching { vm.fetchDiff(item.connId, projectId, file) }
                                    .onSuccess { diff = it }
                                    .onFailure { diffFailure = file.path to (it.message ?: "读取差异失败") }
                            }
                        }
                    }
                }
            } else {
                SectionTitle("更多")
                AppCard {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("完整说明与 diff", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text("在桌面端查看", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { showFeedback = true },
                enabled = !answered && !submitting,
                modifier = Modifier.weight(0.9f),
                shape = RoundedCornerShape(14.dp)
            ) { Text("打回") }
            OutlinedButton(
                onClick = { showReject = true },
                enabled = !answered && !submitting,
                modifier = Modifier.weight(0.7f),
                shape = RoundedCornerShape(14.dp)
            ) { Text("拒绝", color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    submitting = true
                    onSubmit("approve", "") { ok ->
                        submitting = false
                        if (ok) onBack()
                    }
                },
                enabled = !answered && !submitting,
                modifier = Modifier
                    .weight(1.4f)
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = successColor(),
                    contentColor = Color(0xFF08210F)
                )
            ) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (submitting) "提交中…" else "通过", fontWeight = FontWeight.SemiBold)
            }
        }
    }

    diff?.let { d ->
        DiffSheet(
            title = d.path,
            text = if (d.binary) "二进制文件，不显示内容" else d.text.ifBlank { "没有可显示的差异" },
            footnote = if (d.truncated) "内容过长，已截断" else null,
            isDiff = !d.binary && !d.untracked && d.text.isNotBlank(),
            onClose = { diff = null }
        )
    }
    diffFailure?.let { (path, message) ->
        TextDialog(
            title = path,
            text = message,
            footnote = null,
            onClose = { diffFailure = null }
        )
    }

    if (showFeedback) {
        FeedbackSheet(
            agentName = item.agentName.ifEmpty { item.agent }.ifEmpty { "agent" },
            onDismiss = { showFeedback = false },
            onSubmit = { text ->
                submitting = true
                onSubmit("feedback", text) { ok ->
                    submitting = false
                    if (ok) {
                        showFeedback = false
                        onBack()
                    }
                }
            }
        )
    }

    if (showReject) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReject = false },
            title = { Text("拒绝这条交付？") },
            text = {
                Column {
                    Text(
                        "需求会变为「已拒绝」。可以补充一段可选说明发给 agent。",
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { if (it.length <= Protocol.TEXT_MAX) reason = it },
                        minLines = 3,
                        placeholder = { Text("可选说明") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReject = false
                        submitting = true
                        onSubmit("reject", reason) { ok ->
                            submitting = false
                            if (ok) onBack()
                        }
                    }
                ) { Text("拒绝交付", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showReject = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun QuestionDetail(
    item: PendingItem,
    connectionName: String,
    onBack: () -> Unit,
    onSubmit: (String, String, (Boolean) -> Unit) -> Unit
) {
    var submitting by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    val expiresAt = item.expiresAtMillis
    val expired = expiresAt != null && expiresAt <= now
    val answered = item.isAnswered && item.sentAt != null
    val sendError = item.answerError

    var selected by remember(item.key) {
        val index = if (item.defaultAnswer.isNotBlank()) item.options.indexOf(item.defaultAnswer) else -1
        mutableIntStateOf(index)
    }
    var text by remember(item.key) { mutableStateOf(item.defaultAnswer) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        BackBar(
            title = "待回答",
            onBack = onBack,
            actions = {
                if (expiresAt != null) {
                    androidx.compose.foundation.layout.Box(Modifier.padding(end = 12.dp)) {
                        CountdownPill(expiresAt)
                    }
                }
            }
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag("待回答", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                Spacer(Modifier.width(8.dp))
                Text(
                    item.sourceLabel(connectionName),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "${item.agentName.ifEmpty { item.agent }.ifEmpty { "Agent" }} 在「${item.title}」中提问",
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                item.questionText.ifBlank { item.title },
                fontSize = 19.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp
            )
            if (item.questionText.endsWith("…")) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "问题已按 300 字截断，完整内容请到桌面端查看",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
            Spacer(Modifier.height(16.dp))

            if (item.options.isNotEmpty()) {
                item.options.forEachIndexed { index, option ->
                    OptionRow(
                        text = option,
                        selected = selected == index,
                        recommended = option == item.defaultAnswer,
                        enabled = !expired && !answered,
                        onClick = {
                            selected = index
                            text = option
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.take(Protocol.TEXT_MAX)
                    selected = -1
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                enabled = !expired && !answered,
                placeholder = { Text("写一段回答，提交后桌面端会继续执行…") },
                supportingText = {
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        Text("${text.length}/${Protocol.TEXT_MAX}", fontSize = 10.5.sp)
                    }
                }
            )

            if (expired) {
                Spacer(Modifier.height(12.dp))
                WarningBanner("这条提问已过期，请在桌面端回答。")
            } else if (answered || sendError != null) {
                Spacer(Modifier.height(12.dp))
                SubmissionBanner(
                    answered = answered,
                    error = sendError,
                    onRetry = {
                        val decision = item.answeredDecision ?: "answer"
                        submitting = true
                        onSubmit(decision, item.answerText) { ok ->
                            submitting = false
                            if (ok) onBack()
                        }
                    }
                )
            }
        }

        Button(
            onClick = {
                submitting = true
                onSubmit("answer", text.trim()) { ok ->
                    submitting = false
                    if (ok) onBack()
                }
            },
            enabled = !expired && !answered && !submitting && text.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .height(52.dp)
                .imePadding(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(if (submitting) "提交中…" else "提交答案", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun OptionRow(
    text: String,
    selected: Boolean,
    recommended: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (recommended) {
            Spacer(Modifier.width(6.dp))
            Tag("推荐", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedbackSheet(
    agentName: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
            Text("打回这条交付", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "需求会退回「进行中」，意见会通过当前连接加密发给 $agentName；手机端只保留摘要。",
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("功能不完整", "与需求不符", "存在 bug", "补充测试").forEach { label ->
                    SuggestionChip(
                        onClick = {
                            text = if (text.isBlank()) label else (text + "；" + label).take(Protocol.TEXT_MAX)
                        },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(Protocol.TEXT_MAX) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                placeholder = { Text("写清楚需要改什么…") },
                supportingText = {
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        Text("${text.length}/${Protocol.TEXT_MAX}", fontSize = 10.5.sp)
                    }
                }
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.8f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("取消") }
                Button(
                    onClick = { onSubmit(text.trim()) },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.weight(1.4f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("打回并退回进行中", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun SubmissionBanner(answered: Boolean, error: String?, onRetry: () -> Unit) {
    if (answered) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(successTint())
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = successColor(), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "已提交，等待桌面端确认；未生效会自动重发",
                fontSize = 12.sp,
                color = successColor()
            )
        }
    } else if (error != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "上次发送失败：$error",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) { Text("重新发送") }
        }
    }
}

@Composable
private fun WarningBanner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(warningTint())
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = warningColor(), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = warningColor())
    }
}
