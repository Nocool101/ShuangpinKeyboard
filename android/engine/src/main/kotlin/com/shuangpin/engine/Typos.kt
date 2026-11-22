package com.shuangpin.engine

/**
 * 自动纠错：拼音打错了，帮你找回来。
 *
 * ## 要解决什么
 *
 * 双拼用户最高频的错误是**把 zh/z、ch/c、sh/s 搞混**——
 * 「智慧」打成「zihui」，引擎只能给你「自会」「字汇」「紫灰」这些乱七八糟的东西。
 *
 * 而这些错误有个共同点：**在双拼的键位上，打错之后仍然是一个合法音节**。
 * 于是引擎完全不知道用户打错了——它只会老老实实按「zi hui」去找词。
 *
 * ## 怎么解决
 *
 * 在**全拼音节层面**做近音替换，把可能打错的音节换一个再查词库：
 *
 * ```
 * 用户打的：  zi  hui        → 词库命中「自会」(55 分)
 * 近音变体：  zhi hui        → 词库命中「只会」(79 分)、「智慧」(76 分)
 *                                    ↑ 分数明显更高 → 这才是用户想打的
 * ```
 *
 * 判据是**词频分数**：纠错候选必须比原串的首选**明显更常用**才算数。
 * 这条判据能同时挡住两种误报：
 * - 「cishi」(此时 76 分) 不该被纠成「chishi」(吃屎 48 分) → 分数更低，不纠 ✅
 * - 「sishi」(四是 74 分) 不该被纠成「shishi」(事实 76 分) → 只高 2 分，差得不够多，不纠 ✅
 *
 * ## 替换哪些
 *
 * 声母组（发音相近 / 双拼里最容易记混）：
 * `z↔zh` `c↔ch` `s↔sh` `n↔l` `f↔h` `r↔l` `m↔n`
 *
 * 韵母组（鼻音尾差异，双拼里是不同键位但读音极近）：
 * `an↔ang` `en↔eng` `in↔ing` `ian↔iang` `uan↔uang` `ong↔iong`
 *
 * 替换后必须**仍是合法音节**（查音节表），不然变体是凭空造的。
 */
object Typos {

    /**
     * 纠错候选要比原串首选高出这么多分才展示。
     *
     * 分数是 `log2(词频)*4`。差 10 分 ≈ 词频差 8 倍——
     * 「自会」→「智慧」差 24 分（要纠），「四是」→「事实」差 2 分（不纠）。
     */
    const val SCORE_MARGIN = 10

    /** 最多给几个纠错候选。 */
    const val MAX_CORRECTIONS = 3

    /** 声母近音组。 */
    private val INITIAL_GROUPS = listOf(
        listOf("z", "zh"),
        listOf("c", "ch"),
        listOf("s", "sh"),
        listOf("n", "l"),
        listOf("f", "h"),
        listOf("r", "l"),
        listOf("m", "n"),
    )

    /** 韵母近音组。 */
    private val FINAL_GROUPS = listOf(
        listOf("an", "ang"),
        listOf("en", "eng"),
        listOf("in", "ing"),
        listOf("ian", "iang"),
        listOf("uan", "uang"),
        listOf("ong", "iong"),
    )

    /**
     * 找出「用户可能打错了」的候选词。
     *
     * @param dictionary 词库（查词与词频）
     * @param table      音节表（判断替换后的音节合不合法）
     * @param syllables  用户这一轮输入的音节序列
     * @return 按分数降序的纠错候选；没有就是空表
     */
    fun corrections(
        dictionary: Dictionary,
        table: SyllableTable,
        syllables: List<String>,
    ): List<Correction> {
        if (syllables.isEmpty()) return emptyList()
        val originalKey = syllables.joinToString("")
        // top() 只解析首个候选（纠错每次按键都要跑，别把整个候选列表都建出来）
        val original = dictionary.phrases.top(originalKey)
        val originalScore = original?.score ?: 0

        // **原串在词库里根本没有** → 用户百分之百打错了，
        // 这时候把「同声母换韵母」的全量变体也试一遍。
        //
        // 为什么要加这一档：双拼最高频的手误是**韵母键按错**
        // （"xianrenfuwoding" 的 en 在 f 键上，手滑按成 i 键就成了 "xianrifuwoding"）。
        // 这跟 zh/z 那种近音混淆不一样,近音组里根本没有 i↔en 这一对,
        // 老规则完全认不出来——而用户的意图明明白白写着「仙人抚我顶」。
        //
        // 只在「原串查无此词」时开这一档：原串要是能查到,
        // 那用户可能就是故意打冷门词,不该乱改。
        val wide = original == null

        val out = LinkedHashMap<String, Correction>()
        for (i in syllables.indices) {
            for (alt in alternatives(table, syllables[i], wide)) {
                val variant = syllables.toMutableList()
                variant[i] = alt
                val key = variant.joinToString("")
                if (key == originalKey) continue
                val cand = dictionary.phrases.top(key) ?: continue
                // 必须是整词：字数等于音节数，单字不算「纠正」
                if (cand.text.length != syllables.size) continue
                // 词频没明显更高 → 不是纠正，是更冷门的写法
                if (cand.score <= originalScore + SCORE_MARGIN) continue
                if (out.containsKey(cand.text)) continue
                out[cand.text] = Correction(
                    word = cand.text,
                    score = cand.score,
                    from = originalKey,
                    to = key,
                )
            }
        }
        // 按分数取前几个——**不要按位置截断**：
        // 错的那个音节可能在第 3 个（"xian|ri|fu|wo|ding" 的第 2 个），
        // 按位置截的话正确答案根本轮不到。
        return out.values.sortedByDescending { it.score }.take(MAX_CORRECTIONS)
    }

    /**
     * 某个音节的近音变体（只保留合法音节）。
     *
     * @param wide 加上「同声母换任意韵母」这一档（只在原串查无此词时用）
     */
    fun alternatives(table: SyllableTable, syllable: String, wide: Boolean = false): List<String> {
        val parsed = table.parse(syllable)
        val initial = parsed.initial
        val final = parsed.final
        val out = LinkedHashSet<String>()

        // 声母替换：换声母、留韵母
        if (initial != null) {
            for (group in INITIAL_GROUPS) {
                if (!group.contains(initial)) continue
                for (alt in group) {
                    if (alt == initial) continue
                    val syl = table.byInitialFinal["$alt|$final"] ?: continue
                    out.add(syl)
                }
            }
        }

        // 韵母替换：留声母、换韵母
        for (group in FINAL_GROUPS) {
            if (!group.contains(final)) continue
            for (alt in group) {
                if (alt == final) continue
                val syl = if (initial == null) {
                    table.zeroSyllables[alt]
                } else {
                    table.byInitialFinal["$initial|$alt"]
                } ?: continue
                out.add(syl)
            }
        }

        // 宽规则：同声母的**所有**韵母都试一遍（手滑按错韵母键的兜底）
        if (wide) {
            if (initial != null) {
                for (fin in table.finalsByInitial[initial].orEmpty()) {
                    if (fin == final) continue
                    val syl = table.byInitialFinal["$initial|$fin"] ?: continue
                    out.add(syl)
                }
            } else {
                // 零声母：把零声母的那几个音节都试一遍
                for ((fin, syl) in table.zeroSyllables) {
                    if (syl == syllable) continue
                    out.add(syl)
                }
            }
        }
        return out.toList()
    }
}

/**
 * 一条纠错建议。
 *
 * @param word  纠正后应该打的词
 * @param score 词频分数
 * @param from  用户实际打的拼音串
 * @param to    纠正后的拼音串
 */
data class Correction(
    val word: String,
    val score: Int,
    val from: String,
    val to: String,
)
