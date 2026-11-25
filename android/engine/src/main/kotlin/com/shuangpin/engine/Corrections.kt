package com.shuangpin.engine

/**
 * **长句纠错**：一整句里错了一个字，把它找出来并纠正。
 *
 * ## 为什么需要它
 *
 * [Typos.corrections] 只纠**整串**：候选词必须和音节数一样长。
 * 一旦打的是一整句（不是词库里的一个词），那条规则**根本不触发**——
 * 这正是「大篇幅打文档错别字特别多」的原因：
 * 打得越长，整串越不可能是词库里的一个词，纠错就越没用。
 *
 * 「今天仙人抚我顶」里把 ren 打成 ri，整串 "jintianxianrifuwoding"
 * 在词库里查无此词 → 老规则给不出任何纠错候选。
 *
 * ## 怎么找
 *
 * 拿**分词结果**当线索：拼音打错的那个词，词库里没有它，
 * DP 只能把它切碎。于是「这一段词」就是错的地方。
 *
 * 例子（分词结果）：
 *
 * ```
 * 今天 | 西安 | 日服 | 我顶        ← 用户实际打出来的（全是词库里的怪词）
 * 今天 | 仙人抚我顶                ← 纠正后
 * ```
 *
 * 窗口取「连续几个词」，总音节数不超过 [MAX_WINDOW]，
 * 拿这一段的拼音去试近音/错键变体（[Typos.corrections]）。
 *
 * ## 怎么防止乱纠
 *
 * 1. 窗口的拼音**本身就是个词** → 用户打对了，不纠；
 * 2. 纠出来的词要在 DP 的打分上**明显更好**（[MARGIN]）——
 *    「日服」「我顶」这种怪词的分本来就低，
 *    换成「仙人抚我顶」才划算；换个同样冷门的词不算数。
 *
 * ## 放在后台跑
 *
 * 这个计算不便宜（要试几十种变体），**不能挡着打字**。
 * 调用方（App 层）应该在输入停下来之后放后台线程跑，
 * 结果回来了再更新候选栏。
 */
object Corrections {

    /** 错误窗口最长几个音节（更长的多半不是词）。 */
    const val MAX_WINDOW = 5

    /**
     * 纠正后的词要比原来「好」这么多分（DP 的打分口径）才认。
     *
     * 门槛定这么高，是因为**词库里全是怪词**：
     * 「日服」「我顶」「西安」都是词库里的词，
     * 打错的那一段往往也能切出几个像模像样的怪词来。
     * 只有当纠正后的词**明显更常用**时，它才是真的纠正。
     */
    const val MARGIN = 60

    /** 最多给出几条纠正后的整句。 */
    const val MAX_RESULTS = 2

    /**
     * 找出一整句里的错别字。
     *
     * @param engine 引擎（要它的词库、音节表、分词）
     * @param syllables 用户这一轮输入的音节序列
     * @return 纠正后的整句（最多 [MAX_RESULTS] 条）
     */
    fun forSentence(engine: PinyinEngine, syllables: List<String>): List<String> {
        if (syllables.size < 2) return emptyList()
        val spans = engine.segmentWordSpans(syllables)
        if (spans.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (w in spans.indices) {
            var count = 0
            for (end in w until spans.size) {
                count += spans[end].count
                if (count > MAX_WINDOW) break
                val window = spans.subList(w, end + 1)
                val syls = window.flatMap { it.syllables }
                if (syls.size < 2) continue
                val key = syls.joinToString("")
                // 这一段本身就是个词 → 用户打对了
                if (engine.dictionary.phrases.top(key) != null) continue
                val corr = Typos.corrections(engine.dictionary, engine.table, syls).firstOrNull() ?: continue
                // 纠出来的词要**明显更好**才认
                val before = window.sumOf { it.edgeScore }
                val after = corr.score + PinyinEngine.LONG_BONUS * (syls.size - 1) - PinyinEngine.WORD_PENALTY
                if (after <= before + MARGIN) continue
                // 把窗口里那些词**换成纠正后的那一个词**
                // （窗口是「西安 | 日服 | 我顶」，纠正后是「仙人抚我顶」——
                //  三个词换成一个，不是三个都换成它）
                out += spans.take(w).joinToString("") { it.word } +
                    corr.word +
                    spans.drop(end + 1).joinToString("") { it.word }
                if (out.size >= MAX_RESULTS) return out.toList()
            }
        }
        return out.toList()
    }
}
