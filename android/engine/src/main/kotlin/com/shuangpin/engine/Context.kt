package com.shuangpin.engine

/**
 * **上下文选词**：拿「已经上屏的东西」去帮当前这一轮选词。
 *
 * ## 它解决什么
 *
 * 候选装配只看当前这一串拼音，于是「结」+「guo」只能给出「过、国、果……」，
 * 排序只能靠词频——「过」最常用，所以它排第一。
 * 但用户明明刚打了「结」，他要的是「结果」。
 *
 * 另外两条路都太慢：
 * - **端侧重排模型**能看上文，可它**排不了单字候选**
 *   （图不接受零行输入，见 [Reranker.rank]），单音节这一轮它直接不重排；
 * - **云端消歧**能看上文，可它要 500 ms 防抖 + 一次大模型往返（1.4 s 起）。
 *
 * 用户看到的现象就是「候选词第一个显示『过』，得停着不动很长时间才会变成『果』」。
 *
 * ## 怎么解决
 *
 * 用**词库自己**做这件事：上一块「结」（拼音 jie）+ 这一轮「guo」，
 * 在词库里查「jieguo」→ 命中「结果」→ 后半截「果」就是这一轮该给的字。
 *
 * 一次二分查找（2 μs），**不用等任何模型**——按完键立刻就是对的。
 *
 * 同样的招数也能做**下一词预测**（见 [nextWords]）：
 * 刚上屏「结」，词库里「jieguo」「jiehun」这些词都以「结」开头，
 * 它们的尾巴（果、婚……）就是接下来最可能打的字。
 */
object Context {

    /** 下一词最多给几个。 */
    const val DEFAULT_NEXT_LIMIT = 5

    /** 下一词的最长字数（再长就不像「一个词」了）。 */
    const val MAX_NEXT_CHARS = 3

    /**
     * **跨界组合**：上一块 + 当前拼音，在词库里是不是一个词？
     *
     * 「结」（jie）+「guo」→ 词库里的「结果」→ 这一轮该给「果」。
     *
     * 判据很严，三条都要满足：
     * 1. 词库里有「上一块拼音 + 当前拼音」这个条目；
     * 2. 那个词**以上一块的开头**（"jieguo" 的「接过」不是「结」接的，不要）；
     * 3. 词长 = 上一块字数 + 当前音节数（正好对上这一轮该出的字数）。
     *
     * @param prevPinyin 上一块的拼音
     * @param prevText 上一块的文字
     * @param curPinyin 当前这一轮的拼音
     * @param curSyllables 当前这一轮有几个音节
     * @return 这一轮该优先给的字（按词频降序）
     */
    fun boundaryCandidates(
        dictionary: Dictionary,
        prevPinyin: String,
        prevText: String,
        curPinyin: String,
        curSyllables: Int,
    ): List<String> {
        if (prevPinyin.isEmpty() || prevText.isEmpty()) return emptyList()
        if (curPinyin.isEmpty() || curSyllables <= 0) return emptyList()
        val cands = dictionary.phrases.candidates(prevPinyin + curPinyin)
        if (cands.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Int>()
        for (c in cands) {
            val w = c.text
            if (!w.startsWith(prevText)) continue
            if (w.length != prevText.length + curSyllables) continue
            val tail = w.substring(prevText.length)
            if (tail.isEmpty()) continue
            val old = out[tail]
            if (old == null || c.score > old) out[tail] = c.score
        }
        return out.entries.sortedByDescending { it.value }.map { it.key }
    }

    /**
     * **本地下一词预测**：刚上屏的这一块后面，最可能接什么。
     *
     * 做法是**枚举下一个音节**：把「上一块的拼音 + 下一个音节」丢进词库查，
     * 命中且那个词以上一块开头，就把它的尾巴当成下一词。
     *
     * 「结」（jie）→ "jieguo"→结果（果）、"jiehun"→结婚（婚）……
     * 412 个音节各查一次，一共 1 ms 上下，**不联网、不等模型**。
     *
     * @param prevPinyin 上一块的拼音
     * @param prevText 上一块的文字
     * @param limit 最多给几个
     */
    fun nextWords(
        dictionary: Dictionary,
        syllables: List<String>,
        prevPinyin: String,
        prevText: String,
        limit: Int = DEFAULT_NEXT_LIMIT,
    ): List<String> {
        if (prevPinyin.isEmpty() || prevText.isEmpty()) return emptyList()
        if (limit <= 0) return emptyList()
        val found = LinkedHashMap<String, Int>()
        for (syl in syllables) {
            if (syl.isEmpty()) continue
            val cand = dictionary.phrases.top(prevPinyin + syl) ?: continue
            val w = cand.text
            if (!w.startsWith(prevText)) continue
            val tail = w.substring(prevText.length)
            if (tail.isEmpty() || tail.length > MAX_NEXT_CHARS) continue
            val old = found[tail]
            if (old == null || cand.score > old) found[tail] = cand.score
        }
        return found.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }
}
