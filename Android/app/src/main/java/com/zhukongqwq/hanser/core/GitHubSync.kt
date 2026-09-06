package com.zhukongqwq.hanser.core

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * GitHub 清单增量更新源（对应 Windows 版 GitHubSync.cs 协议）：
 * 仓库 Release 维护 list.json（{路径: sha256}），下载清单与本地缓存（.sync-list.json）比对 →
 * 本地文件逐个 sha256 比对 → 只下载缺失/不一致 docx（raw + 镜像）→ 下载后校验 → 保存缓存 → 增量索引。
 */
class GitHubSync(
    val dataDir: File,
    val cacheFile: File,
    private val onDownloaded: () -> Unit,
    private val downloader: (url: String) -> ByteArray = ::httpGet
) {

    class SyncResult {
        var added: Int = 0
        var updated: Int = 0
        var failed: Int = 0
        val errors = ArrayList<String>()
    }

    companion object {
        private const val MARKER = "https://github.com/"
        private const val DEFAULT_BRANCH = "main"

        /** 默认下载实现（HttpURLConnection，Android 与 JVM 通用）。 */
        fun httpGet(url: String): ByteArray {
            val conn = URL(url).openConnection() as HttpURLConnection
            return try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 20_000
                conn.readTimeout = 120_000
                conn.setRequestProperty("User-Agent", "AI-Hanser")
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${conn.responseCode}")
                }
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }

        /** 清单键归一化为 data 目录相对路径（去 data/ 或 ../data/ 前缀、防路径穿越）；不合法返回 null。 */
        fun normalizeRel(path: String): String? {
            var p = path.replace('\\', '/')
            if (p.startsWith("../data/", ignoreCase = true)) p = p.substring(8)
            else if (p.startsWith("data/", ignoreCase = true)) p = p.substring(5)
            if (p.isEmpty() || p.startsWith("/") || p.contains("..")) return null
            return p
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    data class Parsed(val proxy: String, val owner: String, val repo: String, val repoBase: String)

    /** 解析仓库地址 → (镜像前缀, owner, repo, 仓库基址)。 */
    fun parseUrl(url: String): Parsed {
        val trimmed = url.trim().trimEnd('/')
        val idx = trimmed.indexOf(MARKER, ignoreCase = true)
        require(idx >= 0) { "仓库地址无效：需包含 https://github.com/" }
        val proxy = trimmed.substring(0, idx)
        val rest = trimmed.substring(idx + MARKER.length)
        val parts = rest.split('/')
        require(parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
            "仓库地址无效：应为 https://github.com/{owner}/{repo}[.git]"
        }
        var repo = parts[1]
        if (repo.endsWith(".git", ignoreCase = true)) repo = repo.dropLast(4)
        return Parsed(proxy, parts[0], repo, proxy + MARKER + parts[0] + "/" + repo)
    }

    fun listUrl(repoUrl: String): String = parseUrl(repoUrl).repoBase + "/releases/download/latest/list.json"

    /** 校验 list.json 可达（设置保存时用）。返回 (是否可用, 错误信息)。 */
    fun validateListUrl(repoUrl: String): Pair<Boolean, String> {
        val url = listUrl(repoUrl)
        return try {
            downloader(url)
            true to ""
        } catch (e: Exception) {
            false to ("未找到 list.json（${e.message}）：\n$url\n\n请先在仓库 Release 中上传 list.json 资产，或检查仓库地址 / 网络 / 镜像前缀。")
        }
    }

    /**
     * 执行检查更新（阻塞）。流程：清单 → 逐个文件 sha256 核对 → 只下载缺失/不一致 → 保存缓存 → 增量索引。
     * 每次调用都做文件级核对（不因清单缓存一致而跳过，保证本地文件完整性与清单一致）。
     * 调用方负责放到后台线程（协程 / 前台服务）。
     */
    fun run(repoUrl: String, log: (String) -> Unit = {},
            onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> }): SyncResult {
        val result = SyncResult()
        val parsed = parseUrl(repoUrl)
        val listUrl = listUrl(repoUrl)
        val proxy = parsed.proxy
        log("  仓库：${parsed.owner}/${parsed.repo}，镜像：${if (proxy.isNotEmpty()) proxy.trimEnd('/') else "（直连）"}")
        log("  下载清单：$listUrl…")

        // 1. 下载 list.json
        val listBytes = try {
            downloader(listUrl)
        } catch (e: Exception) {
            throw IllegalStateException(
                "下载清单失败（${e.message}）：$listUrl\n请先在仓库 Release 上传 list.json 资产，或检查仓库地址 / 网络 / 镜像前缀。"
            )
        }
        val listSha = sha256Hex(listBytes)

        // 2. 解析清单 {路径: sha256}（每次都做文件级核对：清单缓存一致仅作提示，不跳过核对）
        val cacheSame = cacheFile.exists() && sha256Hex(cacheFile.readBytes()) == listSha
        if (cacheSame) {
            log("  清单与上次一致（sha256 ${listSha.take(12)}…），仍将逐文件核对 sha256…")
        } else {
            log("  清单已更新（sha256 ${listSha.take(12)}…），开始核对本地文件…")
        }
        val manifest = LinkedHashMap<String, String>()
        try {
            val obj = JSONObject(String(listBytes, Charsets.UTF_8))
            obj.keys().forEach { k -> manifest[k] = obj.getString(k) }
        } catch (e: Exception) {
            throw IllegalStateException("list.json 解析失败：${e.message}")
        }
        log("  清单条目：${manifest.size} 个")

        // 4. 本地文件逐个 sha256 对比
        val toDownload = ArrayList<Pair<String, String>>()
        var skipped = 0
        for ((path, sha) in manifest) {
            val rel = normalizeRel(path)
            if (rel == null) {
                log("  [跳过] $path（路径不在 data/ 目录内）")
                continue
            }
            val dest = File(dataDir, rel)
            if (dest.exists() && sha256Hex(dest.readBytes()).equals(sha, ignoreCase = true)) {
                skipped++
                continue
            }
            toDownload.add(path to sha)
        }
        log("  本地一致跳过：$skipped 个；需下载：${toDownload.size} 个")

        // 5. 逐个下载（raw + 镜像），校验 sha256
        dataDir.mkdirs()
        val totalCount = toDownload.size
        toDownload.forEachIndexed { i, (path, sha) ->
            val rel = normalizeRel(path)!!
            val dest = File(dataDir, rel)
            try {
                // 仓库路径统一为 data/{rel}（兼容清单键 data/ 与 ../data/ 两种前缀）
                val encoded = ("data/$rel").split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }
                val rawUrl = proxy + "https://raw.githubusercontent.com/${parsed.owner}/${parsed.repo}/$DEFAULT_BRANCH/$encoded"
                log("  [下载] $rel（${i + 1}/$totalCount）…")
                onProgress(i, totalCount, rel) // 开始下载当前文件
                val bytes = downloader(rawUrl)
                val actualSha = sha256Hex(bytes)
                if (!actualSha.equals(sha, ignoreCase = true)) {
                    throw IllegalStateException(
                        "sha256 校验失败（期望 ${sha.take(12)}…，实际 ${actualSha.take(12)}…）"
                    )
                }
                val isUpdate = dest.exists()
                dest.parentFile?.mkdirs()
                dest.writeBytes(bytes)
                if (isUpdate) result.updated++ else result.added++
                onProgress(i + 1, totalCount, rel) // 当前文件完成
                log("  [完成] $rel（${if (isUpdate) "更新" else "新增"}，${bytes.size / 1024} KB，sha256 校验通过）")
            } catch (e: Exception) {
                result.failed++
                result.errors.add("$rel：${e.message}")
                log("  [失败] $rel：${e.message}")
            }
        }

        // 6. 保存清单缓存 + 增量索引
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeBytes(listBytes)
        log("  已保存更新清单（sha256 ${listSha.take(12)}…）")
        onDownloaded()
        return result
    }
}
