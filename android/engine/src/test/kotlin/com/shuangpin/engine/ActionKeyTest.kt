package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **动作键**的判定规则测试。
 *
 * 守的是这几条：
 *
 * 1. 聊天软件（微信 / QQ）里，右下角那颗键是「发送」——
 *    **哪怕它的输入框是多行的、哪怕它没声明 action**。
 * 2. 浏览器里是「前往」（点一下就搜索 / 打开网址）。
 * 3. 多行文本框里是「换行」。
 * 4. 编辑框自己声明的 action 要认（send / go / search / done）。
 */
class ActionKeyTest {

    /** 多行文本框的 inputType（TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_MULTI_LINE）。 */
    private val multilineType = 0x00000001 or ActionKey.TYPE_TEXT_FLAG_MULTI_LINE

    // ------------------------------------------------------------------
    // 聊天软件
    // ------------------------------------------------------------------

    @Test
    fun `微信聊天框是发送`() {
        assertEquals(
            ActionKind.SEND,
            ActionKey.resolve("com.tencent.mm", ActionKey.IME_ACTION_NONE, 0),
        )
    }

    @Test
    fun `QQ聊天框是发送`() {
        assertEquals(
            ActionKind.SEND,
            ActionKey.resolve("com.tencent.mobileqq", ActionKey.IME_ACTION_NONE, 0),
        )
    }

    @Test
    fun `聊天软件优先于多行`() {
        // 这是最容易判错的一条：微信的聊天框**既是多行的**，又**不声明 action**。
        // 先看多行的话，微信就永远发不出消息了。
        assertEquals(
            ActionKind.SEND,
            ActionKey.resolve("com.tencent.mm", ActionKey.IME_ACTION_NONE, multilineType),
        )
    }

    // ------------------------------------------------------------------
    // 浏览器
    // ------------------------------------------------------------------

    @Test
    fun `Via浏览器是前往`() {
        assertEquals(
            ActionKind.GO,
            ActionKey.resolve("mark.via.gp", ActionKey.IME_ACTION_NONE, 0),
        )
    }

    @Test
    fun `浏览器优先于搜索action`() {
        // 地址栏里声明 SEARCH 的浏览器，对用户来说那一下永远是「搜索 / 前往」
        assertEquals(
            ActionKind.GO,
            ActionKey.resolve("com.android.chrome", ActionKey.IME_ACTION_SEARCH, 0),
        )
    }

    @Test
    fun `浏览器里搜索action也是前往`() {
        assertEquals(
            ActionKind.GO,
            ActionKey.resolve("mark.via.gp", ActionKey.IME_ACTION_SEARCH, 0),
        )
    }

    // ------------------------------------------------------------------
    // 多行 / 单行
    // ------------------------------------------------------------------

    @Test
    fun `多行文本框是换行`() {
        assertEquals(
            ActionKind.NEWLINE,
            ActionKey.resolve("com.example.notes", ActionKey.IME_ACTION_NONE, multilineType),
        )
    }

    @Test
    fun `普通单行输入框是完成`() {
        assertEquals(
            ActionKind.DONE,
            ActionKey.resolve("com.example.form", ActionKey.IME_ACTION_NONE, 0),
        )
    }

    @Test
    fun `没声明action和显式声明NONE是一样的`() {
        assertEquals(
            ActionKey.resolve("com.example.form", ActionKey.IME_ACTION_UNSPECIFIED, 0),
            ActionKey.resolve("com.example.form", ActionKey.IME_ACTION_NONE, 0),
        )
    }

    // ------------------------------------------------------------------
    // 编辑框声明的 action
    // ------------------------------------------------------------------

    @Test
    fun `声明send就是发送`() {
        assertEquals(
            ActionKind.SEND,
            ActionKey.resolve("com.example.app", ActionKey.IME_ACTION_SEND, 0),
        )
    }

    @Test
    fun `声明go就是前往`() {
        assertEquals(
            ActionKind.GO,
            ActionKey.resolve("com.example.app", ActionKey.IME_ACTION_GO, 0),
        )
    }

    @Test
    fun `非浏览器的搜索框是搜索`() {
        assertEquals(
            ActionKind.SEARCH,
            ActionKey.resolve("com.example.app", ActionKey.IME_ACTION_SEARCH, 0),
        )
    }

    @Test
    fun `声明done就是完成`() {
        assertEquals(
            ActionKind.DONE,
            ActionKey.resolve("com.example.app", ActionKey.IME_ACTION_DONE, 0),
        )
    }

    @Test
    fun `媒体控制的action不做事`() {
        assertEquals(
            ActionKind.NONE,
            ActionKey.resolve("com.example.app", ActionKey.IME_ACTION_NEXT, 0),
        )
        assertEquals(
            ActionKind.NONE,
            ActionKey.resolve("com.example.app", ActionKey.IME_ACTION_PREVIOUS, 0),
        )
    }

    @Test
    fun `多行但声明了send时以send为准`() {
        assertEquals(
            ActionKind.SEND,
            ActionKey.resolve("com.example.app", ActionKey.IME_ACTION_SEND, multilineType),
        )
    }

    // ------------------------------------------------------------------
    // 键面文案
    // ------------------------------------------------------------------

    @Test
    fun `键面文案`() {
        assertEquals("发送", ActionKey.label(ActionKind.SEND))
        assertEquals("换行", ActionKey.label(ActionKind.NEWLINE))
        assertEquals("前往", ActionKey.label(ActionKind.GO))
        assertEquals("搜索", ActionKey.label(ActionKind.SEARCH))
        assertEquals("完成", ActionKey.label(ActionKind.DONE))
    }
}
