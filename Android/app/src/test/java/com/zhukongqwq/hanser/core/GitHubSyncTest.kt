package com.zhukongqwq.hanser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitHubSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val repo = "https://github.com/owner/repo.git"

    /** 构造 fake 下载器：清单引用 data/a.docx（内容 CONTENT）。 */
    private fun fakeDownloader(content: ByteArray = "hello docx 内容".toByteArray()): (String) -> ByteArray {
        val sha = GitHubSync.sha256Hex(content)
        val listJson = """{"data/a.docx": "$sha"}"""
        return { url ->
            when {
                url.endsWith("/releases/download/latest/list.json") -> listJson.toByteArray()
                url.contains("/main/data/a.docx") -> content
                else -> throw IllegalStateException("意外 URL：$url")
            }
        }
    }

    private fun newSync(downloader: (String) -> ByteArray, onDownloaded: () -> Unit = {}): GitHubSync {
        val dataDir = tmp.newFolder("data")
        val cache = File(tmp.newFolder("meta"), ".sync-list.json")
        return GitHubSync(dataDir, cache, onDownloaded, downloader)
    }

    @Test
    fun `新增文档下载 校验并回调`() {
        var indexed = false
        val sync = newSync(fakeDownloader(), onDownloaded = { indexed = true })
        val result = sync.run(repo)
        assertEquals(1, result.added)
        assertEquals(0, result.failed)
        // 文件落盘（data/a.docx）且内容正确
        val saved = File(sync.dataDir, "a.docx")
        assertTrue(saved.exists())
        assertEquals("hello docx 内容", saved.readText())
        assertTrue("下载后应触发增量索引回调", indexed)
    }

    @Test
    fun `清单一致仍做文件级核对 不重复下载`() {
        val dataDir = tmp.newFolder("data")
        val cache = File(tmp.newFolder("meta"), ".sync-list.json")
        val dl = fakeDownloader()
        GitHubSync(dataDir, cache, {}, dl).run(repo) // 首次：下载并保存缓存
        var indexed = false
        // 第二次共享缓存：清单一致仍逐文件核对 → 本地一致则无下载，但照常触发增量索引
        val result = GitHubSync(dataDir, cache, { indexed = true }, dl).run(repo)
        assertEquals(0, result.added)
        assertEquals(0, result.updated)
        assertTrue("核对后仍应触发增量索引", indexed)
    }

    @Test
    fun `本地文件被篡改 清单一致也重新下载修复`() {
        val dataDir = tmp.newFolder("data")
        val cache = File(tmp.newFolder("meta"), ".sync-list.json")
        val content = "hello docx 内容".toByteArray()
        val dl = fakeDownloader(content)
        GitHubSync(dataDir, cache, {}, dl).run(repo) // 首次下载
        // 篡改本地文件（模拟损坏/被删改）
        File(dataDir, "a.docx").writeBytes("被篡改的内容".toByteArray())
        val result = GitHubSync(dataDir, cache, {}, dl).run(repo)
        assertEquals(1, result.updated) // 清单一致但文件 sha 不符 → 重新下载修复
        assertEquals("hello docx 内容", File(dataDir, "a.docx").readText())
    }

    @Test
    fun `进度回调按文件推进`() {
        val dataDir = tmp.newFolder("data")
        val cache = File(tmp.newFolder("meta"), ".sync-list.json")
        val progress = ArrayList<Triple<Int, Int, String>>()
        GitHubSync(dataDir, cache, {}, fakeDownloader()).run(repo) { d, t, c ->
            progress.add(Triple(d, t, c))
        }
        assertTrue("应有进度回调", progress.isNotEmpty())
        // 最后回调 = 完成（done==total），total=1（清单仅一个文件）
        val last = progress.last()
        assertEquals(last.second, last.first)
        assertEquals("a.docx", last.third)
    }

    @Test
    fun `本地已一致则无需下载`() {
        val content = "hello docx 内容".toByteArray()
        // 预置与清单一致的文件（清单缺失缓存场景）
        val dataDir = tmp.newFolder("data")
        File(dataDir, "a.docx").writeBytes(content)
        val cache = File(tmp.newFolder("meta"), ".sync-list.json")
        val sync = GitHubSync(dataDir, cache, {}, fakeDownloader(content))
        val result = sync.run(repo)
        assertEquals(0, result.added)
        assertEquals(0, result.failed)
    }

    @Test
    fun `sha 校验失败计入 failed`() {
        // 下载内容与清单 sha 不符
        val bad: (String) -> ByteArray = { url ->
            if (url.endsWith("list.json"))
                """{"data/a.docx": "${"0".repeat(64)}"}""".toByteArray()
            else "不匹配的内容".toByteArray()
        }
        val sync = newSync(bad)
        val result = sync.run(repo)
        assertEquals(1, result.failed)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("sha256 校验失败"))
    }

    @Test
    fun `normalizeRel 防穿越与去前缀`() {
        assertEquals("x.docx", GitHubSync.normalizeRel("data/x.docx"))
        assertEquals("sub/y.docx", GitHubSync.normalizeRel("../data/sub/y.docx"))
        assertEquals(null, GitHubSync.normalizeRel("/abs/x.docx"))
        assertEquals(null, GitHubSync.normalizeRel("data/../x.docx"))
        assertEquals(null, GitHubSync.normalizeRel("a/../../x"))
    }

    @Test
    fun `镜像前缀解析`() {
        val parsed = GitHubSync(dataDir = File("."), cacheFile = File("."), onDownloaded = {}).parseUrl(
            "https://gh-proxy.com/https://github.com/owner/repo.git"
        )
        assertEquals("https://gh-proxy.com/", parsed.proxy)
        assertEquals("owner", parsed.owner)
        assertEquals("repo", parsed.repo)
        assertEquals("https://gh-proxy.com/https://github.com/owner/repo", parsed.repoBase)
    }
}
