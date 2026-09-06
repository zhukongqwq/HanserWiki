package com.zhukongqwq.hanser.core

import org.json.JSONObject

/**
 * 应用软件更新检查（与 Windows 版 AppUpdate 同协议）：
 * 拉取发布仓库 HanserWiki main/version.json，取 apps.android.version（缺省回退顶层 version）
 * 与本地版本比对；发布产物（zip + apk）同挂一个 Release。
 */
object AppUpdate {

    const val VERSION_URL = "https://raw.githubusercontent.com/zhukongqwq/HanserWiki/main/version.json"
    const val RELEASE_URL = "https://github.com/zhukongqwq/HanserWiki/releases/latest"

    data class RemoteInfo(val version: String, val changelog: List<String>)

    /** 语义化版本比较：a &gt; b 返回正数（按 . 与 - 分段数字比较，与 Windows 一致）。 */
    fun compareVersions(a: String, b: String): Int {
        val pa = (a ?: "").split('.', '-')
        val pb = (b ?: "").split('.', '-')
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** 拉取远程 version.json 的 Android 平台信息；失败返回 null。 */
    fun fetchRemoteInfo(downloader: (String) -> ByteArray = { url -> GitHubSync.httpGet(url) }): RemoteInfo? {
        return try {
            val text = String(downloader(VERSION_URL), Charsets.UTF_8)
            val obj = JSONObject(text)
            val android = obj.optJSONObject("apps")?.optJSONObject("android")
            val version = android?.optString("version").orEmpty()
                .ifEmpty { obj.optString("version") }
            val changelog = ArrayList<String>()
            obj.optJSONArray("changelog")?.let { arr ->
                for (i in 0 until arr.length()) changelog.add(arr.optString(i))
            }
            RemoteInfo(version, changelog)
        } catch (_: Exception) {
            null
        }
    }
}
