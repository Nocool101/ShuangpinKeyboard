package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * **自动纠错**的测试。
 *
 * 守的是两件事：
 * 1. 老规则照旧：zh/z、ch/c、sh/s 搞混时给出更常用的那个词
 * 2. 新规则：**韵母键按错**时也能纠回来
 *    （"xianrifuwoding" → 「仙人抚我顶」——en 在 f 键上，手滑按成 i 键）
 */
class TyposTest {

    companion object {
        private lateinit var engine: PinyinEngine

        @BeforeClass
        @JvmStatic
        fun load() {
            engine = TestData.engine()
        }
    }

    private fun corrections(vararg syllables: String): List<Correction> =
        Typos.corrections(engine.dictionary, engine.table, syllables.toList())

    private fun words(vararg syllables: String): List<String> =
        corrections(*syllables).map { it.word }

    // ------------------------------------------------------------------
    // 老规则：近音混淆
    // ------------------------------------------------------------------

    @Test
    fun `zihui 能纠成智慧或只会`() {
        // 「智慧」打成「zihui」——老规则就该认出来
        val c = corrections("zi", "hui")
        assertTrue("纠错候选应该有智慧/只会，实际 $c", c.any { it.word == "智慧" || it.word == "只会" })
    }

    @Test
    fun `词频不够高就不纠`() {
        // 「四是」74 分 vs「事实」76 分——只高 2 分，不够 10 分的门槛，不纠
        val c = corrections("si", "shi")
        assertTrue("不该把「四是」纠成「事实」，实际 $c", c.none { it.word == "事实" })
    }

    @Test
    fun `原串在词库里时不启用宽规则`() {
        // 「xianrenfuwoding」本身就在词库里（仙人抚我顶）
        // → 不该把它当成「打错了」再去乱换韵母
        val c = corrections("xian", "ren", "fu", "wo", "ding")
        assertTrue("原串本来就对，不该有纠错候选，实际 $c", c.isEmpty())
    }

    // ------------------------------------------------------------------
    // 新规则：韵母键按错
    // ------------------------------------------------------------------

    @Test
    fun `韵母按错键能纠回来`() {
        // 用户要打「仙人抚我顶」，却打成了 xianrifuwoding
        // （"ren" 的 en 在 f 键，手滑按成 i 键 → "ri"）
        val c = corrections("xian", "ri", "fu", "wo", "ding")
        assertTrue(
            "应该纠出「仙人抚我顶」，实际 ${c.map { it.word }}",
            c.any { it.word == "仙人抚我顶" },
        )
    }

    @Test
    fun `纠错候选按分数排序`() {
        val c = corrections("xian", "ri", "fu", "wo", "ding")
        assertTrue(c.isNotEmpty())
        // 分数高的排前面
        assertEquals(c.sortedByDescending { it.score }, c)
    }

    @Test
    fun `宽规则只在原串查无此词时启用`() {
        // 宽规则：同声母的所有韵母都试一遍
        val wide = Typos.alternatives(engine.table, "ri", wide = true)
        assertTrue("宽规则应该包含 ren，实际 $wide", wide.contains("ren"))
        assertFalse("宽规则不该包含自己", wide.contains("ri"))

        // 窄规则（老规则）不含 ren——i 和 en 根本不是近音
        val narrow = Typos.alternatives(engine.table, "ri")
        assertFalse("窄规则不该包含 ren，实际 $narrow", narrow.contains("ren"))
    }

    @Test
    fun `纠错候选必须是整词`() {
        // 单字不算「纠正」——纠正的是一整串
        val c = corrections("xian", "ri")
        assertTrue(c.all { it.word.length == 2 })
    }

    @Test
    fun `空输入没有纠错候选`() {
        assertTrue(corrections().isEmpty())
    }
}
