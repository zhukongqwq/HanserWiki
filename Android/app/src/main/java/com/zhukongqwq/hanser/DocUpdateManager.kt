package com.zhukongqwq.hanser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 文档更新任务状态（前台服务写入、Compose 界面读取）：
 * 支持 App 在前台时于设置页内嵌显示进度，后台由通知栏展示。
 */
object DocUpdateManager {

    data class UiState(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val current: String = "",
        /** 本次更新结束后一次性结果文本（成功/失败统计与日志）；null 表示仍在进行或未开始。 */
        val summary: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 任务开始（由服务调用）。 */
    fun begin() {
        _state.value = UiState(running = true)
    }

    /** 进度更新（服务后台线程调用）。 */
    fun setProgress(done: Int, total: Int, current: String) {
        _state.value = UiState(running = true, done = done, total = total, current = current)
    }

    /** 任务结束并给出结果文本（服务调用）。 */
    fun finish(summaryText: String) {
        _state.value = UiState(running = false, summary = summaryText)
    }

    /** 清除上次结果（下次点击前由界面调用）。 */
    fun clearSummary() {
        _state.value = UiState(running = _state.value.running)
    }
}
