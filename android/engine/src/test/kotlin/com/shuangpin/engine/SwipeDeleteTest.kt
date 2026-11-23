package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 退格键上滑「逐格退」的测试。
 *
 * 退格键上滑原来是一把梭——把本会话输入的内容全删掉。
 * 用户想退的往往只是最后那么一两个字（打错了想重打），
 * 一步全删等于把已经打对的字也丢了。
 *
 * 现在的规则是**一次退一格，退的是「声母/韵母」**：
 * "nihao" → 退成 "nih"（删掉 hao 的韵母）→ 退成 "ni" → 退成 "n" → 退成空
 * → 再退就是删已上屏的文字（那是 App 层的事）。
 *
 * 要一次删光整轮拼音，用「**上滑后停顿**」（见 [InputSession.clearInput]）。
 */
class SwipeDeleteTest {

    companion object {
        private lateinit var engine: PinyinEngine

        @BeforeClass
        @JvmStatic
        fun load() {
            engine = TestData.engine()
        }
    }

    /** 按音节拼出一个会话（不写死键位，换方案也不会失效）。 */
    private fun session(vararg syllables: String): InputSession {
        val s = InputSession(engine)
        for (syl in syllables) {
            val code = engine.encode(syl)
            assertTrue("音节 $syl 编码不出来：$code", code.length == 2)
            s.pressKey(code[0].toString())
            s.pressKey(code[1].toString())
        }
        return s
    }

    @Test
    fun `上滑一次退掉最后一个音节的韵母`() {
        val s = session("ni", "hao")
        assertEquals("nihao", s.pinyin)

        val first = s.swipeDelete()
        assertNotNull("第一次上滑应该删掉东西", first)
        // 撤销要还原**整个**音节
        assertEquals("删的是 hao", "hao", first!!.syllable?.syllable)
        // hao 退回半截音节 "h"
        assertEquals("剩 nih", "nih", s.composingText)
        assertEquals(InputStage.FINAL, s.stage)
    }

    @Test
    fun `半截音节再退一次就没了`() {
        val s = session("ni", "hao")
        s.swipeDelete()          // → "nih"
        val second = s.swipeDelete()
        assertNotNull("第二次上滑应该还能删", second)
        assertNull("这次删的是半截音节", second!!.syllable)
        assertEquals("剩 ni", "ni", s.composingText)
    }

    @Test
    fun `没得退了返回空`() {
        val s = session("ni", "hao")
        s.swipeDelete()          // → "nih"
        s.swipeDelete()          // → "ni"
        s.swipeDelete()          // → "n"
        s.swipeDelete()          // → ""
        // 没有输入可退了——上层这时该去删已上屏的文字
        assertNull("没得退了应该返回 null", s.swipeDelete())
    }

    @Test
    fun `撤销能把音节放回去`() {
        val s = session("ni", "hao")
        val removal = s.swipeDelete()!!
        s.undoSwipe(removal)
        assertEquals("撤销后拼音要还原", "nihao", s.pinyin)
        assertEquals("预编辑区也要还原", "nihao", s.composingText)
        // 放回去之后还能再退
        assertNotNull(s.swipeDelete())
    }

    @Test
    fun `半截音节先被删掉`() {
        val s = session("ni")
        // 再敲一个声母键，进入「韵母位」——这半截还没成型
        val code = engine.encode("hao")
        s.pressKey(code[0].toString())
        assertEquals(InputStage.FINAL, s.stage)
        assertEquals("nih", s.composingText)

        val removal = s.swipeDelete()!!
        assertNull("这次删的是半截音节，不是完整音节", removal.syllable)
        assertEquals("半截的声母键要对", code[0].toString(), removal.partialFirstKey)
        assertEquals("退回 ni", "ni", s.pinyin)
        assertEquals("预编辑区也要跟着退", "ni", s.composingText)
    }

    @Test
    fun `半截音节也能撤销`() {
        val s = session("ni")
        val code = engine.encode("hao")
        s.pressKey(code[0].toString())
        val removal = s.swipeDelete()!!
        s.undoSwipe(removal)
        assertEquals("撤销后要回到韵母位", InputStage.FINAL, s.stage)
        assertEquals("半截音节要还原", code[0].toString(), s.firstKey)
        assertEquals("预编辑区也要还原", "nih", s.composingText)
    }

    @Test
    fun `退完还能继续正常输入`() {
        val s = session("ni", "hao")
        s.swipeDelete()
        s.swipeDelete()
        assertEquals("ni", s.pinyin)
        // 退完以后接着敲，应该开一个新音节而不是接在旧的后面
        val code = engine.encode("hao")
        s.pressKey(code[0].toString())
        s.pressKey(code[1].toString())
        assertEquals("nihao", s.pinyin)
    }

    // ------------------------------------------------------------------
    // 「上滑停顿」= 删掉整轮拼音
    // ------------------------------------------------------------------

    @Test
    fun `整轮清空`() {
        val s = session("ni", "hao")
        s.clearInput()
        assertEquals("", s.pinyin)
        assertEquals("", s.composingText)
        assertEquals(InputStage.INITIAL, s.stage)
    }

    @Test
    fun `整轮清空后能整个恢复`() {
        val s = session("ni", "hao")
        val snapshot = s.snapshot()
        s.clearInput()
        assertEquals("清空了", "", s.pinyin)

        s.restore(snapshot)
        assertEquals("撤销后拼音要还原", "nihao", s.pinyin)
        assertEquals("预编辑区也要还原", "nihao", s.composingText)
        assertEquals(InputStage.SYLLABLE, s.stage)
    }

    @Test
    fun `快照能带上半截音节`() {
        val s = session("ni")
        val code = engine.encode("hao")
        s.pressKey(code[0].toString())       // 半截音节 "h"
        val snapshot = s.snapshot()
        s.clearInput()
        s.restore(snapshot)
        assertEquals("半截音节要还原", "nih", s.composingText)
        assertEquals(InputStage.FINAL, s.stage)
    }
}
