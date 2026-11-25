package com.shuangpin.engine

/**
 * **组合学习**：用户连着打的两块，连起来也记一条。
 *
 * ## 它解决什么
 *
 * 用户先打「天道」，再打「符文」——以后打「tiandaofuwen」，
 * 候选栏第一个就该是「天道符文」。
 *
 * 词库里没有这个组合（rime-ice 里 "tiandaofuwen" 查无此词），
 * 分词只能切回「天道」「符文」两个词，用户得自己拼。
 *
 * ## 两条规矩
 *
 * 1. **必须是连着的两块**。中间插了空格、标点就不学——
 *    那种情况下它们本来就是两个词（「我想」和「吃饭」不该学成「我想吃饭」）。
 * 2. **词库里已经有的组合不学**。「我想」词库里有，学不学都一样；
 *    学了反而改了词频。真正要学的是**词库里没有的**——那是用户自己的用法。
 *
 * 放在引擎层是因为它是纯逻辑（给定两个「拼音 → 文字」，
 * 判断该不该记一条组合），App 层只负责把结果写进词库。
 */
object Learning {

    /** 组合**最少**几个字。两个字太容易是巧合（「的是」「个我」）。 */
    const val MIN_COMBO_CHARS = 3

    /** 组合**最多**几个字。再长就不像一个「词」了。 */
    const val MAX_COMBO_CHARS = 6

    /**
     * 该记的组合。
     *
     * @param dictionary 词库（用来判断这个组合是不是已经有了）
     * @param prevPinyin 上一块的拼音（null = 没有上一块）
     * @param prevText 上一块的文字
     * @param pinyin 这一块的拼音
     * @param text 这一块的文字
     * @return `(拼音, 文字)`；不该记就返回 null
     */
    fun combination(
        dictionary: Dictionary,
        prevPinyin: String?,
        prevText: String?,
        pinyin: String,
        text: String,
    ): Pair<String, String>? {
        if (prevPinyin.isNullOrEmpty() || prevText.isNullOrEmpty()) return null
        if (pinyin.isEmpty() || text.isEmpty()) return null
        // 两块都得是**词**（两个字以上）：
        // 单字（「的」「我」）跟谁连着都是巧合，学进去只会污染词库。
        if (prevText.length < 2 || text.length < 2) return null
        val comboPinyin = prevPinyin + pinyin
        val comboText = prevText + text
        if (comboText.length < MIN_COMBO_CHARS || comboText.length > MAX_COMBO_CHARS) return null
        // 词库里已有的，不学
        if (dictionary.phrases.top(comboPinyin) != null) return null
        return comboPinyin to comboText
    }
}
