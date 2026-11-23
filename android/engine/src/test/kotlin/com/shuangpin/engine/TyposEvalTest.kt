package com.shuangpin.engine

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * **纠错评测**：跑 `ime/typo_cases.txt`，给出 top1 / top3 / 空格命中 / 误报率。
 *
 * ## 为什么要有这一套
 *
 * 纠错这条线原来只有 8 条手写单测——**没有「成功率」这个指标，也没有防回归的门禁**。
 * 改一下判据、动一下排序，没人能立刻看出「纠错是变好了还是变差了」。
 *
 * 这个类把那件事变成可执行的：
 * - 跑一遍评测集，按错误类型分别报数
 * - 断言 **top1 / top3 / 空格命中 / 误报率都不比基线差**——改动导致下降即红
 *
 * ## 口径
 *
 * - **top1 / top3**：只看纠错候选（`CandidateKind.CORRECTION`）之间的排序。
 * - **空格命中**：候选栏**第一条**是不是正确词——这才是用户按空格真正得到的东西
 *   （空格上屏的是第一条，见 `ShuangpinImeService.commitTopCandidate`）。
 * - **误报率**：负样本（正确输入）里被误判成「打错了」的比例。
 *
 * ## 评测集
 *
 * `src/test/resources/ime/typo_cases.txt`，每行：
 * ```
 * 错打音节(空格分隔)<TAB>正确词<TAB>错误类型
 * ```
 * 音节用空格分隔是为了避开「同一个拼音串有多种切法」的歧义。
 * 错误类型见文件头的说明。
 */
class TyposEvalTest {

    companion object {
        private lateinit var engine: PinyinEngine

        @BeforeClass
        @JvmStatic
        fun load() {
            engine = TestData.engine()
        }

        /**
         * **基线**：本文件建立时（改动前）的实测值。
         *
         * - top1 58.7% / top3 67.4% / 空格命中 52.6% / 误报 10.0%
         *
         * 这几个数是门禁：任何改动让纠错变差，测试立刻红。
         * 改动**只能往上走**；要更新基线，必须在 PR 里写清为什么变差是可接受的。
         *
         * 往下取整到 0.1：断言是浮点比较，不留舍入的边。
         */
        private const val BASELINE_TOP1 = 58.6
        private const val BASELINE_TOP3 = 67.3
        private const val BASELINE_SPACE = 52.5
        private const val BASELINE_FP = 10.0

        /**
         * **正确输入的「保持原词」率下限**。
         *
         * 条件置顶（纠错候选压过精确命中）是有代价的：
         * 用户打对了的时候，候选栏第一条不能被纠错候选顶掉。
         * 这一条守住那个代价。
         *
         * **98.3%**：阈值从 40 提到 50 之后的实测值——
         * 有一类「打对了但词频分在 40~50 之间」的词（冷门词），
         * 它的精确命中会被纠错候选顶掉。
         * 换来的是错打时空格命中 61.3% → 62.6%。
         * 98% 是用户拍板接受的水位（每 50 次「打对了」有 1 次上屏的是近音词）。
         */
        private const val BASELINE_KEEP = 98.3

        /** 评测集里出现的错误类型。 */
        private val TYPES = listOf("near-initial", "near-final", "final-slip", "initial-slip", "negative")
    }

    /** 一条评测用例。 */
    private data class Case(
        val syllables: List<String>,
        val gold: String,
        val type: String,
    )

    private fun loadCases(): List<Case> =
        DataLoader().lines("typo_cases.txt").mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 3) return@mapNotNull null
            Case(
                syllables = p[0].split(' ').filter { it.isNotEmpty() },
                gold = p[1],
                type = p[2],
            )
        }

    /**
     * 把一串音节按成双拼键，打进一个会话。
     *
     * @return false = 这个音节在当前方案下打不出来（用例跳过）
     */
    private fun type(session: InputSession, syllables: List<String>): Boolean {
        for (syl in syllables) {
            val code = engine.encode(syl)
            if (code.length != 2) return false
            for (ch in code) session.pressKey(ch.toString())
        }
        return true
    }

    @Test
    fun `纠错评测集跑得起来`() {
        val cases = loadCases()
        assertTrue("评测集不该是空的", cases.isNotEmpty())
        assertTrue(
            "评测集要覆盖全部错误类型，实际 ${cases.map { it.type }.distinct()}",
            TYPES.all { t -> cases.any { it.type == t } },
        )
    }

    @Test
    fun `纠错成功率不低于基线`() {
        val cases = loadCases()

        // 按类型分别统计
        val stat = LinkedHashMap<String, IntArray>()
        for (t in TYPES) stat[t] = intArrayOf(0, 0, 0, 0) // 用例数 / top1 / top3 / 空格命中

        var skipped = 0
        var fpCases = 0
        var fpTotal = 0
        var keptCases = 0

        for (case in cases) {
            val session = InputSession(engine)
            if (!type(session, case.syllables)) {
                skipped++
                continue
            }
            val items = Candidates.build(engine, session, includeCorrections = true)
            val corrections = items.filter { it.kind == CandidateKind.CORRECTION }.map { it.text }
            val first = items.firstOrNull()?.text

            if (case.type == "negative") {
                // 负样本：正确输入，不该被判成「打错了」
                fpTotal++
                if (corrections.isNotEmpty()) fpCases++
                // **保持率**：候选栏第一条仍然应该是用户打的那个词。
                // 条件置顶不能为了纠错把「用户打对了的常用词」顶掉。
                if (first == case.gold) keptCases++
                continue
            }

            val st = stat.getValue(case.type)
            st[0]++
            if (corrections.firstOrNull() == case.gold) st[1]++
            if (corrections.take(3).contains(case.gold)) st[2]++
            if (first == case.gold) st[3]++
        }

        // ---- 报告 ----
        println("── 纠错评测 ──")
        var total = 0
        var top1 = 0
        var top3 = 0
        var space = 0
        for (t in TYPES) {
            val st = stat.getValue(t)
            if (t == "negative") {
                println(
                    "  %-14s 用例 %4d  误报 %d (%.1f%%)  保持原词 %d (%.1f%%)".format(
                        t, st[0], fpCases, fpPercent(fpCases, fpTotal),
                        keptCases, fpPercent(keptCases, fpTotal),
                    )
                )
                continue
            }
            total += st[0]; top1 += st[1]; top3 += st[2]; space += st[3]
            println(
                "  %-14s 用例 %4d  top1 %5.1f%%  top3 %5.1f%%  空格命中 %5.1f%%".format(
                    t, st[0], percent(st[1], st[0]), percent(st[2], st[0]), percent(st[3], st[0]),
                )
            )
        }
        println(
            "  %-14s 用例 %4d  top1 %5.1f%%  top3 %5.1f%%  空格命中 %5.1f%%  误报 %.1f%%  保持原词 %.1f%%%s".format(
                "合计", total, percent(top1, total), percent(top3, total), percent(space, total),
                fpPercent(fpCases, fpTotal), fpPercent(keptCases, fpTotal),
                if (skipped > 0) "  (跳过 $skipped)" else "",
            )
        )

        // ---- 门禁 ----
        val top1Pct = percent(top1, total)
        val top3Pct = percent(top3, total)
        val spacePct = percent(space, total)
        val fpPct = fpPercent(fpCases, fpTotal)
        val keptPct = fpPercent(keptCases, fpTotal)

        println(
            "  基线：top1 $BASELINE_TOP1% / top3 $BASELINE_TOP3% / 空格 $BASELINE_SPACE% / " +
                "误报 $BASELINE_FP% / 保持原词 $BASELINE_KEEP%",
        )
        assertTrue("纠错 top1 掉了：$top1Pct% < 基线 $BASELINE_TOP1%", top1Pct >= BASELINE_TOP1)
        assertTrue("纠错 top3 掉了：$top3Pct% < 基线 $BASELINE_TOP3%", top3Pct >= BASELINE_TOP3)
        assertTrue("空格命中掉了：$spacePct% < 基线 $BASELINE_SPACE%", spacePct >= BASELINE_SPACE)
        assertTrue("误报涨了：$fpPct% > 基线 $BASELINE_FP%", fpPct <= BASELINE_FP)
        // 条件置顶的代价：用户打对了的词，不能被纠错候选顶掉
        assertTrue("保持原词率掉了：$keptPct% < 基线 $BASELINE_KEEP%", keptPct >= BASELINE_KEEP)
    }

    private fun percent(part: Int, whole: Int): Double =
        if (whole <= 0) 0.0 else part * 100.0 / whole

    private fun fpPercent(part: Int, whole: Int): Double = percent(part, whole)
}
