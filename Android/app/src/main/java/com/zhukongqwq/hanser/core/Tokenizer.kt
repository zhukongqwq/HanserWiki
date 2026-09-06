package com.zhukongqwq.hanser.core

/**
 * 统一分词入口（对应 Python 版 utils/tokenizer.py 与 Windows 版 Tokenizer）。
 *
 * 约定：索引建词与查询切词必须都走 [tokenize]，保证两侧一致，否则检索失效。
 * - 切词：结巴式词典分词（[DictSegmenter]，jieba 精确模式 DAG + 最大概率，词典放 assets 由 AppCore 加载）；
 * - 过滤：去空白、单字、停用词、纯符号（与 Python/C# 同规则）；
 * - 用户词典：userdict 词注入词典（整词优先）+ 命中补词，保证专有名词不被切散。
 */
object Tokenizer {

    /** 与 Python 版 utils/tokenizer.py 完全一致的停用词表。 */
    val STOPWORDS: Set<String> = setOf(
        "的", "了", "是", "在", "和", "就", "都", "也", "不", "我", "你", "他", "她",
        "它", "我们", "你们", "他们", "她们", "这个", "那个", "什么", "怎么", "为什么",
        "然后", "现在", "可以", "没有", "自己", "这样", "那样", "一个", "一下", "还有",
        "知道", "觉得", "真的", "已经", "因为", "所以", "如果", "但是", "还是", "就是",
        "是不是", "什么", "这样", "那个", "感觉", "有点", "一下", "一会", "起来",
    )

    private val segmenter = DictSegmenter()

    /** userdict 补充词（每行首列词语，跳过注释/空行）。 */
    @Volatile
    var userdictWords: List<String> = emptyList()
        private set

    /** 词典是否已加载（切词可用）。 */
    val isDictLoaded: Boolean get() = segmenter.isLoaded

    /** 加载主词典（dict.txt 文本）+ 注入当前 userdict 词。 */
    fun loadDict(dictText: String) {
        segmenter.loadDict(dictText)
        segmenter.addWords(userdictWords)
    }

    /** 加载 userdict 文本（每行「词语 词频 词性」或仅词语）。设置保存后调用。 */
    fun loadUserdict(text: String) {
        userdictWords = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                // 兼容 jieba 格式「词语 词频 词性」；含空格则只取词语
                val w = line.split(Regex("\\s+")).firstOrNull()?.trim().orEmpty()
                w.takeIf { it.isNotEmpty() }
            }
            .distinct()
            .toList()
        if (segmenter.isLoaded) segmenter.addWords(userdictWords)
    }

    /** 分词并过滤，返回词列表（保持出现顺序；userdict 命中词按首现顺序补尾）。 */
    fun tokenize(text: String?): List<String> {
        val content = text ?: ""
        if (content.isEmpty()) return emptyList()

        val raw = if (segmenter.isLoaded) segmenter.cut(content) else emptyList()
        val tokens = ArrayList<String>(raw.size)
        for (w in raw) {
            val word = w.trim()
            if (word.isEmpty() || word.length == 1) continue // 过滤单字
            if (word in STOPWORDS) continue
            if (!word.any { it.isLetterOrDigit() }) continue // 过滤纯标点/符号
            tokens.add(word)
        }

        // userdict 补充：整词在原文成块出现则补入（避免词典词被切散导致检索失效）
        if (userdictWords.isNotEmpty()) {
            for (dictWord in userdictWords) {
                if (dictWord.length < 2) continue
                if (content.contains(dictWord) && dictWord !in tokens) {
                    tokens.add(dictWord)
                }
            }
        }
        return tokens
    }
}
