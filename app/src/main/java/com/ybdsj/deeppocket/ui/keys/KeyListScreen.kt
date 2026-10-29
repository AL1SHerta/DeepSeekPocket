package com.ybdsj.deeppocket.ui.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ybdsj.deeppocket.R
import com.ybdsj.deeppocket.domain.model.StoredApiKey
import com.ybdsj.deeppocket.platform.AppRestarter
import com.ybdsj.deeppocket.ui.glass.GlassPanel
import com.ybdsj.deeppocket.ui.glass.glassSurfaceColor
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyListScreen(
    modifier: Modifier = Modifier,
    viewModel: KeyListViewModel = viewModel(factory = KeyListViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showRestartHint by rememberSaveable { mutableStateOf(false) }
    var restartHintDontAsk by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<StoredApiKey?>(null) }
    var deleteTarget by remember { mutableStateOf<StoredApiKey?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("API Keys", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            text = "密钥经 Android Keystore 加密后只存在本机",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                expandedHeight = 92.dp,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!state.isLoading && state.keys.isNotEmpty()) {
                // 上移让开底部悬浮的三段式开关
                Box(Modifier.padding(bottom = 84.dp)) {
                    ExtendedFloatingActionButton(
                        onClick = { showAddDialog = true },
                        icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                        text = { Text("添加 Key") },
                    )
                }
            }
        },
    ) { innerPadding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.keys.isEmpty() -> EmptyKeysState(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                onAdd = { showAddDialog = true },
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.keys, key = { it.id }) { key ->
                    KeyCard(
                        key = key,
                        onSelect = { viewModel.setActive(key.id) },
                        onRename = { renameTarget = key },
                        onDelete = { deleteTarget = key },
                    )
                }
                item { SecurityNote() }
            }
        }
    }

    if (showAddDialog) {
        AddKeyDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, apiKey ->
                scope.launch {
                    if (viewModel.addKey(name, apiKey)) {
                        showAddDialog = false
                        // 首次录入后进程内的部分状态还没重新初始化，提示用户重启一次
                        if (viewModel.shouldPromptRestart()) {
                            restartHintDontAsk = false
                            showRestartHint = true
                        }
                    }
                }
            },
        )
    }

    if (showRestartHint) {
        RestartHintDialog(
            dontAskAgain = restartHintDontAsk,
            onDontAskChange = { restartHintDontAsk = it },
            onDismiss = {
                if (restartHintDontAsk) viewModel.disableRestartHint()
                showRestartHint = false
            },
            onRestart = {
                if (restartHintDontAsk) viewModel.disableRestartHint()
                showRestartHint = false
                AppRestarter.restart(context)
            },
        )
    }

    renameTarget?.let { target ->
        RenameKeyDialog(
            currentName = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                scope.launch {
                    if (viewModel.renameKey(target.id, newName)) renameTarget = null
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这个 Key？") },
            text = {
                Text("「${target.name}」删除后需要重新输入才能再次使用。已保存的余额采样不受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteKey(target.id)
                    deleteTarget = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EmptyKeysState(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lock),
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(20.dp))
        Text("还没有 API Key", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "添加一个 DeepSeek API Key 后就能查询账户余额。密钥会加密保存在本机，不会上传到任何第三方服务器。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
            text = { Text("添加 Key") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyCard(
    key: StoredApiKey,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    GlassPanel(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = RoundedCornerShape(20.dp),
        surfaceColor = if (key.isActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            glassSurfaceColor()
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = key.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (key.isActive) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (key.isActive) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Text(
                                text = "使用中",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = key.keyHint,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (key.lastUsedAt != null) {
                        "最近使用：${formatTimestamp(key.lastUsedAt)}"
                    } else {
                        "尚未使用"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "更多操作")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SecurityNote() {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                painter = painterResource(R.drawable.ic_lock),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "密钥由 Android Keystore 加密后存入本机数据库，App 不会把它发送给 DeepSeek 以外的任何服务器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, apiKey: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 API Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text("例如：个人账号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { keyVisible = !keyVisible }) {
                            Text(if (keyVisible) "隐藏" else "显示")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "密钥由 Android Keystore 加密后存入本机数据库，App 不会把它发送给 DeepSeek 以外的任何服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, apiKey) },
                enabled = name.isNotBlank() && apiKey.isNotBlank(),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun RenameKeyDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
) {
    var name by rememberSaveable(currentName) { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 新增 Key 之后的「重启应用」提示。
 *
 * Key 是加密存本机的，运行中的进程里网络层/后台巡检还持有旧配置，个别机型会表现为
 * 查余额报错。重启是最省事的兜底，但不能自动重启：会把正在填表单的用户直接打断。
 */
@Composable
private fun RestartHintDialog(
    dontAskAgain: Boolean,
    onDontAskChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onRestart: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_notification_balance), contentDescription = null) },
        title = { Text("建议重启一次应用") },
        text = {
            Column {
                Text(
                    text = "API Key 已加密保存。为了让运行中的网络请求和后台巡检重新载入新配置，" +
                        "建议现在重启应用，否则可能遇到余额查询失败之类的小问题。",
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "点「立即重启」会先关掉应用再自动拉起；少数手机（如小米）会拦下自动拉起，" +
                        "那时点一下桌面图标重新打开即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dontAskAgain, onCheckedChange = onDontAskChange)
                    Text("以后不再提示", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRestart) { Text("立即重启") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后") }
        },
    )
}

private fun formatTimestamp(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(millis).atZone(zone)
    val today = LocalDate.now(zone)
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    return when (dateTime.toLocalDate()) {
        today -> "今天 ${dateTime.format(timeFormatter)}"
        today.minusDays(1) -> "昨天 ${dateTime.format(timeFormatter)}"
        else -> dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }
}
