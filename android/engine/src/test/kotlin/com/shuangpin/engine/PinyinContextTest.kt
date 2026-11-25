package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * 上下文选词、提交前缀、组合学习的测试。
 *
 * 这几条对应用户反馈里的：
 * - 「在候选区选择前面的文字，后面的文字就会消失」→ [commitPrefix 留住剩下的音节]
 * - 「候选词第一个显示『过』，停很久才变成『果』」→ [上下文选词]
 * - 「输入『天道』再输入『符文』，以后输 tiandaofuwen 显示『天道符文』」→ [组合学习]
 * - 「大篇幅打文档，错别字非常多」→ [长句里的逐词纠错]
 */
class PinyinContextTest {

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

    /** 打一串音节（自动编成双拼键位）。 */
    private fun typeSyllables(vararg syllables: String) {
        for (syl in syllables) {
            val code = engine.encode(syl)
            for (ch in code) session.pressKey(ch.toString())
        }
    }

    // ---------------------------------------------------------------
    // 提交前缀：选前面的词，后面的拼音要留住
    // ---------------------------------------------------------------

    @Test
    fun `提交前缀之后剩下的音节留在会话里`() {
        typeSyllables("cang", "tian", "zhi", "jian")
        assertEquals("四个音节", 4, session.pending.size)
        session.commitPrefix("苍天", 2)
        assertEquals("剩下的两个音节还在", 2, session.pending.size)
        assertEquals("预编辑区只剩后半截", "zhijian", session.composingText)
    }

    @Test
    fun `提交前缀之后还能接着上屏剩下的`() {
        typeSyllables("cang", "tian", "zhi", "jian")
        session.commitPrefix("苍天", 2)
        val rest = session.commitTop()
        assertEquals("剩下那半截照样能上屏", "之间", rest)
    }

    @Test
    fun `提交全部时行为跟以前一样`() {
        typeSyllables("cang", "tian")
        session.commitPrefix("苍天", 2)
        assertTrue("音节都提交完了，会话应该空了", session.pending.isEmpty())
        assertTrue("预编辑区也该空了", session.composingText.isEmpty())
    }

    @Test
    fun `分词单元带上了音节跨度`() {
        val spans = engine.segmentUnitSpans(listOf("cang", "tian", "zhi", "jian"))
        assertTrue("应该分出词", spans.isNotEmpty())
        val first = spans.first()
        assertEquals("第一个词的拼音", "cangtian", first.pinyin)
        assertEquals("第一个词覆盖两个音节", 2, first.syllables.size)
        assertEquals("第一个词从第 0 个音节开始", 0, first.start)
    }

    // ---------------------------------------------------------------
    // 上下文选词：结 + guo → 果
    // ---------------------------------------------------------------

    @Test
    fun `上一块加当前拼音在词库里是一个词时该字排第一`() {
        typeSyllables("guo")
        val plain = Candidates.build(engine, session)
        assertEquals("没有上下文时第一条是词频最高的「过」", "过", plain.first().text)

        val withCtx = Candidates.build(
            engine = engine,
            session = session,
            contextPinyin = "jie",
            contextText = "结",
        )
        assertEquals("有了上下文就该是「果」", "果", withCtx.first().text)
        assertTrue("上下文选出来的候选要带标记", withCtx.first().context)
    }

    @Test
    fun `上下文选出来的候选不参与端侧重排`() {
        // 标记本身就够了：App 层靠它把这条从重排池子里摘出去
        typeSyllables("guo")
        val items = Candidates.build(
            engine = engine,
            session = session,
            contextPinyin = "jie",
            contextText = "结",
        )
        val hit = items.first { it.context }
        assertEquals("果", hit.text)
    }

    // ---------------------------------------------------------------
    // 长句里的逐词纠错
    // ---------------------------------------------------------------

    @Test
    fun `长句里错的一个词能纠出来`() {
        // 「今天」+「仙人抚我顶」（ren 打成 ri）
        // 整串不是词库里的一个词 → 老规则压根不触发
        val fixed = Corrections.forSentence(
            engine,
            listOf("jin", "tian", "xian", "ri", "fu", "wo", "ding"),
        )
        assertTrue("长句里也该纠得出来，实际 $fixed", fixed.isNotEmpty())
        assertEquals("纠错后是「今天仙人抚我顶」", "今天仙人抚我顶", fixed.first())
    }

    @Test
    fun `打对了的长句不该冒出纠错候选`() {
        val fixed = Corrections.forSentence(
            engine,
            listOf("jin", "tian", "tian", "qi", "zhen", "hao"),
        )
        assertTrue("打对了不该纠，实际 $fixed", fixed.isEmpty())
    }

    @Test
    fun `长句纠错的耗时在可接受范围`() {
        // 这个计算放在后台跑，但也不能太慢（用户还在等结果）
        val syls = listOf(
            "jin", "tian", "tian", "qi", "zhen", "hao",
            "wo", "men", "qu", "chi", "fan", "le",
        )
        val t0 = System.nanoTime()
        Corrections.forSentence(engine, syls)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("长句纠错不该超过 200 ms，实际 ${ms}ms", ms < 200)
    }

    // ---------------------------------------------------------------
    // 组合学习：天道 + 符文 → 天道符文
    // ---------------------------------------------------------------

    @Test
    fun `连着打的两块会学成组合`() {
        val combo = Learning.combination(
            dictionary = engine.dictionary,
            prevPinyin = "tiandao",
            prevText = "天道",
            pinyin = "fuwen",
            text = "符文",
        )
        assertTrue("该学成组合，实际 $combo", combo != null)
        assertEquals("tiandaofuwen", combo!!.first)
        assertEquals("天道符文", combo.second)
    }

    @Test
    fun `词库里已有的组合不学`() {
        // 「我想」词库里就有 → 学它没意义（还改了词频）
        val combo = Learning.combination(
            dictionary = engine.dictionary,
            prevPinyin = "wo",
            prevText = "我",
            pinyin = "xiang",
            text = "想",
        )
        assertTrue("词库里有的不该学，实际 $combo", combo == null)
    }

    @Test
    fun `没有上一块就没有组合`() {
        val combo = Learning.combination(
            dictionary = engine.dictionary,
            prevPinyin = null,
            prevText = null,
            pinyin = "fuwen",
            text = "符文",
        )
        assertTrue(combo == null)
    }

    @Test
    fun `单字跟谁连着都不学`() {
        // 「的」+「苍天」：单字跟谁连着都是巧合，学进去只会污染词库
        val combo = Learning.combination(
            dictionary = engine.dictionary,
            prevPinyin = "de",
            prevText = "的",
            pinyin = "cangtian",
            text = "苍天",
        )
        assertTrue("单字不该学组合，实际 $combo", combo == null)
    }

    @Test
    fun `学到的组合排第一`() {
        // 模拟：先打「天道」再打「符文」，学到了「tiandaofuwen → 天道符文」
        val dict = FakeUserDict()
        val combo = Learning.combination(
            dictionary = engine.dictionary,
            prevPinyin = "tiandao",
            prevText = "天道",
            pinyin = "fuwen",
            text = "符文",
        )!!
        dict.learn(combo.first, combo.second)

        val s = InputSession(engine)
        for (syl in listOf("tian", "dao", "fu", "wen")) {
            for (ch in engine.encode(syl)) s.pressKey(ch.toString())
        }
        val cands = Candidates.build(engine, s, userDict = dict)
        assertEquals("学过的组合排第一", "天道符文", cands.first().text)
        assertEquals("而且是用户词", CandidateKind.USER, cands.first().kind)
    }

    /** 只在内存里的用户词库（测试用，不落盘）。 */
    private class FakeUserDict : UserDict {
        private val entries = LinkedHashMap<String, UserEntry>()

        override fun learn(pinyin: String, word: String) {
            if (pinyin.isEmpty() || word.isEmpty() || word.length < 2) return
            val key = "$pinyin$word"
            val old = entries[key]
            entries[key] = UserEntry(pinyin, word, (old?.count ?: 0) + 1, System.currentTimeMillis())
        }

        override fun lookup(pinyin: String): List<UserEntry> =
            entries.values.filter { it.pinyin == pinyin }
                .sortedWith(compareByDescending<UserEntry> { it.count }.thenByDescending { it.lastUsed })

        override fun all(): List<UserEntry> = entries.values.toList()

        override val size: Int get() = entries.size

        override fun clear() = entries.clear()
    }
}
