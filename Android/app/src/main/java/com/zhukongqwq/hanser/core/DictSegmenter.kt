package com.zhukongqwq.hanser.core

import kotlin.math.ln

/**
 * 结巴式词典分词器（jieba 精确模式：DAG + 最大对数概率动态规划，含词频总归一惩罚）。
 *
 * Android 上 jieba-analysis 的 jar 内词典无法经 classloader 读取（崩溃根因），
 * 改为自实现：词典（dict.txt）与自定义词（userdict）都从外部文件/文本注入，
 * 由 [Tokenizer] 统一调用。切分打分与 jieba 一致（每段减 log(total)，偏好整词；
 * 无 HMM 未登录词合成，专名由 userdict 兜底）。
 */
class DictSegmenter {

    private var freq: HashMap<String, Int> = HashMap()
    private var total: Long = 0
    private var logTotal: Double = 0.0
    private var maxWordLen = 2

    /** 是否已加载词典。 */
    val isLoaded: Boolean get() = freq.isNotEmpty()

    /** 加载主词典（dict.txt：每行「词 词频 词性」）。线程外调用。 */
    fun loadDict(text: String) {
        val f = HashMap<String, Int>(350_000)
        var sum = 0L
        var maxLen = 1
        text.lineSequence().forEach { line ->
            val parts = line.split(' ')
            if (parts.size >= 2) {
                val word = parts[0]
                val count = parts[1].trim().toIntOrNull() ?: 0
                if (word.isNotEmpty() && count > 0) {
                    f[word] = count
                    sum += count
                    if (word.length > maxLen) maxLen = word.length
                }
            }
        }
        freq = f
        total = sum
        logTotal = ln(sum.toDouble().coerceAtLeast(1.0))
        maxWordLen = maxLen
    }

    /** 注入自定义词（userdict；词频给高值保证整词优先）。 */
    fun addWords(words: List<String>) {
        if (words.isEmpty()) return
        for (w in words) {
            if (w.length > 1 && w !in freq) {
                freq[w] = 10_000_000
                total += 10_000_000L
                if (w.length > maxWordLen) maxWordLen = w.length
            }
        }
        logTotal = ln(total.toDouble().coerceAtLeast(1.0))
    }

    /** 精确模式切词（jieba DAG + 最大概率；每段得分减 log(total)，未登录字按词频 1）。 */
    fun cut(text: String): List<String> {
        val n = text.length
        if (n == 0) return emptyList()
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val next = IntArray(n + 1)
        best[n] = 0.0
        next[n] = n
        for (i in n - 1 downTo 0) {
            val maxLen = minOf(maxWordLen, n - i)
            var bi = i + 1
            var bs = Double.NEGATIVE_INFINITY
            for (len in 1..maxLen) {
                val seg = text.substring(i, i + len)
                val f = freq[seg] ?: continue // 只把词典命中作为候选
                // jieba：log(FREQ.get(w)) - logtotal + route
                val v = (ln(f.toDouble()) - logTotal) + best[i + len]
                if (v > bs) { bs = v; bi = i + len }
            }
            if (bs == Double.NEGATIVE_INFINITY) {
                // 词典完全无命中：单字作未登录（词频按 1 → score = -logtotal）
                bs = -logTotal
                bi = i + 1
            }
            best[i] = bs
            next[i] = bi
        }
        val words = ArrayList<String>()
        var i = 0
        while (i < n) {
            val e = next[i]
            words.add(text.substring(i, e))
            i = e
        }
        return words
    }
}
