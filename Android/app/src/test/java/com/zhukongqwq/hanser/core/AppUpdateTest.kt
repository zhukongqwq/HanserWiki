package com.zhukongqwq.hanser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {

    @Test
    fun `语义化版本比较`() {
        assertTrue(AppUpdate.compareVersions("1.0.6", "1.0.5") > 0)
        assertTrue(AppUpdate.compareVersions("1.0.5", "1.0.6") < 0)
        assertEquals(0, AppUpdate.compareVersions("0.1.4", "0.1.4"))
        assertTrue(AppUpdate.compareVersions("1.10", "1.9") > 0) // 数字分段比较
        assertTrue(AppUpdate.compareVersions("0.1.4", "0.1.3") > 0)
    }

    @Test
    fun `fetchRemoteInfo 解析 android 段`() {
        val json = """
            {"version": "1.0.6", "date": "2026-09-06",
             "changelog": ["a", "b"],
             "apps": {"windows": {"version": "1.0.6"}, "android": {"version": "0.1.4"}}}
        """.trimIndent()
        val info = AppUpdate.fetchRemoteInfo { json.toByteArray() }
        assertEquals("0.1.4", info!!.version)
        assertEquals(listOf("a", "b"), info.changelog)
    }

    @Test
    fun `无 android 段时回退顶层 version`() {
        val json = """{"version": "1.0.6", "changelog": []}"""
        val info = AppUpdate.fetchRemoteInfo { json.toByteArray() }
        assertEquals("1.0.6", info!!.version)
    }

    @Test
    fun `拉取失败返回 null`() {
        assertNull(AppUpdate.fetchRemoteInfo { throw IllegalStateException("网络失败") })
    }
}
