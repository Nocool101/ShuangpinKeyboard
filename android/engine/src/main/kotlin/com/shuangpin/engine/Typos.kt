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
 *
 * ## 档位：近音组优先，宽规则靠后
 *
 * 变体分两档：
 * - **档位 0（近音组）**：上面两组近音替换。**用户真打错时，错的大概率就是这几组之一**——
 *   因为这些是双拼用户最容易记混的键。
 * - **档位 1（宽规则）**：同声母换**任意**韵母。手滑按错韵母键才会走到这一档，
 *   可能性比近音组低得多，所以排序时排在后面（见 [TIER_BONUS]）。
 *
 * 实测（`TyposEvalTest`，评测集 290 条）：
 * - 只按词频排序：近声母类 top1 66.7%、近韵母类 62.9%
 * - 加上档位加分：**近声母类 79.2%、近韵母类 75.9%**，而**误报率一点没涨**
 *
 * 换句话说：候选生成的范围不变，只把「更可能的那一档」往前挪。
 */
object Typos {

    /**
     * 纠错候选要比原串首选高出这么多分才展示。
     *
     * 分数是 `log2(词频)*4`。差 10 分 ≈ 词频差 8 倍——
     * 「自会」→「智慧」差 21 分（要纠），「四是」→「事实」差 2 分（不纠）。
     */
    const val SCORE_MARGIN = 10

    /** 最多给几个纠错候选。 */
    const val MAX_CORRECTIONS = 3

    /** 档位：近音组变体。 */
    const val TIER_NEAR = 0

    /** 档位：宽规则变体（同声母换任意韵母）。 */
    const val TIER_WIDE = 1

    /**
     * **近音组变体的排序加分**。
     *
     * 排序分 = `词频分 - TIER_BONUS * 档位`。
     * 也就是说：一个宽规则变体要压在近音组变体前面，词频得比它高 12 分（约 8 倍）。
     *
     * 为什么不给宽规则加更狠的惩罚：近音组是「用户真会打错的地方」，
     * 宽规则是「兜底」。12 分的差距刚好挡住宽规则的噪声，又不至于
     * 把真正更常用的宽规则候选（用户就是按错了韵母键）也一起压下去。
     */
    const val TIER_BONUS = 12

    /**
     * **宽规则是否始终启用**。
     *
     * `false`（默认）：只在「整串在词库里查无此词」时启用——
     * 原串能查到就可能是用户故意打冷门词，不该乱改。
     *
     * `true`：任何时候都启用。实测能多救回一点「韵母按错」（+1.2pp），
     * 但误报率从 4.2% 涨到 6.2%——性价比一般，所以**默认关着**。
     * 真机反馈「韵母按错纠不回来」频发时再打开。
     */
    const val WIDE_ALWAYS = false

    /**
     * **宽规则变体的准入门槛**（只在 [WIDE_ALWAYS] 打开时生效）。
     *
     * 宽规则变体比近音组「更可能是错的」，所以门槛要更高：
     * 词频分要比原串首选高 `WIDE_MARGIN` 分（约 2^7 = 128 倍）才认。
     */
    const val WIDE_MARGIN = 28

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
        // （[WIDE_ALWAYS] 打开时不再有这个限制，代价是误报变多。）
        val wide = WIDE_ALWAYS || (original == null)

        val out = LinkedHashMap<String, Correction>()
        for (i in syllables.indices) {
            for (alt in tieredAlternatives(table, syllables[i], wide)) {
                val variant = syllables.toMutableList()
                variant[i] = alt.syllable
                val key = variant.joinToString("")
                if (key == originalKey) continue
                val cand = dictionary.phrases.top(key) ?: continue
                // 必须是整词：字数等于音节数，单字不算「纠正」
                if (cand.text.length != syllables.size) continue
                // 词频没明显更高 → 不是纠正，是更冷门的写法
                val margin = marginFor(alt.tier)
                if (cand.score <= originalScore + margin) continue
                val corr = Correction(
                    word = cand.text,
                    score = cand.score,
                    from = originalKey,
                    to = key,
                    tier = alt.tier,
                )
                // 同一个词可能从多条路绕过来（近音组 / 宽规则都命中）：
                // 留**排序分更高**的那条，别让先到先得的顺序决定。
                val old = out[corr.word]
                if (old == null || corr.rank > old.rank) out[corr.word] = corr
            }
        }
        // 按排序分取前几个——**不要按位置截断**：
        // 错的那个音节可能在第 3 个（"xian|ri|fu|wo|ding" 的第 2 个），
        // 按位置截的话正确答案根本轮不到。
        return out.values.sortedByDescending { it.rank }.take(MAX_CORRECTIONS)
    }

    /**
     * 某个档位的**准入门槛**：纠错候选要比原串首选高出这么多分。
     *
     * @param tier        档位（[TIER_NEAR] / [TIER_WIDE]）
     * @param wideAlways 宽规则是否始终启用（[WIDE_ALWAYS]）。
     *        宽规则变体只在它「额外开着」时用更严的门槛——
     *        否则老行为（「原串查无此词才开宽规则」）会被误伤。
     */
    fun marginFor(tier: Int, wideAlways: Boolean = WIDE_ALWAYS): Int =
        if (tier == TIER_WIDE && wideAlways) WIDE_MARGIN else SCORE_MARGIN

    /**
     * 某个音节的近音变体（只保留合法音节）。
     *
     * 保留这个函数是为了不破坏现有调用方（`TyposTest` 直接调它）。
     * 要看档位就用 [tieredAlternatives]。
     *
     * @param wide 加上「同声母换任意韵母」这一档（只在原串查无此词时用）
     */
    fun alternatives(table: SyllableTable, syllable: String, wide: Boolean = false): List<String> =
        tieredAlternatives(table, syllable, wide).map { it.syllable }

    /**
     * 某个音节的近音变体，**带档位**（唯一真源）。
     *
     * @param wide 加上「同声母换任意韵母」这一档（宽规则，档位 [TIER_WIDE]）
     */
    fun tieredAlternatives(table: SyllableTable, syllable: String, wide: Boolean = false): List<Alt> {
        val parsed = table.parse(syllable)
        val initial = parsed.initial
        val final = parsed.final
        // 同一个音节可能从近音组和宽规则都绕过来——近音组优先，所以只留第一次。
        val out = LinkedHashMap<String, Alt>()

        fun add(syl: String?, tier: Int) {
            if (syl.isNullOrEmpty()) return
            out.putIfAbsent(syl, Alt(syl, tier))
        }

        // 声母替换：换声母、留韵母
        if (initial != null) {
            for (group in INITIAL_GROUPS) {
                if (!group.contains(initial)) continue
                for (alt in group) {
                    if (alt == initial) continue
                    add(table.byInitialFinal["$alt|$final"], TIER_NEAR)
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
                }
                add(syl, TIER_NEAR)
            }
        }

        // 宽规则：同声母的**所有**韵母都试一遍（手滑按错韵母键的兜底）
        if (wide) {
            if (initial != null) {
                for (fin in table.finalsByInitial[initial].orEmpty()) {
                    if (fin == final) continue
                    add(table.byInitialFinal["$initial|$fin"], TIER_WIDE)
                }
            } else {
                // 零声母：把零声母的那几个音节都试一遍
                for ((fin, syl) in table.zeroSyllables) {
                    if (syl == syllable) continue
                    add(syl, TIER_WIDE)
                }
            }
        }
        return out.values.toList()
    }
}

/**
 * 一个近音变体。
 *
 * @param syllable 变体音节
 * @param tier     档位：[Typos.TIER_NEAR] 近音组 / [Typos.TIER_WIDE] 宽规则
 */
data class Alt(val syllable: String, val tier: Int)

/**
 * 一条纠错建议。
 *
 * @param word  纠正后应该打的词
 * @param score 词频分数
 * @param from  用户实际打的拼音串
 * @param to    纠正后的拼音串
 * @param tier  这个纠错来自哪一档（近音组 / 宽规则）
 */
data class Correction(
    val word: String,
    val score: Int,
    val from: String,
    val to: String,
    val tier: Int = Typos.TIER_NEAR,
) {
    /**
     * **排序分**：词频分减去档位惩罚。
     *
     * 近音组的纠错比宽规则的更可信，所以同等词频下近音组排前面。
     */
    val rank: Int get() = score - Typos.TIER_BONUS * tier
}
