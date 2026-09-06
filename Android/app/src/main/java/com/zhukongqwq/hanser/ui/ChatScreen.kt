package com.zhukongqwq.hanser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhukongqwq.hanser.AppCore
import com.zhukongqwq.hanser.MainViewModel
import com.zhukongqwq.hanser.core.AppUpdate
import com.zhukongqwq.hanser.core.EndpointConfig
import com.zhukongqwq.hanser.ui.HanserColors.Accent
import com.zhukongqwq.hanser.ui.HanserColors.AccentDeep
import com.zhukongqwq.hanser.ui.HanserColors.AccentSoft
import com.zhukongqwq.hanser.ui.HanserColors.Background
import com.zhukongqwq.hanser.ui.HanserColors.Border
import com.zhukongqwq.hanser.ui.HanserColors.BubbleAssistant
import com.zhukongqwq.hanser.ui.HanserColors.BubbleText
import com.zhukongqwq.hanser.ui.HanserColors.BubbleUser
import com.zhukongqwq.hanser.ui.HanserColors.TextPrimary
import com.zhukongqwq.hanser.ui.HanserColors.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 顶部自绘标题栏。 */
@Composable
private fun TopBar(onSettings: () -> Unit, onClear: () -> Unit, onDocSearch: () -> Unit, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).background(Accent, CircleShape))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("内容为 AI 生成，请勿断章取义", color = TextSecondary, fontSize = 9.sp, maxLines = 1)
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onDocSearch) { Text("📚 文档库", color = AccentDeep, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        TextButton(onClick = onClear) { Text("清空", color = TextSecondary, fontSize = 13.sp) }
        TextButton(onClick = onSettings) { Text("设置", color = AccentDeep, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
    }
    HorizontalDivider(color = Border)
}

/** 流程气泡：打字机式阶段提示（小兔/普罗米修斯/憨憨）。 */
@Composable
private fun StageBubble(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Surface(
            color = AccentSoft,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.background(Color.Transparent)
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = Accent,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text(text, color = AccentDeep, fontSize = 12.sp)
            }
        }
    }
}

/** 用户 / 憨憨消息气泡。 */
@Composable
private fun MessageBubble(msg: com.zhukongqwq.hanser.UiMessage, onToggleDocs: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        when (msg.role) {
            "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(color = BubbleUser, shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)) {
                    Text(msg.content, color = BubbleText, fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp))
                }
            }
            "system" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(msg.content, color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Box(Modifier.size(30.dp).background(Accent, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text("憨", color = BubbleText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Surface(color = BubbleAssistant, shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)) {
                        Text(msg.content, color = TextPrimary, fontSize = 15.sp, lineHeight = 22.sp,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp))
                    }
                    if (msg.docs.isNotEmpty()) {
                        Row(Modifier.clickable { onToggleDocs() }.padding(top = 5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("📎 ${msg.docs.size} 篇参考文档 折叠查看", color = AccentDeep, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 主聊天界面。 */
@Composable
fun ChatScreen(viewModel: MainViewModel = viewModel()) {
    val messages by viewModel.messages.collectAsState()
    val stage by viewModel.stage.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val expanded by viewModel.expandedDocs.collectAsState()

    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showDocSearch by remember { mutableStateOf(false) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // 文档库更新：GitHub list.json 增量拉取（含 sha 校验与增量索引）
    fun runDocUpdate() {
        if (updateBusy) return
        updateBusy = true
        scope.launch(Dispatchers.IO) {
            val text = try {
                val logs = StringBuilder()
                val r = AppCore.githubSync.run(AppCore.config.loadUpdateUrl()) { logs.appendLine(it) }
                val err = r.errors.take(3).joinToString("\n")
                buildString {
                    appendLine("文档更新完成：新增 ${r.added}，更新 ${r.updated}，失败 ${r.failed}")
                    appendLine()
                    append(logs)
                    if (err.isNotEmpty()) appendLine("失败明细：\n$err")
                }
            } catch (e: Exception) {
                "文档更新失败：${e.message}"
            }
            withContext(Dispatchers.Main) { updateResult = text; updateBusy = false }
        }
    }

    // 软件更新：version.json（apps.android）比对
    fun runAppUpdate() {
        if (updateBusy) return
        updateBusy = true
        scope.launch(Dispatchers.IO) {
            val local = com.zhukongqwq.hanser.BuildConfig.VERSION_NAME
            val text = try {
                val info = AppUpdate.fetchRemoteInfo()
                when {
                    info == null || info.version.isBlank() -> "检查更新失败：无法获取版本信息"
                    AppUpdate.compareVersions(info.version, local) > 0 -> buildString {
                        appendLine("发现新版本 v${info.version}（当前 v$local）")
                        appendLine()
                        appendLine("更新日志：")
                        appendLine(if (info.changelog.isEmpty()) "（无）" else info.changelog.joinToString("\n"))
                        appendLine()
                        appendLine("下载页：${AppUpdate.RELEASE_URL}")
                    }
                    else -> "已是最新版本 v$local"
                }
            } catch (e: Exception) {
                "检查更新失败：${e.message}"
            }
            withContext(Dispatchers.Main) { updateResult = text; updateBusy = false }
        }
    }

    Box(Modifier.fillMaxSize().background(Background)) {
        if (showDocSearch) {
            DocSearchScreen(onBack = { showDocSearch = false })
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(
                    onSettings = { showSettings = true },
                    onClear = {
                        viewModel.clearHistory()
                        input = ""
                    },
                    onDocSearch = { showDocSearch = true },
                    title = "ihan助手，您的身边憨憨百科"
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                ) {
                    items(messages, key = { it.content.hashCode().toString() + it.role }) { m ->
                        MessageBubble(m) { viewModel.toggleDocs(m) }
                    }
                    stage?.let { item { StageBubble(it) } }
                }

                // 输入栏
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Background)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .imePadding(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("问问文档库…", color = TextSecondary, fontSize = 14.sp) },
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Border,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { viewModel.send(input); input = "" })
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.send(input)
                            input = ""
                        },
                        enabled = !busy,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("发送", color = BubbleText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // 设置对话框
    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            updateBusy = updateBusy,
            onDocUpdate = { runDocUpdate() },
            onAppUpdate = { runAppUpdate() }
        )
    }
    // 更新结果对话框
    updateResult?.let { result ->
        val hasRelease = result.contains(AppUpdate.RELEASE_URL)
        AlertDialog(
            onDismissRequest = { updateResult = null },
            containerColor = Color.White,
            title = { Text("更新", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(result, color = TextPrimary, fontSize = 13.sp, lineHeight = 19.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    updateResult = null
                    if (hasRelease) {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(AppUpdate.RELEASE_URL)
                            )
                        )
                    }
                }) { Text(if (hasRelease) "前往下载" else "关闭", color = AccentDeep) }
            },
            dismissButton = if (hasRelease) {
                { TextButton(onClick = { updateResult = null }) { Text("稍后", color = TextSecondary) } }
            } else null
        )
    }
    // 参考文档展开
    expanded?.let { msg ->
        DocListDialog(msg.docs) { viewModel.clearDocsOverlay() }
    }
}

/** 参考文档列表弹窗。 */
@Composable
private fun DocListDialog(docs: List<com.zhukongqwq.hanser.UiDoc>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("参考文档", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(Modifier.height(260.dp)) {
                items(docs) { d ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(d.filename, color = AccentDeep, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("命中 ${d.hits} 次", color = TextSecondary, fontSize = 11.sp)
                        if (d.snippet.isNotEmpty())
                            Text(d.snippet, color = TextPrimary, fontSize = 12.sp)
                    }
                    HorizontalDivider(color = Border)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = AccentDeep) } }
    )
}

/** 设置页（全局 + 三 AI + 更新源 + 模拟模式 + userdict）。 */
@Composable
private fun SettingsDialog(
    onDismiss: () -> Unit,
    updateBusy: Boolean = false,
    onDocUpdate: () -> Unit = {},
    onAppUpdate: () -> Unit = {}
) {
    val cfg = AppCore.config
    var gBase by remember { mutableStateOf("") }
    var gKey by remember { mutableStateOf("") }
    var gModel by remember { mutableStateOf("") }
    var gMax by remember { mutableStateOf("") }
    var bBase by remember { mutableStateOf("") }
    var bKey by remember { mutableStateOf("") }
    var bModel by remember { mutableStateOf("") }
    var bMax by remember { mutableStateOf("") }
    var pBase by remember { mutableStateOf("") }
    var pKey by remember { mutableStateOf("") }
    var pModel by remember { mutableStateOf("") }
    var pMax by remember { mutableStateOf("") }
    var hBase by remember { mutableStateOf("") }
    var hKey by remember { mutableStateOf("") }
    var hModel by remember { mutableStateOf("") }
    var hMax by remember { mutableStateOf("") }
    var updateUrl by remember { mutableStateOf("") }
    var dryRun by remember { mutableStateOf(false) }
    var userdict by remember { mutableStateOf("") }

    // 首帧加载当前配置
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val global = cfg.loadGlobal()
        gBase = global.baseUrl; gKey = global.apiKey; gModel = global.model
        gMax = if (global.maxTokens > 0) global.maxTokens.toString() else ""
        val b = cfg.loadAgent("bunny"); bBase = b?.baseUrl ?: ""; bKey = b?.apiKey ?: ""; bModel = b?.model ?: ""
        bMax = if ((b?.maxTokens ?: 0) > 0) b!!.maxTokens.toString() else ""
        val p = cfg.loadAgent("prometheus"); pBase = p?.baseUrl ?: ""; pKey = p?.apiKey ?: ""; pModel = p?.model ?: ""
        pMax = if ((p?.maxTokens ?: 0) > 0) p!!.maxTokens.toString() else ""
        val h = cfg.loadAgent("hanser"); hBase = h?.baseUrl ?: ""; hKey = h?.apiKey ?: ""; hModel = h?.model ?: ""
        hMax = if ((h?.maxTokens ?: 0) > 0) h!!.maxTokens.toString() else ""
        updateUrl = cfg.loadUpdateUrl()
        dryRun = cfg.loadDryRun()
        userdict = AppCore.userdictFile.readText(Charsets.UTF_8)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("设置", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("全局默认", fontWeight = FontWeight.Bold, color = AccentDeep, fontSize = 13.sp)
                Field("接口地址", gBase) { gBase = it }
                Field("API 密钥", gKey) { gKey = it }
                Field("模型名", gModel) { gModel = it }
                Field("最大输出（空=自动）", gMax) { gMax = it }

                Text("小兔 Bunny（留空回退全局）", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                Field("接口地址", bBase) { bBase = it }; Field("密钥", bKey) { bKey = it }
                Field("模型", bModel) { bModel = it }; Field("最大输出", bMax) { bMax = it }

                Text("普罗米修斯（留空回退全局）", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                Field("接口地址", pBase) { pBase = it }; Field("密钥", pKey) { pKey = it }
                Field("模型", pModel) { pModel = it }; Field("最大输出", pMax) { pMax = it }

                Text("憨憨 hanser（留空回退全局）", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                Field("接口地址", hBase) { hBase = it }; Field("密钥", hKey) { hKey = it }
                Field("模型", hModel) { hModel = it }; Field("最大输出", hMax) { hMax = it }

                Text("文档更新源（GitHub list.json）", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                Field("仓库地址", updateUrl) { updateUrl = it }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("模拟模式（dry-run，不调 API）", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = dryRun, onCheckedChange = { dryRun = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Accent))
                }

                Text("userdict（jieba 自定义词典）", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                OutlinedTextField(
                    value = userdict, onValueChange = { userdict = it },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border)
                )

                // 更新入口：文档库更新 + 软件更新
                Row(Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onDocUpdate, enabled = !updateBusy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Accent),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentDeep)
                    ) { Text("📥 文档库更新", fontSize = 13.sp) }
                    OutlinedButton(
                        onClick = onAppUpdate, enabled = !updateBusy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Accent),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentDeep)
                    ) { Text("⬇ 软件更新", fontSize = 13.sp) }
                }
                if (updateBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = Accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在更新…请稍候", color = TextSecondary, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                fun e(v: String) = EndpointConfig(baseUrl = v)
                cfg.save(
                    global = EndpointConfig(gBase, gKey, gModel, gMax.toIntOrNull() ?: 0),
                    bunny = EndpointConfig(bBase, bKey, bModel, bMax.toIntOrNull() ?: 0),
                    prometheus = EndpointConfig(pBase, pKey, pModel, pMax.toIntOrNull() ?: 0),
                    hanser = EndpointConfig(hBase, hKey, hModel, hMax.toIntOrNull() ?: 0),
                    updateUrl = updateUrl, dryRun = dryRun
                )
                AppCore.saveUserdict(userdict)
                onDismiss()
            }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("保存", color = BubbleText)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) } }
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent, unfocusedBorderColor = Border,
            focusedLabelColor = AccentDeep, unfocusedLabelColor = TextSecondary)
    )
}
