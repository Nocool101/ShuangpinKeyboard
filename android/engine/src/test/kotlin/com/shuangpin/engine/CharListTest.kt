package com.shuangpin.engine

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 候选字的切分。
 *
 * 这块出过一个问题：字表扩了之后（多了 Unihan 大字表），
 * 扩展区的字（𫘪、𠒜 这种）在 UTF-16 里是**两个码元**，
 * 而原来的代码把「连写起来的候选字」按字符切——
 * 一个扩展字被劈成两个乱码，候选栏里显示成两个方块。
 *
 * 切字必须按**字表里的条目**切，一个字一个元素。
 */
class CharListTest {

    companion object {
        private lateinit var engine: PinyinEngine

        @BeforeClass
        @JvmStatic
        fun load() {
            engine = TestData.engine()
        }
    }

    /** 是不是被劈开的半个字（落单的代理项）。 */
    private fun isSplit(text: String): Boolean {
        if (text.isEmpty()) return true
        val first = text[0]
        if (!Character.isHighSurrogate(first)) return false
        // 高代理项后面必须紧跟低代理项，否则就是劈开了
        return text.length < 2 || !Character.isLowSurrogate(text[1])
    }

    @Test
    fun `候选字是一个字一个元素`() {
        val chars = engine.candidateCharList("yuan")
        assertTrue("yuan 应有候选字", chars.isNotEmpty())
        for (c in chars) {
            assertTrue("被劈开的半个字：$c", !isSplit(c))
        }
    }

    @Test
    fun `所有音节的候选字都不该有半个字`() {
        val data = TestData.data
        var checked = 0
        for (syl in data.dictionary.chars.keys) {
            for (c in data.dictionary.charList(syl)) {
                assertTrue("$syl 里有被劈开的半个字：$c", !isSplit(c))
                checked++
            }
        }
        assertTrue("检查的字太少，说明字表没加载上：$checked", checked > 10000)
    }

    @Test
    fun `扩展区的字已经不在字表里了`() {
        // 字表生成阶段就把 U+20000 以上的字滤掉了：
        // 手机字体（Noto Sans CJK 只覆盖 BMP 和扩展 A）没有这些字形，
        // 画出来是一片空白——进候选栏就是「看不见的候选」。
        // 唯一的例外是 cei：它别的字都没有，滤掉整个音节就没了。
        val chars = engine.candidateCharList("yuan")
        assertTrue(
            "yuan 不该再有扩展区的字，实际：${chars.filter { it.length == 2 }}",
            chars.none { it.length == 2 && Character.isSurrogatePair(it[0], it[1]) },
        )
        // 例外只有 cei 一个
        val cei = engine.candidateCharList("cei")
        assertTrue("cei 的字应该还在（不然这个音节就没了）", cei.isNotEmpty())
    }

    @Test
    fun `字表里没有繁体字`() {
        // 用户要求「不要繁体文字，只要简体」。
        // 繁体字在大字表（rime-ice 41448）里有一大把，
        // 靠 tools/make_t2s.ps1 生成的繁简表在**生成字表时**就滤掉了。
        val cases = listOf(
            "long" to "龍", "xue" to "學", "yuan" to "鴛", "gu" to "鶻",
            "ai" to "愛", "men" to "門", "zhe" to "這", "shuo" to "說",
            "xue" to "覺", "guo" to "國",
        )
        for ((syllable, trad) in cases) {
            val chars = engine.candidateCharList(syllable)
            assertTrue(
                "$syllable 的候选里不该有繁体「$trad」，实际：${chars.take(20)}",
                !chars.contains(trad),
            )
        }
    }

    @Test
    fun `常用简体字还在字表里`() {
        // 反过来守着：滤繁体不能把简体也误伤
        val cases = listOf(
            "long" to "龙", "xue" to "学", "yuan" to "鸳", "gu" to "鹄",
            "ai" to "爱", "men" to "门", "zhe" to "这", "shuo" to "说",
            "guo" to "国",
        )
        for ((syllable, simple) in cases) {
            val chars = engine.candidateCharList(syllable)
            assertTrue(
                "$syllable 的候选里应该有简体「$simple」",
                chars.contains(simple),
            )
        }
    }

    @Test
    fun `候选栏里的单字候选也不会裂`() {
        val session = InputSession(engine)
        // yuan = y + r(uan)
        session.pressKey("y")
        session.pressKey("r")
        val cands = Candidates.build(engine, session)
        val chars = cands.filter { it.kind == CandidateKind.CHAR }
        assertTrue("应该有单字候选", chars.isNotEmpty())
        for (c in chars) {
            assertTrue("候选里混进了半个字：${c.text}", !isSplit(c.text))
        }
    }
}
