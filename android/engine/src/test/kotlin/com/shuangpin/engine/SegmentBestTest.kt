package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 整句 N-best 切分的测试。
 *
 * 为什么要有它：原来 [PinyinEngine.segmentWords] 只给一条最优切分，
 * 于是**等长候选几乎总是只有一条**——端侧重排模型没东西可排
 * （实测每条用例的等长候选中位只有 1 个，只有 24.4% 的用例有 2 个以上）。
 *
 * 这里守三条：
 * 1. 第一条必须和原来的最优切分**完全一致**（不许因为改了算法而改变既有行为）
 * 2. 所有备选必须**等长**（长度 = 音节数），否则模型的长度偏置会失效
 * 3. 备选不重复、数量有上限
 */
class SegmentBestTest {

    companion object {
        private lateinit var data: ImeData
        private lateinit var engine: PinyinEngine

        @BeforeClass
        @JvmStatic
        fun load() {
            data = DataLoader().load()
            engine = data.engine("xiaohe")
        }
    }

    private fun syllables(text: String): List<String> =
        text.split(" ").map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun `第一条与原来的最优切分一致`() {
        val cases = listOf(
            "wo xiang chi fan",
            "dui shi gu ze ren dan wei",
            "ni hao",
            "zhong guo ren min",
            "shi jian dao di yi ban",
        )
        for (text in cases) {
            val syl = syllables(text)
            val best = engine.segmentWords(syl).joinToString("")
            val alts = engine.segmentSentences(syl, 8)
            assertTrue("$text 应至少给出一条整句", alts.isNotEmpty())
            assertEquals("$text 的第一条备选应等于最优切分", best, alts.first())
        }
    }

    @Test
    fun `所有备选等长`() {
        val cases = listOf(
            "wo xiang chi fan",
            "dui shi gu ze ren dan wei",
            "zhong guo ren min",
        )
        for (text in cases) {
            val syl = syllables(text)
            for (alt in engine.segmentSentences(syl, 8)) {
                assertEquals(
                    "$text 的备选「$alt」长度应等于音节数 ${syl.size}",
                    syl.size,
                    alt.length,
                )
            }
        }
    }

    @Test
    fun `备选不重复且数量有上限`() {
        val syl = syllables("wo xiang chi fan")
        val alts = engine.segmentSentences(syl, 3)
        assertTrue("备选不该超过 limit，实际 ${alts.size}", alts.size <= 3)
        assertEquals("备选不该重复", alts.size, alts.distinct().size)
    }

    @Test
    fun `能给出不止一种切分`() {
        val syl = syllables("wo xiang chi fan")
        val alts = engine.segmentSentences(syl, 8)
        println("wo xiang chi fan -> $alts")
        assertTrue("应该给得出多种切分，实际=$alts", alts.size >= 2)
    }

    @Test
    fun `limit 为零时返回空`() {
        val syl = syllables("ni hao")
        assertTrue(engine.segmentSentences(syl, 0).isEmpty())
    }

    @Test
    fun `空音节返回空`() {
        assertTrue(engine.segmentSentences(emptyList(), 5).isEmpty())
    }
}
