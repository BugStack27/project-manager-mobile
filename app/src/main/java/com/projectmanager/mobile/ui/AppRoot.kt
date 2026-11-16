package com.projectmanager.mobile.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.projectmanager.mobile.PmApp
import com.projectmanager.mobile.data.REMOTE_CONN_ID
import kotlinx.coroutines.flow.StateFlow

data class OpenTarget(val connId: String, val messageId: String)

@Composable
fun AppRoot(
    app: PmApp,
    openTarget: StateFlow<OpenTarget?>,
    onConsumeTarget: () -> Unit
) {
    val vm: AppViewModel = viewModel(factory = AppViewModel.factory(app))
    val state by vm.state.collectAsStateWithLifecycle()
    val remoteVm: RemoteViewModel = viewModel(factory = RemoteViewModel.factory(app))
    val remoteState by remoteVm.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    /** 远程面的操作回执（采纳成功、打回失败…）：由 ViewModel 抛一次，显示完清掉 */
    LaunchedEffect(remoteState.notice) {
        val text = remoteState.notice ?: return@LaunchedEffect
        remoteVm.consumeNotice()
        snackbarHostState.showSnackbar(text)
    }

    val target by openTarget.collectAsStateWithLifecycle()
    LaunchedEffect(target) {
        val current = target ?: return@LaunchedEffect
        navController.navigate("item/${Uri.encode(current.connId)}/${Uri.encode(current.messageId)}") {
            launchSingleTop = true
        }
        onConsumeTarget()
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showTabs = route == "todos" || route == "connections" || route == "remote"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.navigationBars,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showTabs) {
                BottomTabs(
                    selected = route ?: "todos",
                    todoCount = state.items.count { it.sentAt == null },
                    remoteCount = remoteState.pendingCount,
                    onSelect = { destination ->
                        navController.navigate(destination) {
                            popUpTo("todos") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "todos",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable("todos") {
                TodoScreen(
                    state = state,
                    onAddConnection = {
                        navController.navigate("connections") { launchSingleTop = true }
                    },
                    onOpenItem = { item ->
                        navController.navigate("item/${Uri.encode(item.connId)}/${Uri.encode(item.messageId)}")
                    },
                    onRefresh = vm::refresh
                )
            }
            composable("connections") {
                ConnectionsScreen(
                    state = state,
                    onOpenConnection = { id ->
                        navController.navigate("connections/${Uri.encode(id)}")
                    },
                    onAdd = { info, name ->
                        val result = vm.addConnection(info, name)
                        if (result.alreadyExists) {
                            vm.notify("这台桌面端已连接")
                            false
                        } else {
                            vm.notify("已添加 ${result.record.name}")
                            true
                        }
                    },
                    onToggleNotifications = vm::setNotificationsEnabled,
                    onTestAlert = vm::testAlert,
                    onBatteryPromptShown = vm::markBatteryPromptShown
                )
            }
            composable("connections/{connId}") { entry ->
                val connId = entry.arguments?.getString("connId").orEmpty()
                val connection = state.connections.firstOrNull { it.id == connId }
                if (connection == null) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                    ) {
                        BackBar(title = "连接", onBack = { navController.popBackStack() })
                        EmptyState("连接已移除", "这台桌面端的密钥与本地待办已经清除。")
                    }
                } else {
                    ConnectionDetailScreen(
                        connection = connection,
                        runtime = state.runtimes[connection.id],
                        itemCount = state.items.count { it.connId == connection.id },
                        foreground = state.foreground,
                        onBack = { navController.popBackStack() },
                        onRename = { name ->
                            vm.renameConnection(connection.id, name)
                            vm.notify("已重命名为 $name")
                        },
                        onRescan = { info ->
                            vm.replaceSession(connection.id, info)
                            vm.notify("已更新 ${connection.name} 的会话")
                        },
                        onRemove = {
                            val name = connection.name
                            vm.removeConnection(connection.id)
                            vm.notify("已移除 $name")
                            navController.popBackStack()
                        },
                        onNotify = { message, error -> vm.notify(message, error) }
                    )
                }
            }
            composable("item/{connId}/{messageId}") { entry ->
                val connId = entry.arguments?.getString("connId").orEmpty()
                val messageId = entry.arguments?.getString("messageId").orEmpty()
                val item = state.items.firstOrNull { it.connId == connId && it.messageId == messageId }
                val connectionName = state.connections.firstOrNull { it.id == connId }?.name
                    ?: if (connId == REMOTE_CONN_ID) "桌面端" else "已移除的连接"
                ItemScreen(
                    item = item,
                    connectionName = connectionName,
                    onBack = { navController.popBackStack() },
                    onSubmit = { decision, text, onResult ->
                        if (item == null) {
                            onResult(false)
                        } else {
                            vm.answer(item, decision, text, onResult)
                        }
                    }
                )
            }
            /* ── 项目 / 需求：走桌面端 /remote 接口，与 PushGo 待办互不干扰 ── */
            composable("remote") {
                RemoteProjectsScreen(
                    state = remoteState,
                    onRefresh = remoteVm::refresh,
                    onOpenProject = { project ->
                        navController.navigate("remote/p/${Uri.encode(project.id)}")
                    }
                )
            }
            composable("remote/p/{projectId}") { entry ->
                val projectId = entry.arguments?.getString("projectId").orEmpty()
                val project = remoteState.projects.firstOrNull { it.id == projectId }
                LaunchedEffect(projectId) { remoteVm.loadRequirements(projectId) }
                RemoteRequirementsScreen(
                    state = remoteState,
                    projectName = project?.name.orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenRequirement = { requirement ->
                        navController.navigate(
                            "remote/p/${Uri.encode(projectId)}/r/${Uri.encode(requirement.id)}"
                        )
                    },
                    onAdopt = { requirement -> remoteVm.setStatus(requirement.id, "todo") },
                    onReject = { requirement -> remoteVm.setStatus(requirement.id, "rejected") }
                )
            }
            composable("remote/p/{projectId}/r/{rid}") { entry ->
                val projectId = entry.arguments?.getString("projectId").orEmpty()
                val rid = entry.arguments?.getString("rid").orEmpty()
                LaunchedEffect(projectId, rid) {
                    if (remoteState.requirements.none { it.id == rid }) remoteVm.loadRequirements(projectId)
                }
                RemoteRequirementScreen(
                    vm = remoteVm,
                    state = remoteState,
                    projectId = projectId,
                    requirement = remoteState.requirements.firstOrNull { it.id == rid },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
private fun BottomTabs(selected: String, todoCount: Int, remoteCount: Int, onSelect: (String) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp
    ) {
        NavigationBarItem(
            selected = selected == "todos",
            onClick = { onSelect("todos") },
            icon = {
                BadgedBox(badge = {
                    if (todoCount > 0) {
                        Badge(containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White) {
                            Text("$todoCount", fontSize = 10.sp)
                        }
                    }
                }) {
                    Icon(Icons.AutoMirrored.Outlined.FactCheck, contentDescription = null)
                }
            },
            label = { Text("待办", fontSize = 10.5.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        NavigationBarItem(
            selected = selected == "remote",
            onClick = { onSelect("remote") },
            icon = {
                BadgedBox(badge = {
                    if (remoteCount > 0) {
                        Badge(containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White) {
                            Text("$remoteCount", fontSize = 10.sp)
                        }
                    }
                }) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                }
            },
            label = { Text("项目", fontSize = 10.5.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        NavigationBarItem(
            selected = selected == "connections",
            onClick = { onSelect("connections") },
            icon = { Icon(Icons.Outlined.Link, contentDescription = null) },
            label = { Text("连接", fontSize = 10.5.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}
