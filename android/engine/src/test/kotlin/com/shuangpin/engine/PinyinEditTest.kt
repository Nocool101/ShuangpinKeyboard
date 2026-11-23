package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * **拼音打错了，点一下就能改**——会话的中途编辑。
 *
 * 用户提的场景：
 * > 用户要打「仙人抚我顶」，拼音打成了 xianrifuwoding，
 * > 光标可以选在 ri 后，点击删除键删除 i，继续可以打 en，
 * > 候选区可以显示仙人抚我顶。
 *
 * 拆开就是三件事：
 * 1. **光标能停在拼音中间**（[InputSession.setCaret]）
 * 2. **退格删的是光标前面那个字**，不是最后一个字（[InputSession.deleteBeforeCaret]）
 * 3. **接着打的字插在光标处**，后面已经打好的音节跟在后头
 */
class PinyinEditTest {

    companion object {
        private lateinit var engine: PinyinEngine

        @BeforeClass
        @JvmStatic
        fun load() {
            engine = TestData.engine()
        }
    }

    private fun session() = InputSession(engine)

    /** 按一串小鹤编码。 */
    private fun InputSession.type(vararg keys: String) {
        for (k in keys) pressKey(k)
    }

    /** 「xian qi shi」= x-m / q-i / sh(u)-i。 */
    private fun InputSession.typeLianQiShi() = type("x", "m", "q", "i", "u", "i")

    /** 「xian ri fu wo ding」= x-m / r-i / f-u / w-o / d-k。 */
    private fun InputSession.typeLianRiFuWoDing() =
        type("x", "m", "r", "i", "f", "u", "w", "o", "d", "k")

    // ------------------------------------------------------------------
    // 光标
    // ------------------------------------------------------------------

    @Test
    fun `光标默认在预编辑区末尾`() {
        val s = session()
        s.typeLianQiShi()
        assertEquals("xianqishi", s.composingText)
        assertEquals(s.composingText.length, s.caret)
        assertEquals(s.pending.size, s.insertAt)
    }

    @Test
    fun `光标挪到中间后插入点跟着走`() {
        val s = session()
        s.typeLianRiFuWoDing()
        assertEquals("xianrifuwoding", s.composingText)
        // 光标点在 "xianri" 之后
        s.setCaret(6)
        assertEquals(6, s.caret)
        // 插入点在第 2 个音节之后（xian / ri | fu / wo / ding）
        assertEquals(2, s.insertAt)
    }

    @Test
    fun `光标挪到开头时插入点在最前`() {
        val s = session()
        s.typeLianQiShi()
        s.setCaret(0)
        assertEquals(0, s.caret)
        assertEquals(0, s.insertAt)
    }

    @Test
    fun `光标挪到中间不改变拼音串`() {
        val s = session()
        s.typeLianQiShi()
        s.setCaret(3)
        assertEquals("xianqishi", s.pinyin)
        assertEquals("xianqishi", s.composingText)
    }

    // ------------------------------------------------------------------
    // 退格：删光标前面那个字
    // ------------------------------------------------------------------

    @Test
    fun `退格删光标前面的字之后能接着打`() {
        // 用户要打「仙人抚我顶」，拼音打成了 xianrifuwoding
        val s = session()
        s.typeLianRiFuWoDing()
        assertEquals("xianrifuwoding", s.composingText)

        // 光标点在 "ri" 后面
        s.setCaret(6)
        // 删掉那个 i → "ri" 退回半截音节 "r"
        assertTrue(s.deleteBeforeCaret())
        assertEquals("xianrfuwoding", s.composingText)

        // 接着按 en 的键（小鹤里 en 在 f 上）→ "ren"
        s.type("f")
        assertEquals("xianrenfuwoding", s.composingText)
        assertEquals("xianrenfuwoding", s.pinyin)
    }

    @Test
    fun `改完之后候选里出现仙人抚我顶`() {
        val s = session()
        s.typeLianRiFuWoDing()
        s.setCaret(6)
        assertTrue(s.deleteBeforeCaret())
        s.type("f")
        assertEquals("xianrenfuwoding", s.pinyin)

        val items = Candidates.build(engine, s)
        assertTrue(
            "候选里应该有「仙人抚我顶」，实际是 ${items.take(5).map { it.text }}",
            items.any { it.text == "仙人抚我顶" },
        )
    }

    @Test
    fun `分词结果就是仙人抚我顶`() {
        // 整串在词库里有「仙人抚我顶」这一条 → 分词应该选它，
        // 而不是切成「现任 + 服 + 我 + 定」那堆常用词。
        val s = session()
        s.typeLianRiFuWoDing()
        s.setCaret(6)
        assertTrue(s.deleteBeforeCaret())
        s.type("f")
        assertEquals("仙人抚我顶", s.sentence)
    }

    @Test
    fun `整串精确命中排候选第一位`() {
        val s = session()
        s.typeLianRiFuWoDing()
        s.setCaret(6)
        assertTrue(s.deleteBeforeCaret())
        s.type("f")

        assertEquals("仙人抚我顶", engine.phraseFor(s.pinyin))
        val items = Candidates.build(engine, s)
        assertEquals(
            "精确命中应该排第一，实际 ${items.take(4).map { it.text }}",
            "仙人抚我顶",
            items[0].text,
        )
        assertTrue("精确命中要带 exact 标记", items[0].exact)
    }

    @Test
    fun `光标在开头时退格不删东西`() {
        val s = session()
        s.typeLianQiShi()
        s.setCaret(0)
        assertFalse(s.deleteBeforeCaret())
        assertEquals("xianqishi", s.composingText)
    }

    @Test
    fun `删音节中间的字会换成还成立的音节`() {
        // "xian" 里的 "a" 删掉 → "xin" 也是个音节（心），直接换
        val s = session()
        s.type("x", "m")
        assertEquals("xian", s.composingText)
        s.setCaret(3)          // "xia" 之后
        assertTrue(s.deleteBeforeCaret())
        assertEquals("xin", s.composingText)
    }

    @Test
    fun `删到只剩声母就退回半截音节`() {
        // "xian" 里的 "i" 删掉 → "xan" 不成立 → 只剩声母 "x" → 退回半截音节
        val s = session()
        s.type("x", "m")
        s.setCaret(2)          // "xi" 之后
        assertTrue(s.deleteBeforeCaret())
        assertEquals("x", s.composingText)
        assertEquals(InputStage.FINAL, s.stage)
        assertEquals("x", s.firstKey)
    }

    @Test
    fun `删整个音节的最后一个字会退回半截音节`() {
        val s = session()
        s.type("r", "i")       // "ri"
        assertEquals("ri", s.composingText)
        s.setCaret(2)
        assertTrue(s.deleteBeforeCaret())
        // 退回半截音节：只剩声母 r，等着用户按韵母键
        assertEquals("r", s.composingText)
        assertEquals(InputStage.FINAL, s.stage)
        assertEquals("r", s.firstKey)
        // 接着按 en 的键 → "ren"
        s.type("f")
        assertEquals("ren", s.composingText)
    }

    @Test
    fun `插在中间的音节后面跟原有音节`() {
        val s = session()
        s.typeLianQiShi()
        s.setCaret(4)          // "xian" 之后
        s.type("r", "f")       // 插一个 "ren" 在中间
        assertEquals("xianrenqishi", s.composingText)
        // 后面的音节没丢
        assertEquals(4, s.pending.size)
    }

    // ------------------------------------------------------------------
    // 跟老行为的关系
    // ------------------------------------------------------------------

    @Test
    fun `光标在末尾时退格还是按音节退`() {
        val s = session()
        s.typeLianQiShi()
        assertTrue(s.backspace())
        // 退到上一个音节的韵母位：声母部分留着（sh 显示成 "sh"，不是键位字母 "u"）
        assertEquals("xianqish", s.composingText)
        assertTrue(s.backspace())
        assertEquals("xianqi", s.composingText)
    }

    @Test
    fun `半截音节显示声母的拼音而不是键位字母`() {
        val s = session()
        s.type("u")            // sh 键
        assertEquals("sh", s.composingText)
    }

    @Test
    fun `上滑删除不受影响`() {
        val s = session()
        s.typeLianQiShi()
        val removal = s.swipeDelete()
        assertTrue(removal != null)
        // 上滑退一格 = 退掉最后一个音节的**韵母**（"shi" → 半截音节 "sh"）
        assertEquals("xianqish", s.composingText)
    }

    // ------------------------------------------------------------------
    // 删字不许「连坐」：删一个字符，最多只能丢它自己
    // ------------------------------------------------------------------

    @Test
    fun `删掉声母之后剩下的是韵母`() {
        // 「wei」= w + ei（小鹤 w-ei 的 ei 在 w 键上）
        val s = session()
        s.type("w", "w")
        assertEquals("wei", s.composingText)
        // 光标停在 "w" 后面 → 删掉声母
        s.setCaret(1)
        assertTrue(s.deleteBeforeCaret())
        // 「ei」自己就是个音节 → 剩下 "ei"，而不是把整个音节都吃掉
        assertEquals("ei", s.composingText)
    }

    @Test
    fun `删掉韵母的一半会退回半截音节`() {
        // 「ri」= r + i，删掉 "i" → 只剩声母 "r"，用户接着按 "f" 就是 "ren"
        val s = session()
        s.type("r", "i")
        s.setCaret(2)
        assertTrue(s.deleteBeforeCaret())
        assertEquals("r", s.composingText)
        assertEquals(InputStage.FINAL, s.stage)
        s.type("f")
        assertEquals("ren", s.composingText)
    }

    @Test
    fun `删掉韵母后光标停在声母后面`() {
        // 「fu」删掉 "u" → 退回半截音节 "f"
        // 光标必须停在 **f 的后面**——停在前面的话，下一次退格会越过 f
        // 直接删到 f 前头那个字上去（用户反馈「删除 u 后光标跑到 f 前面，
        // 再按删除就会删除 f 前面的 n」）。
        val s = session()
        s.type("f", "u")
        assertEquals("fu", s.composingText)
        s.setCaret(2)
        assertTrue(s.deleteBeforeCaret())
        assertEquals("f", s.composingText)
        assertEquals(1, s.caret)
        // 再删一次 → 整个半截音节 "f" 删掉（不是跳过去删 f 前面的字）
        assertTrue(s.deleteBeforeCaret())
        assertEquals("", s.composingText)
    }

    @Test
    fun `删字之后光标停在删掉的那个位置`() {
        val s = session()
        s.typeLianQiShi()
        // "xian" 里面（"xia" 之后）删一个
        s.setCaret(3)
        assertTrue(s.deleteBeforeCaret())
        // 删的是 "a" → "xin"，光标落在删掉的那个位置（"xi" 之后）
        assertEquals("xinqishi", s.composingText)
        assertEquals(2, s.caret)
    }

    @Test
    fun `删字之后接着打的字插在光标处`() {
        val s = session()
        s.typeLianRiFuWoDing()
        // 光标点在 "ri" 之后，删掉 "i"，再补一个 "en"
        s.setCaret(6)
        assertTrue(s.deleteBeforeCaret())
        s.type("f")
        assertEquals("xianrenfuwoding", s.composingText)
        assertEquals("xianrenfuwoding", s.pinyin)
    }

    @Test
    fun `半截音节的偏移按显示出来的文字算`() {
        // 「sh」在小鹤是 u 键 → 预编辑区显示 "sh"（两个字符），不是 "u"
        val s = session()
        s.type("u")
        assertEquals("sh", s.composingText)
        assertEquals(2, s.caret)
        // 接着补一个韵母键 → "shi"
        s.type("i")
        assertEquals("shi", s.composingText)
        assertEquals(3, s.caret)
    }
}
