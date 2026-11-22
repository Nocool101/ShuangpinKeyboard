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
 * 现在的规则是**一次退一格，从后往前退**：
 * "nihao" → 退成 "ni" → 退成空 → 再退就是删已上屏的文字（那是 App 层的事）。
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
    fun `上滑一次退一个音节`() {
        val s = session("ni", "hao")
        assertEquals("nihao", s.pinyin)

        val first = s.swipeDelete()
        assertNotNull("第一次上滑应该删掉东西", first)
        assertEquals("先删最后输入的 hao", "hao", first!!.syllable?.syllable)
        assertEquals("剩 ni", "ni", s.pinyin)

        val second = s.swipeDelete()
        assertNotNull("第二次上滑应该还能删", second)
        assertEquals("再删 ni", "ni", second!!.syllable?.syllable)
        assertEquals("剩空", "", s.pinyin)
    }

    @Test
    fun `没得退了返回空`() {
        val s = session("ni", "hao")
        s.swipeDelete()
        s.swipeDelete()
        // 没有输入可退了——上层这时该去删已上屏的文字
        assertNull("没得退了应该返回 null", s.swipeDelete())
    }

    @Test
    fun `撤销能把音节放回去`() {
        val s = session("ni", "hao")
        val removal = s.swipeDelete()!!
        s.undoSwipe(removal)
        assertEquals("撤销后拼音要还原", "nihao", s.pinyin)
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
        assertEquals("ni", s.pinyin)
        // 退完以后接着敲，应该开一个新音节而不是接在旧的后面
        val code = engine.encode("hao")
        s.pressKey(code[0].toString())
        s.pressKey(code[1].toString())
        assertEquals("nihao", s.pinyin)
    }
}
