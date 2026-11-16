package com.projectmanager.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmanager.mobile.data.ConnectionRuntime
import com.projectmanager.mobile.data.ItemKind
import com.projectmanager.mobile.data.PendingItem

@Composable
fun TodoScreen(
    state: UiState,
    onAddConnection: () -> Unit,
    onOpenItem: (PendingItem) -> Unit,
    onRefresh: () -> Unit
) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val connectionsById = state.connections.associateBy { it.id }
    val effectiveFilter = filter?.takeIf { connectionsById.containsKey(it) }

    val filtered = state.items.filter { effectiveFilter == null || it.connId == effectiveFilter }
    val reviews = filtered.filter { it.kind == ItemKind.REVIEW }
        .sortedByDescending { it.completedAtMillis ?: it.atMillis }
    val questions = filtered.filter { it.kind == ItemKind.QUESTION }
        .sortedWith(
            compareBy(
                { if (it.isExpired()) 1 else 0 },
                { it.expiresAtMillis ?: Long.MAX_VALUE }
            )
        )

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.Top
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
                        "待办",
                        fontSize = 30.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${state.items.size} 条",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "重新同步",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.connections.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            ) {
                items(state.connections, key = { it.id }) { connection ->
                    val runtime = state.runtimes[connection.id]
                    ConnectionStatusItem(connection.name, runtime)
                }
            }
        }

        if (state.connections.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            ) {
                item {
                    FilterChip(
                        selected = effectiveFilter == null,
                        onClick = { filter = null },
                        label = { Text("全部 ${state.items.size}", fontSize = 12.5.sp) },
                        shape = RoundedCornerShape(999.dp),
                        colors = chipColors()
                    )
                }
                items(state.connections, key = { it.id }) { connection ->
                    val count = state.items.count { it.connId == connection.id }
                    FilterChip(
                        selected = effectiveFilter == connection.id,
                        onClick = { filter = if (filter == connection.id) null else connection.id },
                        label = { Text("${connection.name} $count", fontSize = 12.5.sp) },
                        shape = RoundedCornerShape(999.dp),
                        colors = chipColors()
                    )
                }
            }
        }

        when {
            state.connections.isEmpty() -> {
                EmptyState(
                    title = "还没有连接桌面端",
                    subtitle = "在桌面端打开「设置与数据 → 通知与集成 → 手机端通信」，扫码即可开始接收待审核与提问。",
                    action = {
                        Button(onClick = onAddConnection) { Text("添加桌面端") }
                    }
                )
            }

            filtered.isEmpty() -> {
                EmptyState(
                    title = "暂时没有待处理的事项",
                    subtitle = "只有等待审核的交付、或等待回答的提问才会出现在这里；其余时间保持后台连接即可。"
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (reviews.isNotEmpty()) {
                        item { SectionHeader("待审核", reviews.size) }
                        items(reviews, key = { it.key }) { item ->
                            val name = connectionsById[item.connId]?.name ?: "已移除的连接"
                            TaskCard(item, name) { onOpenItem(item) }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (questions.isNotEmpty()) {
                        item { SectionHeader("待回答", questions.size) }
                        items(questions, key = { it.key }) { item ->
                            val name = connectionsById[item.connId]?.name ?: "已移除的连接"
                            TaskCard(item, name) { onOpenItem(item) }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusItem(name: String, runtime: ConnectionRuntime?) {
    val needsRescan = runtime?.adviceKind?.needsRescan == true
    val color = when {
        runtime == null -> MaterialTheme.colorScheme.outline
        runtime.connected -> successColor()
        needsRescan -> MaterialTheme.colorScheme.error
        else -> warningColor()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color)
        Spacer(Modifier.width(6.dp))
        Text(name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(
            runtime?.chipText ?: "连接中",
            fontSize = 11.sp,
            color = color
        )
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.onBackground,
    selectedLabelColor = MaterialTheme.colorScheme.background,
    containerColor = Color.Transparent,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
)
