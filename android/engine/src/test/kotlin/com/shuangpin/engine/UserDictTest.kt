package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * **用户自学习词库**的测试。
 *
 * 守的是用户提的那条需求：
 * 「这次打了炼气士，下次再打炼气士这三个字要排在第一个」。
 *
 * 也就是说：
 * - 打过一次就要能进候选栏，而且**排在第一**
 * - 词库里根本没有的词（「炼气士」）也要能进
 * - 打得多的排前面，次数一样时最近打的排前面
 */
class UserDictTest {

    private lateinit var file: File

    @Before
    fun newFile() {
        file = File.createTempFile("userdict", ".tsv")
        file.delete()
    }

    private fun dict(max: Int = 100, clock: () -> Long = { 1_000L }) =
        FileUserDict(file, max, clock)

    // ------------------------------------------------------------------
    // 记与查
    // ------------------------------------------------------------------

    @Test
    fun `学过的词能查出来`() {
        val d = dict()
        d.learn("lianqishi", "炼气士")
        val hit = d.lookup("lianqishi")
        assertEquals(1, hit.size)
        assertEquals("炼气士", hit[0].word)
        assertEquals(1, hit[0].count)
    }

    @Test
    fun `没学过的拼音查不到`() {
        val d = dict()
        d.learn("lianqishi", "炼气士")
        assertTrue(d.lookup("lianqishi2").isEmpty())
    }

    @Test
    fun `单字不学`() {
        // 单字字表里本来就有，记它只是浪费位置
        val d = dict()
        d.learn("shi", "是")
        assertTrue(d.lookup("shi").isEmpty())
        assertEquals(0, d.size)
    }

    @Test
    fun `空串不学`() {
        val d = dict()
        d.learn("", "炼气士")
        d.learn("lianqishi", "")
        assertEquals(0, d.size)
    }

    @Test
    fun `同一个词多打几次计数会涨`() {
        val d = dict()
        d.learn("lianqishi", "炼气士")
        d.learn("lianqishi", "炼气士")
        d.learn("lianqishi", "炼气士")
        assertEquals(3, d.lookup("lianqishi")[0].count)
        assertEquals(1, d.size)
    }

    // ------------------------------------------------------------------
    // 排序
    // ------------------------------------------------------------------

    @Test
    fun `打得多的排前面`() {
        var t = 0L
        val d = dict(clock = { ++t })
        d.learn("lianqishi", "炼气士")
        d.learn("lianqishi", "炼气是")
        d.learn("lianqishi", "炼气是")
        val words = d.lookup("lianqishi").map { it.word }
        assertEquals(listOf("炼气是", "炼气士"), words)
    }

    @Test
    fun `次数一样时最近打的排前面`() {
        var t = 0L
        val d = dict(clock = { ++t })
        // 先打「炼气是」，后打「炼气士」——次数一样，后者必须在前
        d.learn("lianqishi", "炼气是")
        d.learn("lianqishi", "炼气士")
        val words = d.lookup("lianqishi").map { it.word }
        assertEquals(listOf("炼气士", "炼气是"), words)
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    @Test
    fun `落盘之后重新打开还在`() {
        val d = dict()
        d.learn("lianqishi", "炼气士")
        d.learn("lianqi", "炼气")
        d.flush()

        val again = dict()
        assertEquals(2, again.size)
        assertEquals("炼气士", again.lookup("lianqishi")[0].word)
        assertEquals("炼气", again.lookup("lianqi")[0].word)
    }

    @Test
    fun `落盘文件里没有乱码`() {
        val d = dict()
        d.learn("lianqishi", "炼气士")
        d.flush()
        val text = file.readText()
        assertTrue("文件里应该有拼音", text.contains("lianqishi"))
        assertTrue("文件里应该有词", text.contains("炼气士"))
    }

    @Test
    fun `清空后就没了`() {
        val d = dict()
        d.learn("lianqishi", "炼气士")
        d.clear()
        assertEquals(0, d.size)
        assertTrue(d.lookup("lianqishi").isEmpty())
    }

    // ------------------------------------------------------------------
    // 淘汰
    // ------------------------------------------------------------------

    @Test
    fun `超过上限时淘汰打得最少最久没用的`() {
        var t = 0L
        val d = dict(max = 3, clock = { ++t })
        d.learn("a1", "甲一")
        d.learn("a2", "甲二")
        d.learn("a3", "甲三")
        // 「甲一」多打一次，它就该活下来
        d.learn("a1", "甲一")
        d.learn("a4", "甲四")
        assertEquals(3, d.size)
        assertTrue("打得多的应该留下", d.lookup("a1").isNotEmpty())
        assertTrue("最久没用的应该被淘汰", d.lookup("a2").isEmpty())
    }

    // ------------------------------------------------------------------
    // 进候选栏
    // ------------------------------------------------------------------

    @Test
    fun `学过的词排在候选栏第一位`() {
        val d = dict()
        d.learn("lianqishi", "炼气士")
        val session = InputSession(engine)
        typeLianQiShi(session)
        assertEquals("lianqishi", session.pinyin)

        val items = Candidates.build(engine, session, userDict = d)
        assertNotNull(items)
        assertEquals("炼气士", items[0].text)
        assertEquals(CandidateKind.USER, items[0].kind)
    }

    @Test
    fun `没学过的词不会污染候选栏`() {
        val d = dict()
        val session = InputSession(engine)
        typeLianQiShi(session)
        val items = Candidates.build(engine, session, userDict = d)
        assertTrue(items.none { it.kind == CandidateKind.USER })
    }

    @Test
    fun `学过的词盖住引擎的默认切分`() {
        val d = dict()
        // 引擎默认会把 lianqishi 切成「炼气 + 是」
        d.learn("lianqishi", "炼气士")
        val session = InputSession(engine)
        typeLianQiShi(session)
        val items = Candidates.build(engine, session, userDict = d)
        val userIdx = items.indexOfFirst { it.text == "炼气士" }
        val sentenceIdx = items.indexOfFirst { it.kind == CandidateKind.SENTENCE }
        assertEquals(0, userIdx)
        assertTrue("用户的词应该在整句候选前面", userIdx < sentenceIdx)
    }

    companion object {
        private lateinit var engine: PinyinEngine

        @BeforeClass
        @JvmStatic
        fun load() {
            engine = TestData.engine()
        }
    }

    /** 在小鹤双拼下打出「炼气士」：l-m / q-i / sh(u)-i。 */
    private fun typeLianQiShi(session: InputSession) {
        for (k in listOf("l", "m", "q", "i", "u", "i")) {
            session.pressKey(k)
        }
    }
}
