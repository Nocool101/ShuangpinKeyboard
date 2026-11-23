package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **选字面板的结果拼装**。
 *
 * 用户提的场景：
 * > 输入多个字以后，点向下箭头打开选字面板，只改最后一个字，
 * > 前面的字不会上屏，只会上屏最后一个字。
 *
 * 根因：面板把**没挑过的音节**拼成了空串。
 */
class PanelPickTest {

    @Test
    fun `没挑过的音节用默认首字补上`() {
        val text = PanelPick.assemble(
            picked = listOf(null, null, "生"),
            defaults = listOf("解", "放", "长"),
        )
        // 挑过的用挑的，没挑的用默认首字
        assertEquals("解放生", text)
    }

    @Test
    fun `全挑过就按挑的来`() {
        val text = PanelPick.assemble(
            picked = listOf("解", "放", "生"),
            defaults = listOf("甲", "乙", "丙"),
        )
        assertEquals("解放生", text)
    }

    @Test
    fun `只改最后一个字时前面的字照样上屏`() {
        // 用户只动了最后一个音节
        val text = PanelPick.assemble(
            picked = listOf(null, null, "手"),
            defaults = listOf("解", "放", "长"),
        )
        // 前面两个字不能丢
        assertTrue("前面的字也要上屏，实际 = $text", text.contains("解"))
        assertTrue("前面的字也要上屏，实际 = $text", text.contains("放"))
        assertTrue("改的那个字要上屏，实际 = $text", text.endsWith("手"))
        assertEquals(3, text.length)
    }

    @Test
    fun `挑过的空串按没挑处理`() {
        val text = PanelPick.assemble(
            picked = listOf("", null, "生"),
            defaults = listOf("甲", "乙", "丙"),
        )
        assertEquals("甲乙生", text)
    }

    @Test
    fun `音节数对不上也不崩`() {
        // 挑的音节比默认的少 → 多出来的用默认首字补
        assertEquals("甲乙", PanelPick.assemble(listOf("甲"), listOf("甲", "乙")))
        // 挑的音节比默认的多 → 多出来的用挑的
        assertEquals("甲乙丙", PanelPick.assemble(listOf("甲", "乙", "丙"), listOf("甲")))
        // 两边都空
        assertEquals("", PanelPick.assemble(emptyList(), emptyList()))
    }

    @Test
    fun `明确挑过的字能单独列出来`() {
        val picks = PanelPick.explicitPicks(listOf(null, "放", "生"))
        assertEquals(2, picks.size)
        assertEquals(1 to "放", picks[0])
        assertEquals(2 to "生", picks[1])
    }
}
