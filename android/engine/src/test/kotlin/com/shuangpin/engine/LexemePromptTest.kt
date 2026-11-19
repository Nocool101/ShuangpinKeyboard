package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端提示词与请求模式的测试。
 *
 * 这块出问题最难查：提示词不对，模型返回的东西就不对，
 * 但不会报错、也不会崩，只是「效果变差了」。
 */
class LexemePromptTest {

    @Test
    fun `有候选池是消歧模式`() {
        val req = LexemeRequest(
            text = "知乎",
            pinyin = "zhihui",
            candidates = listOf("智慧", "指挥"),
        )
        assertTrue(
            "消歧模式要强调「只能从给定候选里挑」",
            LexemePrompts.systemFor(req).contains("只能从给定的候选里挑"),
        )
    }

    @Test
    fun `只给拼音是补全模式`() {
        val req = LexemeRequest(text = "知乎", pinyin = "zhihui")
        assertTrue(
            "补全模式要强调「和拼音对得上」",
            LexemePrompts.systemFor(req).contains("对得上"),
        )
    }

    @Test
    fun `什么都没有是下一词模式`() {
        val req = LexemeRequest(text = "智慧")
        assertTrue(
            "下一词模式要说「接下来」",
            LexemePrompts.systemFor(req).contains("接下来"),
        )
    }

    @Test
    fun `给了待纠错文本是纠错模式`() {
        val req = LexemeRequest(text = "上文", input = "我系广东人")
        val system = LexemePrompts.systemFor(req)
        assertTrue("纠错模式要说「错别字」", system.contains("错别字"))
        assertTrue(
            "纠错模式必须强调「拿不准就原样返回」",
            system.contains("拿不准就原样返回"),
        )
    }

    @Test
    fun `用户消息带上全部上下文`() {
        val req = LexemeRequest(
            text = "知乎",
            pinyin = "zhihui",
            candidates = listOf("智慧", "指挥"),
        )
        val user = LexemePrompts.userFor(req)
        assertTrue("缺上文：$user", user.contains("上文：知乎"))
        assertTrue("缺拼音：$user", user.contains("正在输入：zhihui"))
        assertTrue("缺候选池：$user", user.contains("候选：智慧、指挥"))
    }

    @Test
    fun `纠错请求带上待纠错的文本`() {
        val req = LexemeRequest(text = "上文", input = "我系广东人")
        val user = LexemePrompts.userFor(req)
        assertTrue("缺待纠错文本：$user", user.contains("待纠错：我系广东人"))
    }

    @Test
    fun `空请求不发`() {
        assertTrue("空请求不该发出去", LexemeRequest().isEmpty)
        assertTrue("只有空串也算空", LexemeRequest(text = "  ").isEmpty)
        assertTrue("有上文就不算空", !LexemeRequest(text = "你好").isEmpty)
        assertTrue("有拼音就不算空", !LexemeRequest(pinyin = "nihao").isEmpty)
        assertTrue("有候选池就不算空", !LexemeRequest(candidates = listOf("你")).isEmpty)
        assertTrue("有待纠错文本就不算空", !LexemeRequest(input = "我系").isEmpty)
    }
}
