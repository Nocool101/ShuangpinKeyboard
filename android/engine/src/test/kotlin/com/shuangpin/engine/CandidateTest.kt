package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * 候选栏装配的测试。
 *
 * 候选的**顺序**就是用户看到的优先级，装配逻辑放在引擎层就是为了它可测、
 * 可复用——端侧模型评估用的候选池也是这份（`Candidates.build`）。
 *
 * 顺序：**整句 → 整串命中 → 分出来的词 → 长词扩展 → 单字**。
 */
class CandidateTest {

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

    private lateinit var session: InputSession

    @Before
    fun newSession() {
        session = InputSession(engine)
    }

    /** 打一串双拼键位，得到一个会话。 */
    private fun type(code: String) {
        for (ch in code) session.pressKey(ch.toString())
    }

    /** 打一串音节（自动编成双拼键位）——纠错用例都是按音节写的。 */
    private fun typeSyllables(vararg syllables: String) {
        for (syl in syllables) {
            val code = engine.encode(syl)
            for (ch in code) session.pressKey(ch.toString())
        }
    }

    @Test
    fun `空会话没有候选`() {
        assertTrue(Candidates.build(engine, session).isEmpty())
    }

    @Test
    fun `整句排在最前`() {
        type("nihc")   // ni + hao = 你好
        val cands = Candidates.build(engine, session)
        assertTrue("应该有候选", cands.isNotEmpty())
        assertEquals("整句候选排第一", CandidateKind.SENTENCE, cands.first().kind)
        assertEquals("整句就是「你好」", "你好", cands.first().text)
    }

    @Test
    fun `单字按音节分组排且组号不倒退`() {
        type("nihc")
        val cands = Candidates.build(engine, session)
        val chars = cands.filter { it.kind == CandidateKind.CHAR }
        assertTrue("应该有单字候选", chars.isNotEmpty())
        // 单字候选现在**跟着分组走**：一组一段，从前往后排
        // （用户反馈「滑动选词要从前面选起」）。
        // 组号不能倒退——倒退了就说明又翻回前面那组了。
        val groups = chars.map { it.group }
        assertTrue("单字候选应该属于某个分组：$groups", groups.all { it >= 0 })
        for (i in 1 until groups.size) {
            assertTrue(
                "分组号不能倒退：$groups",
                groups[i] >= groups[i - 1],
            )
        }
    }

    @Test
    fun `分组候选紧跟在整句后面`() {
        type("nihc")
        val cands = Candidates.build(engine, session)
        val sentenceIdx = cands.indexOfFirst { it.kind == CandidateKind.SENTENCE }
        val groupIdx = cands.indexOfFirst { it.kind == CandidateKind.GROUP }
        assertTrue("应该有分组候选", groupIdx >= 0)
        assertTrue(
            "分组候选应紧跟在整句后面：${cands.map { it.kind }}",
            groupIdx > sentenceIdx,
        )
    }

    @Test
    fun `分组里词排在字前面`() {
        type("nihc")
        val cands = Candidates.build(engine, session)
        val groupIdx = cands.indexOfFirst { it.kind == CandidateKind.GROUP }
        val charIdx = cands.indexOfFirst { it.kind == CandidateKind.CHAR }
        assertTrue("应该有分组候选", groupIdx >= 0)
        assertTrue(
            "组内词应排在字前面：${cands.map { it.kind }}",
            groupIdx < charIdx,
        )
    }

    @Test
    fun `长词扩展排在分组候选之后`() {
        type("nihc")
        val cands = Candidates.build(engine, session)
        val extIdx = cands.indexOfFirst { it.kind == CandidateKind.EXTENSION }
        val groupIdx = cands.indexOfFirst { it.kind == CandidateKind.GROUP }
        if (extIdx >= 0) {
            assertTrue("扩展候选应排在分组候选之后", extIdx > groupIdx)
            assertTrue("nihao 应能扩展出长词", cands[extIdx].text.startsWith("你好"))
        }
    }

    @Test
    fun `候选不重复`() {
        type("nihc")
        val cands = Candidates.build(engine, session)
        assertEquals("候选不应重复", cands.size, cands.map { it.text }.distinct().size)
    }

    @Test
    fun `扩展数量有上限`() {
        type("nihc")
        val cands = Candidates.build(engine, session)
        val ext = cands.count { it.kind == CandidateKind.EXTENSION }
        assertTrue("扩展不该超过上限，实际 $ext", ext <= Candidates.MAX_EXTENSION)
    }

    @Test
    fun `单字数量有上限`() {
        type("nihc")
        val cands = Candidates.build(engine, session)
        val chars = cands.count { it.kind == CandidateKind.CHAR }
        assertTrue("单字不该超过上限，实际 $chars", chars <= Candidates.MAX_CHAR)
    }

    @Test
    fun `单字限流后常用字仍在前面`() {
        type("nihc")
        val cands = Candidates.build(engine, session)
        val chars = cands.filter { it.kind == CandidateKind.CHAR }.map { it.text }
        assertTrue("单字候选不该为空", chars.isNotEmpty())
        // 第一个单字应该是**第一个音节**最高频的字
        // （分组从前往后排，不再是「最后一个音节的字优先」）
        val first = engine.candidateCharList("ni").first().toString()
        assertEquals("第一个单字应是最高频的字", first, chars.first())
    }

    @Test
    fun `备选整句排在主整句之后且等长`() {
        // woxiangchifan = 我想吃饭 / 我想吃翻，两条等长整句
        type("woxl iifj".replace(" ", ""))
        val cands = Candidates.build(engine, session)
        assertEquals("主整句排第一", CandidateKind.SENTENCE, cands.first().kind)
        val alts = cands.filter { it.kind == CandidateKind.SENTENCE_ALT }
        assertTrue("应该给得出备选整句", alts.isNotEmpty())
        for (alt in alts) {
            assertEquals("备选整句应与主整句等长", cands.first().text.length, alt.text.length)
        }
        // 备选不能和主候选重复
        assertEquals("备选不该与主候选重复", cands.size, cands.map { it.text }.distinct().size)
    }

    // ---------------------------------------------------------------
    // 自动纠错：条件置顶
    // ---------------------------------------------------------------

    @Test
    fun `错打成词库里没有的东西时纠错候选排第一`() {
        // 「仙人抚我顶」打成了「xianrifuwoding」——
        // 错打的那串在词库里根本没有命中，纠错候选理应顶到最前。
        typeSyllables("xian", "ri", "fu", "wo", "ding")
        val cands = Candidates.build(engine, session, includeCorrections = true)
        assertTrue("应该有候选", cands.isNotEmpty())
        assertEquals("纠错候选该排第一", CandidateKind.CORRECTION, cands.first().kind)
        assertEquals("第一条就是「仙人抚我顶」", "仙人抚我顶", cands.first().text)
    }

    @Test
    fun `精确命中是常用词时纠错候选不顶上去`() {
        // 「丝线」(57 分) 是常用词 → 用户多半就是故意打的 → 精确命中照旧在前
        // （57 ≥ HOIST_MAX_SCORE=50，差 7 分，门槛改动也不会翻盘）
        typeSyllables("si", "xian")
        val cands = Candidates.build(engine, session, includeCorrections = true)
        assertEquals("精确命中该排第一", "丝线", cands.first().text)
        assertTrue("精确命中要带 exact 标记", cands.first().exact)
        val corr = cands.firstOrNull { it.kind == CandidateKind.CORRECTION }
        assertTrue("纠错候选也该在候选栏里", corr != null)
        assertEquals("纠错候选是「实现」", "实现", corr!!.text)
        assertTrue("纠错候选排在精确命中后面", cands.indexOf(corr) > 0)
    }

    @Test
    fun `精确命中是冷门词时纠错候选顶上去`() {
        // 「可耐」(27 分) 是冷门词 → 更可能是「可来」打错出来的
        typeSyllables("ke", "nai")
        val cands = Candidates.build(engine, session, includeCorrections = true)
        assertEquals("纠错候选该顶到第一", CandidateKind.CORRECTION, cands.first().kind)
        assertEquals("第一条就是「可来」", "可来", cands.first().text)
        // 置顶只是换次序，**不删候选**：原精确命中还在栏里
        assertTrue(
            "原精确命中不该被丢掉，实际 ${cands.map { it.text }}",
            cands.any { it.text == "可耐" },
        )
    }

    // ---------------------------------------------------------------
    // 云端消歧：把候选提到前面
    // ---------------------------------------------------------------

    @Test
    fun `优先词被提到最前面`() {
        val items = listOf(
            Candidate("甲", CandidateKind.WORD),
            Candidate("乙", CandidateKind.WORD),
            Candidate("丙", CandidateKind.WORD),
        )
        val out = Candidates.promote(listOf("丙", "甲"), items)
        assertEquals("丙", out[0].text)
        assertEquals("甲", out[1].text)
        assertEquals("乙", out[2].text)
    }

    @Test
    fun `池子外的词不进候选`() {
        val items = listOf(
            Candidate("甲", CandidateKind.WORD),
            Candidate("乙", CandidateKind.WORD),
        )
        // 云端要是发了池子外的词，直接丢掉，绝不能塞进候选栏
        val out = Candidates.promote(listOf("丙", "乙"), items)
        assertEquals("不该出现池子外的词", 2, out.size)
        assertEquals("乙", out[0].text)
        assertEquals("甲", out[1].text)
    }

    @Test
    fun `被提升的候选标上标记且类别不变`() {
        val items = listOf(
            Candidate("甲", CandidateKind.WORD),
            Candidate("乙", CandidateKind.CHAR),
        )
        val out = Candidates.promote(listOf("乙"), items)
        assertTrue("被提升的应该有标记", out.first().promoted)
        assertEquals("类别不能变", CandidateKind.CHAR, out.first().kind)
        assertTrue("没被提升的没有标记", !out[1].promoted)
    }

    @Test
    fun `没有优先词时原样返回`() {
        val items = listOf(Candidate("甲", CandidateKind.WORD))
        assertEquals(items, Candidates.promote(emptyList(), items))
        assertEquals(items, Candidates.promote(listOf("不存在"), items))
    }
}
