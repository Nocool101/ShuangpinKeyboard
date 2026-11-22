package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 下一词建议的清洗。
 *
 * 这块出问题最难查：模型回了「火焰山」，界面照单全收，
 * 用户点一下得到「火焰火焰山」——不报错、不崩，只是结果不对。
 */
class NextWordsTest {

    @Test
    fun `模型把上文一起吐回来时削掉重复的部分`() {
        // 上屏「火焰」，模型回「火焰山」→ 真正要接着打的是「山」
        assertEquals("山", NextWords.stripContext("火焰山", "火焰"))
    }

    @Test
    fun `只削上文的后缀那一段`() {
        // 上文「我要去」，模型回「要去的地方」→ 削掉「要去」，留「的地方」
        assertEquals("的地方", NextWords.stripContext("要去的地方", "我要去"))
    }

    @Test
    fun `整个词都落在上文里就丢掉`() {
        // 「焰」就是上文的最后一个字，再提一次没有意义
        assertNull(NextWords.stripContext("焰", "火焰"))
    }

    @Test
    fun `词在上文中间不算重复`() {
        // 「火」在上文里，但上文的**最后一个字**是「焰」——
        // 削掉的是「上文的尾巴」，不是「上文里出现过的每一个字」
        assertEquals("火", NextWords.stripContext("火", "火焰"))
    }

    @Test
    fun `跟上文没有重叠就原样保留`() {
        assertEquals("山峰", NextWords.stripContext("山峰", "火焰"))
    }

    @Test
    fun `没有上文就原样保留`() {
        assertEquals("山峰", NextWords.stripContext("山峰", ""))
    }

    @Test
    fun `空白词丢掉`() {
        assertNull(NextWords.stripContext("   ", "火焰"))
    }

    @Test
    fun `整批清洗去掉重复并保序`() {
        val out = NextWords.clean(listOf("火焰山", "伤害", "山", "火焰伤害"), "火焰")
        assertEquals(listOf("山", "伤害"), out)
    }
}
