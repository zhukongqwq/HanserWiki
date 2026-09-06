package com.zhukongqwq.hanser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhukongqwq.hanser.AppCore
import com.zhukongqwq.hanser.core.Search
import com.zhukongqwq.hanser.ui.HanserColors.Accent
import com.zhukongqwq.hanser.ui.HanserColors.AccentDeep
import com.zhukongqwq.hanser.ui.HanserColors.AccentSoft
import com.zhukongqwq.hanser.ui.HanserColors.Background
import com.zhukongqwq.hanser.ui.HanserColors.Border
import com.zhukongqwq.hanser.ui.HanserColors.BubbleAssistant
import com.zhukongqwq.hanser.ui.HanserColors.BubbleText
import com.zhukongqwq.hanser.ui.HanserColors.TextPrimary
import com.zhukongqwq.hanser.ui.HanserColors.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 文档库检索结果。 */
private data class DocHit(
    val filepath: String,
    val filename: String,
    val hits: Long,
    val snippet: String,
    val score: Double
)

/** 文档库搜索入口：输入关键词 → BM25 检索文档 → 点击查看正文。 */
@Composable
fun DocSearchScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<DocHit>>(emptyList()) }
    var opened by remember { mutableStateOf<DocHit?>(null) }
    val scope = rememberCoroutineScope()

    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty() || busy) return
        busy = true
        scope.launch(Dispatchers.IO) {
            val hits = try {
                Search.searchDocuments(AppCore.library.db, listOf(q)).map {
                    DocHit(it.filepath, it.filename, it.hits, it.snippet, it.score)
                }
            } catch (e: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                results = hits
                searched = true
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Background)) {
        // 自绘标题行
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", color = AccentDeep, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
            Text("文档库搜索", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)

        // 搜索输入行
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入关键词…", color = TextSecondary, fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Border,
                    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch() })
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { doSearch() },
                enabled = !busy,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.height(48.dp)
            ) {
                Text(if (busy) "检索中…" else "搜索", color = BubbleText, fontWeight = FontWeight.Bold)
            }
        }

        if (searched && results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (busy) "检索中…" else "没有找到相关文档", color = TextSecondary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(results) { hit ->
                    DocCard(hit, onClick = { opened = hit })
                }
            }
        }
    }

    opened?.let { DocViewDialog(it) { opened = null } }
}

/** 检索结果卡片。 */
@Composable
private fun DocCard(hit: DocHit, onClick: () -> Unit) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hit.filename, color = AccentDeep, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text("命中 ${hit.hits}", color = TextSecondary, fontSize = 11.sp)
                Text("　${hit.score}", color = TextSecondary, fontSize = 11.sp)
            }
            Text(hit.filepath, color = TextSecondary, fontSize = 11.sp)
            if (hit.snippet.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(hit.snippet, color = TextPrimary, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

/** 文档正文阅读弹窗（含关键词命中上下文片段）。 */
@Composable
private fun DocViewDialog(hit: DocHit, onDismiss: () -> Unit) {
    var content by remember { mutableStateOf("") }
    var snippets by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    // 进入时读正文
    androidx.compose.runtime.LaunchedEffect(hit.filepath) {
        val text = withContext(Dispatchers.IO) {
            AppCore.library.db.query(
                "SELECT content FROM documents WHERE filepath = ?", listOf(hit.filepath)
            ).firstOrNull()?.getString("content").orEmpty()
        }
        content = text
        snippets = Search.contextSnippets(text, listOf(hit.filename), radius = 200, maxSnippets = 3)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text(hit.filename, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (snippets.isNotEmpty()) {
                    Text("命中段落", color = AccentDeep, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    snippets.forEach { s ->
                        Surface(color = AccentSoft, shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(s, color = TextPrimary, fontSize = 13.sp,
                                modifier = Modifier.padding(8.dp), lineHeight = 19.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    if (content.isEmpty()) "（无正文）" else content.take(4000),
                    color = TextPrimary, fontSize = 14.sp, lineHeight = 21.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = AccentDeep) } }
    )
}
