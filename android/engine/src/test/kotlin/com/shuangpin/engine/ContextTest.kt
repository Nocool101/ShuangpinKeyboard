package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * **上下文选词**的测试。
 *
 * 这一组解决的是「候选词触发太慢」和「建议不符合预期」：
 * - 跨界组合：「结」+「guo」→ 该给「果」，不是词频最高的「过」
 * - 下一词：刚上屏「结」→ 给出「果」「婚」这些接下来最可能接的字
 *
 * 全是**本地词库**算出来的（一次二分查找），不用等模型。
 */
class ContextTest {

    companion object {
        private lateinit var data: ImeData
        private lateinit var engine: PinyinEngine

        @BeforeClass
        @JvmStatic
        fun load() {
            data = TestData.data
            engine = data.engine("xiaohe")
        }
    }

    private val dictionary get() = engine.dictionary

    // ---------------------------------------------------------------
    // 跨界组合
    // ---------------------------------------------------------------

    @Test
    fun `上一块加当前拼音在词库里是一个词时给出后半截`() {
        // 「结」(jie) + 「guo」→ 词库里的「结果」→ 这一轮该给「果」
        val out = Context.boundaryCandidates(
            dictionary = dictionary,
            prevPinyin = "jie",
            prevText = "结",
            curPinyin = "guo",
            curSyllables = 1,
        )
        assertEquals("最高频的那条是「果」", "果", out.first())
    }

    @Test
    fun `词里的字跟上一块对不上时不给`() {
        // 「jieguo」在词库里还有「接过」「借过」「结过」——
        // 但没有以「解」开头的，所以「解」+「guo」不该命中
        val out = Context.boundaryCandidates(
            dictionary = dictionary,
            prevPinyin = "jie",
            prevText = "解",
            curPinyin = "guo",
            curSyllables = 1,
        )
        assertTrue("对不上的不该给，实际 $out", out.isEmpty())
    }

    @Test
    fun `没有上文时不给`() {
        val out = Context.boundaryCandidates(
            dictionary = dictionary,
            prevPinyin = "",
            prevText = "",
            curPinyin = "guo",
            curSyllables = 1,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `词长对不上当前音节数时不给`() {
        // 「结果的」比「结」多两个字，这一轮只有一个音节 → 对不上
        val out = Context.boundaryCandidates(
            dictionary = dictionary,
            prevPinyin = "jie",
            prevText = "结",
            curPinyin = "guode",
            curSyllables = 1,
        )
        assertTrue("对不上的不该给，实际 $out", out.isEmpty())
    }

    @Test
    fun `多个音节时给出对应的后半截`() {
        // 「结」+「guo」（两个音节）→ 词库里有没有「结果XX」这种三字词
        val out = Context.boundaryCandidates(
            dictionary = dictionary,
            prevPinyin = "jie",
            prevText = "结",
            curPinyin = "guode",
            curSyllables = 2,
        )
        // 「结果的」正好对上（结 + 的 → 但 curPinyin 是 "de"）
        val two = Context.boundaryCandidates(
            dictionary = dictionary,
            prevPinyin = "jie",
            prevText = "结",
            curPinyin = "de",
            curSyllables = 1,
        )
        assertTrue("「结果的」应该命中，实际 $two", two.contains("的"))
        assertTrue(out.isEmpty() || out.all { it.length == 2 })
    }

    // ---------------------------------------------------------------
    // 下一词
    // ---------------------------------------------------------------

    @Test
    fun `下一词来自词库里以它开头的词`() {
        val out = Context.nextWords(
            dictionary = dictionary,
            syllables = engine.table.syllables,
            prevPinyin = "jie",
            prevText = "结",
        )
        assertTrue("下一词不该是空的，实际 $out", out.isNotEmpty())
        assertTrue("应该给出「果」，实际 $out", out.contains("果"))
    }

    @Test
    fun `下一词不含上一块本身`() {
        val out = Context.nextWords(
            dictionary = dictionary,
            syllables = engine.table.syllables,
            prevPinyin = "jie",
            prevText = "结",
        )
        assertTrue("不该把上一块本身当下一词，实际 $out", out.none { it == "结" })
    }

    @Test
    fun `没有上文时没有下一词`() {
        val out = Context.nextWords(
            dictionary = dictionary,
            syllables = engine.table.syllables,
            prevPinyin = "",
            prevText = "",
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `下一词数量有上限`() {
        val out = Context.nextWords(
            dictionary = dictionary,
            syllables = engine.table.syllables,
            prevPinyin = "jie",
            prevText = "结",
            limit = 2,
        )
        assertTrue("不该超过上限，实际 $out", out.size <= 2)
    }
}
