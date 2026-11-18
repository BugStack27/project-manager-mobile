package com.projectmanager.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.projectmanager.mobile.data.RemoteChangeFile
import com.projectmanager.mobile.data.RemoteChanges
import com.projectmanager.mobile.data.RemoteCommand
import com.projectmanager.mobile.data.RemoteDiff
import com.projectmanager.mobile.data.RemoteLogs
import com.projectmanager.mobile.data.RemoteProject
import com.projectmanager.mobile.data.RemoteRequirement
import kotlinx.coroutines.launch

/**
 * 项目 / 需求这条线（走桌面端 /remote 接口）。
 *
 * 手机在这里只做三件事：看有什么、看交付的 diff 与命令输出、对需求表态（采纳 / 拒绝 / 通过 / 打回 / 回答）。
 * 认领、交付、提问是 agent 在本机做的，手机上没有对应入口。
 */

@Composable
fun RemoteProjectsScreen(
    state: RemoteState,
    onRefresh: () -> Unit,
    onOpenProject: (RemoteProjectEntry) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        PageHeader(
            title = "项目",
            subtitle = when {
                state.desktops.isEmpty() -> "等你完成配对"
                !state.ready -> "等待桌面端地址"
                state.desktops.size == 1 -> {
                    val desktop = state.desktops.first()
                    "${desktop.connectionName} · ${desktop.status}"
                }
                else -> "${state.desktops.count { it.online }}/${state.desktops.size} 台在线 · 共 ${state.projectCount} 个项目"
            },
            subtitleColor = when {
                state.desktops.isEmpty() || !state.ready -> MaterialTheme.colorScheme.onSurfaceVariant
                state.online -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.error
            },
            actions = {
                if (state.ready) {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        )

        when {
            state.desktops.isEmpty() -> EmptyState(
                title = "还没有连接桌面端",
                subtitle = "先在「连接」里扫码配对；每台电脑的项目会分开显示，并标注来自哪台机器。"
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.desktops.forEach { desktop ->
                    item(key = "desktop-${desktop.connectionId}") {
                        DesktopHeader(desktop)
                    }
                    if (!desktop.ready) {
                        item(key = "waiting-${desktop.connectionId}") {
                            Text(
                                "还没收到这台电脑的远程地址，桌面端开启手机端通信后会自动同步。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                            )
                        }
                    } else if (desktop.loading && desktop.projects.isEmpty()) {
                        item(key = "loading-${desktop.connectionId}") { LoadingRow() }
                    } else if (desktop.error != null) {
                        item(key = "error-${desktop.connectionId}") {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "读取失败：${desktop.error}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onRefresh) { Text("重试", fontSize = 12.sp) }
                            }
                        }
                    } else if (desktop.projects.isEmpty()) {
                        item(key = "empty-${desktop.connectionId}") {
                            Text(
                                "这台电脑还没有登记项目。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                            )
                        }
                    }
                    items(desktop.projects, key = { "${desktop.connectionId}|${it.id}" }) { project ->
                        ProjectCard(project) {
                            onOpenProject(
                                RemoteProjectEntry(desktop.connectionId, desktop.connectionName, project)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 每台电脑一组：项目同名时靠这一行区分是哪台机器的 */
@Composable
private fun DesktopHeader(desktop: RemoteDesktopState) {
    val color = when {
        desktop.online -> MaterialTheme.colorScheme.primary
        desktop.error != null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(color)
        Spacer(Modifier.width(6.dp))
        Text(
            desktop.connectionName,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (desktop.ready) "${desktop.projects.size} 个项目" else "等待地址",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun ProjectCard(project: RemoteProject, onClick: () -> Unit) {
    AppCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    project.name.ifBlank { "未命名项目" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (project.path.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        project.path,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun RemoteRequirementsScreen(
    state: RemoteState,
    desktopName: String,
    projectName: String,
    onBack: () -> Unit,
    onOpenRequirement: (RemoteRequirement) -> Unit,
    onAdopt: (RemoteRequirement) -> Unit,
    onReject: (RemoteRequirement) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        BackBar(title = projectName.ifBlank { "需求" }, onBack = onBack)
        if (desktopName.isNotBlank()) {
            Row(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Tag(
                    desktopName,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "这个项目的需求都在这台电脑上",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        when {
            state.loading && state.requirements.isEmpty() -> LoadingRow()
            state.requirements.isEmpty() -> EmptyState(
                title = state.error ?: "还没有需求",
                subtitle = if (state.error != null) state.error else "这个项目下暂无需求。"
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.requirements, key = { it.id }) { req ->
                    RequirementCard(
                        req = req,
                        onClick = { onOpenRequirement(req) },
                        onAdopt = { onAdopt(req) },
                        onReject = { onReject(req) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RequirementCard(
    req: RemoteRequirement,
    onClick: () -> Unit,
    onAdopt: () -> Unit,
    onReject: () -> Unit
) {
    AppCard(modifier = Modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusTag(req.statusLabel, req.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (req.seq != null) "#${req.seq}" else "",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                if (req.questions.isNotEmpty()) {
                    Tag("${req.questions.size} 个提问", warningColor(), warningTint())
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                req.title.ifBlank { "（无标题）" },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val excerpt = req.completion?.note?.ifBlank { req.body } ?: req.body
            if (excerpt.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    excerpt,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (req.status == "candidate") {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("采纳", primary = true, onClick = onAdopt)
                    SmallButton("拒绝", primary = false, onClick = onReject)
                }
            }
        }
    }
}

@Composable
fun RemoteRequirementScreen(
    vm: RemoteViewModel,
    connId: String,
    desktopName: String,
    projectName: String,
    projectId: String,
    requirement: RemoteRequirement?,
    onBack: () -> Unit
) {
    if (requirement == null) {
        Column(Modifier.fillMaxSize()) {
            BackBar(title = "需求", onBack = onBack)
            EmptyState("需求已不存在", "可能已被归档或删除。")
        }
        return
    }

    val scope = rememberCoroutineScope()
    var changes by remember { mutableStateOf<RemoteChanges?>(null) }
    var commands by remember { mutableStateOf<List<RemoteCommand>>(emptyList()) }
    var loadingExtra by remember { mutableStateOf(false) }
    var diff by remember { mutableStateOf<RemoteDiff?>(null) }
    var logs by remember { mutableStateOf<RemoteLogs?>(null) }
    var logsTitle by remember { mutableStateOf("") }
    var textFor by remember { mutableStateOf<String?>(null) }
    var answerDraft by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }

    LaunchedEffect(requirement.id) {
        loadingExtra = true
        runCatching { vm.fetchChanges(connId, projectId) }.onSuccess { changes = it }
        runCatching { vm.fetchCommands(connId, projectId) }.onSuccess { commands = it }
        loadingExtra = false
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(title = if (requirement.seq != null) "#${requirement.seq}" else "需求", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusTag(requirement.statusLabel, requirement.status)
                Spacer(Modifier.width(8.dp))
                if (requirement.source == "ai") Tag("AI 提出", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                listOf(desktopName, projectName).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                requirement.title,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 26.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (requirement.body.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    requirement.body,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (requirement.completion != null && requirement.completion.note.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                SectionTitle("交付说明")
                Text(
                    requirement.completion.note,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            /* ── 表态 ── */
            Spacer(Modifier.height(18.dp))
            RequirementActions(
                req = requirement,
                submitting = submitting,
                answerDraft = answerDraft,
                onDraftChange = { answerDraft = it },
                onSubmit = { decision, text, qid ->
                    submitting = true
                    vm.answer(connId, requirement.id, decision, text, qid) { submitting = false }
                },
                onStatus = { status -> vm.setStatus(connId, requirement.id, status) },
                onFeedback = { textFor = "打回意见" }
            )

            /* ── 改动 ── */
            Spacer(Modifier.height(22.dp))
            SectionTitle("本次改动" + (changes?.let { if (it.isRepo) "" else "（不是 git 仓库）" } ?: ""))
            val files = changes?.files.orEmpty()
            when {
                loadingExtra && changes == null -> SmallLoading()
                files.isEmpty() -> Hint("工作区没有未提交的改动。")
                else -> files.forEach { file ->
                    FileRow(file) {
                        scope.launch {
                            runCatching { vm.fetchDiff(connId, projectId, file) }
                                .onSuccess { diff = it }
                        }
                    }
                }
            }

            /* ── 命令 ── */
            Spacer(Modifier.height(20.dp))
            SectionTitle("命令输出")
            if (commands.isEmpty()) {
                Hint("这个项目还没有登记命令。")
            } else {
                commands.forEach { cmd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    runCatching { vm.fetchLogs(connId, cmd.id) }.onSuccess {
                                        logs = it
                                        logsTitle = cmd.name.ifBlank { cmd.text }
                                    }
                                }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                cmd.name.ifBlank { cmd.text },
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (cmd.name.isNotBlank()) {
                                Text(
                                    cmd.text,
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (cmd.running) Tag("运行中", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (diff != null) {
        TextDialog(
            title = diff!!.path,
            text = if (diff!!.binary) "二进制文件，不显示内容" else diff!!.text.ifBlank { "没有可显示的差异" },
            footnote = if (diff!!.truncated) "内容过长，已截断" else null,
            onClose = { diff = null }
        )
    }
    if (logs != null) {
        TextDialog(
            title = logsTitle,
            text = logs!!.lines.joinToString("\n") { line ->
                if (line.t.isBlank()) line.text else "${line.t}  ${line.text}"
            }.ifBlank { "还没有输出" },
            footnote = if (logs!!.truncated) "只显示最后一段" else null,
            onClose = { logs = null }
        )
    }
    textFor?.let { label ->
        // 打回 / 继续回复都要写清楚改什么，统一走同一个输入弹窗
        InputDialog(
            title = label,
            hint = "写清楚要改什么，agent 会照这个改",
            onCancel = { textFor = null },
            onConfirm = { text ->
                textFor = null
                submitting = true
                vm.answer(connId, requirement.id, "feedback", text, null) { submitting = false }
            }
        )
    }
}

@Composable
private fun RequirementActions(
    req: RemoteRequirement,
    submitting: Boolean,
    answerDraft: String,
    onDraftChange: (String) -> Unit,
    onSubmit: (decision: String, text: String?, questionId: String?) -> Unit,
    onStatus: (String) -> Unit,
    onFeedback: () -> Unit
) {
    when (req.status) {
        "candidate" -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryAction("采纳", submitting) { onStatus("todo") }
            OutlinedButton(onClick = { onStatus("rejected") }, enabled = !submitting) { Text("拒绝") }
        }

        "review" -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryAction("通过并提交", submitting) { onSubmit("approve", null, null) }
            OutlinedButton(onClick = onFeedback, enabled = !submitting) { Text("打回") }
            OutlinedButton(onClick = { onSubmit("reject", null, null) }, enabled = !submitting) { Text("拒绝") }
        }

        "doing" -> {
            val q = req.questions.firstOrNull()
            if (q == null) {
                Hint("agent 正在做，还没有要你拍板的问题。")
            } else {
                Column {
                    Text(q.text, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                    if (q.options.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        q.options.forEach { opt ->
                            OutlinedButton(
                                onClick = { onSubmit("answer", opt, q.id) },
                                enabled = !submitting,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(opt, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = answerDraft,
                        onValueChange = onDraftChange,
                        label = { Text("或者直接回答") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    PrimaryAction("提交回答", submitting || answerDraft.isBlank()) {
                        onSubmit("answer", answerDraft.trim(), q.id)
                    }
                }
            }
        }

        else -> Hint("这条需求当前没有需要你操作的地方。")
    }
}

@Composable
private fun PrimaryAction(text: String, disabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Button(onClick = onClick, enabled = !disabled) {
        Text(text, fontSize = 13.5.sp)
    }
}

@Composable
private fun SmallButton(text: String, primary: Boolean, onClick: () -> Unit) {
    if (primary) {
        androidx.compose.material3.Button(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            Text(text, fontSize = 12.5.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            Text(text, fontSize = 12.5.sp)
        }
    }
}

@Composable
private fun TextDialog(title: String, text: String, footnote: String?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                .padding(16.dp)
        ) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(
                        text,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (footnote != null) {
                Spacer(Modifier.height(8.dp))
                Text(footnote, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun InputDialog(
    title: String,
    hint: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("提交") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } }
    )
}

@Composable
private fun PageHeader(
    title: String,
    subtitle: String,
    subtitleColor: Color,
    actions: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, fontSize = 11.5.sp, color = subtitleColor)
        }
        actions()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
}

@Composable
private fun FileRow(file: RemoteChangeFile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                file.path,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (file.added > 0) Text("+${file.added}", fontSize = 11.sp, color = successColor())
        if (file.removed > 0) {
            Spacer(Modifier.width(6.dp))
            Text("-${file.removed}", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun StatusTag(label: String, status: String) {
    val (fg, bg) = when (status) {
        "review" -> warningColor() to warningTint()
        "doing" -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        "candidate" -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant
        "done" -> successColor() to successColor().copy(alpha = 0.14f)
        "rejected" -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant
    }
    Tag(label, fg, bg)
}

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun SmallLoading() {
    Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}
