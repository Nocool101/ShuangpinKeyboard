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

    // ------------------------------------------------------------------
    // 档位：近音组优先，宽规则靠后
    // ------------------------------------------------------------------

    @Test
    fun `近音组变体档位是 0 宽规则变体档位是 1`() {
        // 「ri」的近音组变体（l↔n 那组之外，近音组里 r↔l）
        val near = Typos.tieredAlternatives(engine.table, "ri", wide = false)
        assertTrue("近音组应该有变体，实际 $near", near.isNotEmpty())
        assertTrue("近音组变体档位都该是 0，实际 $near", near.all { it.tier == Typos.TIER_NEAR })

        // 宽规则多出来的「ren」是档位 1
        val wide = Typos.tieredAlternatives(engine.table, "ri", wide = true)
        val ren = wide.firstOrNull { it.syllable == "ren" }
        assertTrue("宽规则该有 ren，实际 $wide", ren != null)
        assertEquals("ren 属于宽规则档位", Typos.TIER_WIDE, ren!!.tier)
    }

    @Test
    fun `近音组变体排在宽规则变体前面`() {
        // 「men sui」（打错成「门锁」的音）
        // 近音组变体 → 「梦碎」(53 分，档位 0)
        // 宽规则变体 → 「门锁」(64 分，档位 1)
        // 裸看词频是「门锁」更高，但宽规则更可能是错的 → 排序分 53 vs 52，「梦碎」在前。
        val c = corrections("men", "sui")
        assertTrue("应该有纠错候选，实际 $c", c.isNotEmpty())
        assertEquals("近音组的候选该排第一", "梦碎", c.first().word)
        assertEquals("第一名的档位是近音组", Typos.TIER_NEAR, c.first().tier)
        val menSuo = c.firstOrNull { it.word == "门锁" }
        assertTrue("宽规则的候选也该在表里，实际 $c", menSuo != null)
        assertEquals("门锁属于宽规则档位", Typos.TIER_WIDE, menSuo!!.tier)
    }

    @Test
    fun `纠错候选带着档位出来`() {
        val c = corrections("men", "sui")
        assertTrue(c.all { it.tier == Typos.TIER_NEAR || it.tier == Typos.TIER_WIDE })
    }

    @Test
    fun `宽档的门槛比近音档高`() {
        // 宽规则变体只在「额外开着」时用更严的门槛
        assertEquals("近音档用基础门槛", Typos.SCORE_MARGIN, Typos.marginFor(Typos.TIER_NEAR))
        assertEquals(
            "宽档开着的时候门槛更高",
            Typos.WIDE_MARGIN,
            Typos.marginFor(Typos.TIER_WIDE, wideAlways = true),
        )
        assertTrue(
            "宽档门槛必须高于近音档",
            Typos.marginFor(Typos.TIER_WIDE, wideAlways = true) > Typos.marginFor(Typos.TIER_NEAR),
        )
        assertEquals(
            "宽规则没额外开着时沿用基础门槛",
            Typos.SCORE_MARGIN,
            Typos.marginFor(Typos.TIER_WIDE, wideAlways = false),
        )
    }
}
