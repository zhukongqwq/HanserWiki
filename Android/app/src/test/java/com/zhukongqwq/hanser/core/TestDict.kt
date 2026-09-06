package com.zhukongqwq.hanser.core

/**
 * 测试共用：从 classpath 加载真实词典（test/resources 下的 dict.txt/userdict.txt），
 * 保证索引/查询切词可用（与 assets 同一份文件，仅测试用）。
 */
object TestDict {

    /** 幂等加载（词典存在才加载）。 */
    fun loadOnce() {
        val cls = TestDict::class.java
        val dictText = cls.getResourceAsStream("/dict.txt")?.readBytes()
        if (dictText != null && !Tokenizer.isDictLoaded) {
            Tokenizer.loadDict(String(dictText, Charsets.UTF_8))
        }
        val userText = cls.getResourceAsStream("/userdict.txt")?.readBytes()
        if (userText != null) {
            Tokenizer.loadUserdict(String(userText, Charsets.UTF_8))
        }
    }
}
