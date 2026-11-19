package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 长词扩展（联想）的测试。
 *
 * 这里守的是三条硬规则：
 *
 * 1. **词级过滤**——拼音撞了不算扩展。词库的键是拼音串，
 *    「shi」能命中「诗艾娜」「世阿弥」，但它们不是「是/十/石」开头的词，
 *    就不该进扩展（这条以前没人守，正是它决定了联想会不会变成垃圾场）。
 * 2. **长度与分数上限**——扩展不能太长（长尾巴会淹掉主候选），
 *    分数太低的（rime-ice 里 27 分是「无人工词频」的默认档）也不该进。
 * 3. **成本可控**——单音节前缀能命中上万行，绝不能扫穿。
 */
class ExtensionTest {

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

    private fun ext(pinyin: String, limit: Int = Extensions.DEFAULT_LIMIT) =
        engine.extensions(pinyin, limit)

    private fun baseWords(pinyin: String): List<String> =
        engine.dictionary.phraseCandidates(pinyin) +
            engine.dictionary.charCandidates(pinyin).map { it.text }

    // ---------------------------------------------------------------
    // 基本能力
    // ---------------------------------------------------------------

    @Test
    fun `nihao 能扩展出你好吗`() {
        val words = ext("nihao").map { it.text }
        assertTrue("nihao 应扩展出「你好吗」，实际=$words", words.contains("你好吗"))
        assertTrue("「你好吗」应排进前列，实际=$words", words.indexOf("你好吗") <= 3)
    }

    @Test
    fun `shijie 能扩展出世界上和世界杯`() {
        val words = ext("shijie").map { it.text }
        assertTrue("shijie 应扩展出「世界上」，实际=$words", words.contains("世界上"))
        assertTrue("shijie 应扩展出「世界杯」，实际=$words", words.contains("世界杯"))
    }

    @Test
    fun `zhongguo 能扩展出中国人`() {
        val words = ext("zhongguo").map { it.text }
        assertTrue("zhongguo 应扩展出「中国人」，实际=$words", words.contains("中国人"))
    }

    @Test
    fun `单音节也能扩展出组词`() {
        // 「hao」在词表里没有词条，只有字表里的「好/号/浩…」，
        // 所以基准词必须把字表算进去，否则一个扩展都出不来。
        val words = ext("hao").map { it.text }
        assertTrue("hao 应能扩展出组词，实际=$words", words.isNotEmpty())
        assertTrue(
            "hao 的扩展应包含以「好」开头的词，实际=$words",
            words.any { it.startsWith("好") },
        )
    }

    // ---------------------------------------------------------------
    // 规则一：词级过滤
    // ---------------------------------------------------------------

    @Test
    fun `扩展必须以基准词开头`() {
        for (p in listOf("nihao", "shijie", "zhongguo", "hao")) {
            val base = baseWords(p)
            assertTrue("$p 应当有基准词", base.isNotEmpty())
            for (c in ext(p, 40)) {
                val ok = base.any { b ->
                    b.length < c.text.length &&
                        c.text.startsWith(b) &&
                        c.text.length - b.length <= Extensions.MAX_EXTRA_CHARS
                }
                assertTrue(
                    "扩展「${c.text}」不是任何基准词的词级扩展（基准=$base）",
                    ok,
                )
            }
        }
    }

    @Test
    fun `拼音巧合不算扩展`() {
        // 「shi」的拼音能撞上「诗艾娜」「世阿弥」「食案方丈」，
        // 它们不是「是/十/石/时」开头的词，不该出现在扩展里。
        val words = ext("shi", 60).map { it.text }
        for (junk in listOf("诗艾娜", "世阿弥", "食案方丈")) {
            assertFalse("「$junk」是拼音巧合，不该出现在扩展里", words.contains(junk))
        }
    }

    @Test
    fun `基准词自己不该出现在扩展里`() {
        val words = ext("nihao", 40).map { it.text }
        assertFalse("「你好」是基准词，不该作为扩展出现", words.contains("你好"))
    }

    // ---------------------------------------------------------------
    // 规则二：长度与分数上限
    // ---------------------------------------------------------------

    @Test
    fun `扩展不比基准词长太多`() {
        for (p in listOf("nihao", "shijie", "zhongguo")) {
            val base = baseWords(p)
            for (c in ext(p, 40)) {
                val diff = base.filter { c.text.startsWith(it) }
                    .minOfOrNull { c.text.length - it.length } ?: Int.MAX_VALUE
                assertTrue(
                    "扩展「${c.text}」比基准词长了 $diff 字，超过上限 ${Extensions.MAX_EXTRA_CHARS}",
                    diff <= Extensions.MAX_EXTRA_CHARS,
                )
            }
        }
    }

    @Test
    fun `分数太低的扩展被滤掉`() {
        for (p in listOf("nihao", "shijie", "zhongguo")) {
            for (c in ext(p, 60)) {
                assertTrue(
                    "分数 ${c.score} 的扩展不该出现：${c.text}",
                    c.score > Extensions.MIN_SCORE,
                )
            }
        }
    }

    @Test
    fun `扩展按词频降序`() {
        val scores = ext("zhongguo", 40).map { it.score }
        assertEquals("扩展应按分数降序排列", scores.sortedDescending(), scores)
    }

    @Test
    fun `扩展条数不超过 limit`() {
        assertTrue(ext("zhongguo", 5).size <= 5)
        assertTrue(ext("zhongguo", 1).size <= 1)
    }

    // ---------------------------------------------------------------
    // 规则三：成本可控
    // ---------------------------------------------------------------

    @Test
    fun `单音节大范围也不卡`() {
        val t0 = System.nanoTime()
        val r = ext("shi", 12)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("shi 的扩展条数应受 limit 约束，实际=${r.size}", r.size <= 12)
        assertTrue("shi 的扩展耗时 ${ms}ms，超出预期", ms < 200)
    }

    @Test
    fun `前缀区间扫描只覆盖同前缀的行`() {
        val store = engine.dictionary.phrases
        val range = store.prefixRange("nihao")
        assertTrue("nihao 的前缀区间不该是空的", range.count() > 0)
        for (idx in range) {
            assertTrue(
                "第 $idx 行的键 ${store.keyAt(idx)} 不该在 nihao 的区间里",
                store.keyAt(idx).startsWith("nihao"),
            )
        }
        // 区间之后的第一行必须不匹配
        val after = range.last + 1
        if (after < store.size) {
            assertFalse(
                "区间之后的键 ${store.keyAt(after)} 不该以 nihao 开头",
                store.keyAt(after).startsWith("nihao"),
            )
        }
    }

    @Test
    fun `前缀区间与二分定位一致`() {
        val store = engine.dictionary.phrases
        for (p in listOf("nih", "shi", "zhong", "nihao")) {
            val start = store.lowerBound(p)
            val range = store.prefixRange(p)
            assertEquals("$p 的区间起点应等于二分定位结果", start, range.first)
        }
    }

    // ---------------------------------------------------------------
    // 边界
    // ---------------------------------------------------------------

    @Test
    fun `空输入和词库没有的串返回空`() {
        assertTrue(ext("").isEmpty())
        assertTrue(ext("zzzzzz").isEmpty())
        assertTrue(ext("ni", 0).isEmpty())
    }

    @Test
    fun `音节表能支撑枚举兜底`() {
        // 枚举法要靠音节清单穷举「当前串 + 一个音节」，
        // 音节表太空的话兜底路径就没意义了。
        assertTrue("音节表不该少于 400 个，实际=${data.table.syllables.size}", data.table.syllables.size >= 400)
    }
}
