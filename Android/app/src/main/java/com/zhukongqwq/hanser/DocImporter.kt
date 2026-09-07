package com.zhukongqwq.hanser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * 自定义文件导入（仅新增，绝不改动/覆盖文档库现有文件）：
 * 把用户多选的文件（docx 等）复制进 data/ 文档库目录（重名自动加序号），随后触发增量索引。
 */
object DocImporter {

    data class ImportResult(val ok: Int, val errors: List<String>)

    /** docx MIME（SAF 文件选择过滤用）。 */
    const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** 导入多个 Uri 文件（调用方放后台线程）。 */
    fun import(context: Context, uris: List<Uri>): ImportResult {
        val errors = ArrayList<String>()
        var ok = 0
        for (uri in uris) {
            try {
                val name = queryDisplayName(context, uri)
                    ?: "imported-${System.currentTimeMillis()}"
                val docxName = if (name.endsWith(".docx", ignoreCase = true)) name else "$name.docx"
                val dest = uniqueDest(docxName)
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("无法读取所选文件")
                input.use { ins -> dest.outputStream().use { outs -> ins.copyTo(outs) } }
                ok++
            } catch (e: Exception) {
                errors.add("${queryDisplayName(context, uri) ?: uri.lastPathSegment}: ${e.message}")
            }
        }
        if (ok > 0) AppCore.indexInBackground() // 后台增量索引新导入文件
        return ImportResult(ok, errors)
    }

    /** 同名文件不覆盖：自动追加 (1)(2)… 序号。 */
    private fun uniqueDest(name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = File(AppCore.dataDir, name)
        var n = 1
        while (candidate.exists()) {
            candidate = File(AppCore.dataDir, "$base($n)$ext")
            n++
        }
        return candidate
    }

    /** 查询系统文件选择器中的显示名；查询失败返回 null。 */
    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx) else null
                    } else null
                }
        } catch (_: Exception) {
            null
        }
    }
}
