package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **滑动挪光标**的计算。
 *
 * 没有输入的时候，用户在键盘字母区左右滑动手指挪宿主文本里的光标：
 * 「手往哪个方向滑，光标就往哪个方向走」。
 */
class CursorMoveTest {

    // ------------------------------------------------------------------
    // 横向
    // ------------------------------------------------------------------

    @Test
    fun `往右挪一个字`() {
        assertEquals(3, CursorMove.horizontal("你好世界", 2, 1))
    }

    @Test
    fun `往左挪一个字`() {
        assertEquals(1, CursorMove.horizontal("你好世界", 2, -1))
    }

    @Test
    fun `往右挪几个字`() {
        // 从头往右挪 3 个字 → 下标 3
        assertEquals(3, CursorMove.horizontal("你好世界", 0, 3))
        // 挪 4 个字就到末尾了
        assertEquals(4, CursorMove.horizontal("你好世界", 0, 4))
    }

    @Test
    fun `越界就停在边上`() {
        // 已经在末尾，往右挪不动
        assertEquals(4, CursorMove.horizontal("你好世界", 4, 5))
        // 已经在开头，往左挪不动
        assertEquals(0, CursorMove.horizontal("你好世界", 0, -5))
    }

    @Test
    fun `代理对算一个字`() {
        // "a😀b"：😀 是两个码元，但它是**一个**字
        val text = "a😀b"
        assertEquals(3, CursorMove.horizontal(text, 1, 1))
        assertEquals(1, CursorMove.horizontal(text, 3, -1))
    }

    @Test
    fun `不动就是不动`() {
        assertEquals(2, CursorMove.horizontal("你好世界", 2, 0))
    }
}
