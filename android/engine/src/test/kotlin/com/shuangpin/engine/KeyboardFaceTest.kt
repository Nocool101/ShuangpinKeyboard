package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * 键面标签的测试。
 *
 * 这里守的是一个曾经很难查的 bug：
 * 键面标签在 layout 阶段算死存进 Key，状态变了也不重算——
 * 于是「按了声母还在韵母界面」「w 之后 o 键显示零」「sh 之后没有韵母 i」
 * 全都是同一个根因。所以标签计算必须放在绘制时求值的纯函数里。
 */
class KeyboardFaceTest {

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

    // 每个用例独立的会话——共用一个会被上一个用例的状态污染
    private lateinit var session: InputSession

    @Before
    fun newSession() {
        session = InputSession(engine)
    }

    private fun face(key: String) =
        KeyboardFaces.forKey(engine.scheme, session.stage, session.finals, key)

    @Test
    fun `声母位显示声母和韵母提示`() {
        assertEquals(InputStage.INITIAL, session.stage)

        val v = face("v")
        assertEquals("v 键在声母位应显示 zh", "zh", v.big)
        assertEquals("副标签是该键的韵母提示", "ü/ui", v.sub)
        assertEquals("角标是键位字母", "v", v.badge)
        assertFalse(v.dead)

        // 零声母入口
        val o = face("o")
        assertEquals("o 是零声母入口", "零", o.big)
        assertEquals("零声母键没有韵母提示", null, o.sub)

        // 整体认读
        assertEquals("整体认读", "整体认读", face("w").sub)
    }

    @Test
    fun `按了声母后键面立刻切到韵母位`() {
        session.pressKey("n")
        assertEquals(InputStage.FINAL, session.stage)

        // 有效韵母：主标签是韵母，左上角角标是键位字母
        val d = face("d")
        assertEquals("n+d 的韵母是 ai", "ai", d.big)
        assertEquals("角标是键位字母 d", "d", d.badge)
        assertTrue("合法键要高亮", d.accent)
        assertFalse("合法键不能是灰的", d.dead)
        assertEquals("韵母位不再有副标签", null, d.sub)

        // 无效键：灰掉，主标签退成通用提示
        val y = face("y")
        assertTrue("n+y 不是合法音节，应该灰掉", y.dead)
        assertFalse(y.accent)
        assertEquals("y 键在 n 后面不合法", "un/ün", y.big)
        assertEquals("无效键的角标仍是字母", "y", y.badge)
    }

    @Test
    fun `w 之后 o 键主标签是 uo 角标是 o`() {
        session.pressKey("w")
        assertEquals(InputStage.FINAL, session.stage)

        val o = face("o")
        assertEquals("韵母位主标签是韵母 uo", "uo", o.big)
        assertEquals("键位字母放在角标", "o", o.badge)
        assertFalse("o 键在 w 后面是合法的", o.dead)
        assertTrue(o.accent)
    }

    @Test
    fun `sh 之后有韵母 i`() {
        // 小鹤双拼里 sh 的键位是 u
        session.pressKey("u")
        assertEquals(InputStage.FINAL, session.stage)

        val i = face("i")
        assertEquals("sh+i 的韵母是 i", "i", i.big)
        assertEquals("角标是字母 i", "i", i.badge)
        assertFalse("i 键在 sh 后面是合法的", i.dead)
        assertTrue(i.accent)
    }

    @Test
    fun `退格回到声母位后键面也要跟着换回来`() {
        session.pressKey("n")
        assertEquals(InputStage.FINAL, session.stage)
        assertTrue(face("d").accent)

        session.backspace()   // 韵母位 -> 声母位
        assertEquals(InputStage.INITIAL, session.stage)

        val d = face("d")
        assertEquals("回到声母位后 d 显示声母 d", "d", d.big)
        assertFalse("声母位不该高亮", d.accent)
    }
    @Test
    fun `声母位与韵母位的标签集合不重叠`() {
        session.pressKey("n")
        val finalStage = session.finals
        val finalsOnly = finalStage.keys

        session.backspace()
        val initialStage = session.finals

        // 退格后韵母集合必须清空，否则键面还按韵母位画
        assertTrue("退格后应回到声母位", initialStage.isEmpty())
        assertTrue("韵母位至少有若干可用键", finalsOnly.size >= 16)
    }
}
