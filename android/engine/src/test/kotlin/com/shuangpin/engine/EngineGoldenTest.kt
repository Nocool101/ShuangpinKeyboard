package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Golden test：逐条移植 test_engine.py（Playwright 版）的断言。
 *
 * 目的不是「测一遍 Kotlin」，而是**锁死 Kotlin 与 JS 原型行为一致**。
 * 以后任何人改动引擎，两端必须同时通过同一组期望值。
 */
class EngineGoldenTest {

    companion object {
        private lateinit var data: ImeData
        private lateinit var xiaohe: PinyinEngine
        private lateinit var ziranma: PinyinEngine

        @BeforeClass
        @JvmStatic
        fun load() {
            data = TestData.data
            xiaohe = data.engine("xiaohe")
            ziranma = data.engine("ziranma")
        }
    }

    // ---------------------------------------------------------------
    // 键位表编码正确性
    // ---------------------------------------------------------------

    @Test
    fun `编码正确性`() {
        val cases = listOf(
            "bai" to "bd",
            "zhong" to "vs",
            "xue" to "xt",
            "wen" to "wf",
            "ping" to "pk",
            "liang" to "ll",
            "nv" to "nv",
            "ju" to "jv",
            "jue" to "jt",
            "chuang" to "il",
        )
        for ((pinyin, expected) in cases) {
            assertEquals("encode($pinyin)", expected, xiaohe.encode(pinyin))
        }
    }

    // ---------------------------------------------------------------
    // 零声母（小鹤规则）
    // ---------------------------------------------------------------

    @Test
    fun `零声母编码`() {
        val cases = listOf(
            "a" to "aa",
            "ai" to "ai",
            "an" to "an",
            "ang" to "ah",
            "en" to "en",
            "eng" to "eg",
            "er" to "er",
            "ou" to "ou",
        )
        for ((pinyin, expected) in cases) {
            assertEquals("encode($pinyin)", expected, xiaohe.encode(pinyin))
        }
    }

    // ---------------------------------------------------------------
    // y/w 开头（整体认读）：wang 必须是 w + h
    // ---------------------------------------------------------------

    @Test
    fun `wang 编码是 wh`() {
        // 「王/往/网」的韵母是 **ang**（h 键），不是 uang（l 键）——
        // uang 是「光/双/装」那一类。原型里写成 wang:'uang' 时，
        // 按 w 之后韵母位上没有 ang，「wang」根本打不出来
        // （用户反馈「打不出 wang，按了 w 不显示 ang」）。
        assertEquals("encode(wang)", "wh", xiaohe.encode("wang"))
        assertEquals("encode(wan)", "wr", xiaohe.encode("wan"))
        assertEquals("encode(wai)", "wd", xiaohe.encode("wai"))
        assertEquals("encode(wei)", "ww", xiaohe.encode("wei"))
        assertEquals("encode(wa)", "wa", xiaohe.encode("wa"))
        assertEquals("encode(wo)", "wo", xiaohe.encode("wo"))
    }

    @Test
    fun `按 w 之后 ang 可选`() {
        // 韵母位上必须能选到 ang（h 键）
        val finals = xiaohe.resolveFinals("w")
        assertTrue("按 w 之后应该有 ang（h 键），实际：${finals.keys}", finals.containsKey("h"))
        assertEquals("wang", finals["h"]?.syllable)
    }

    @Test
    fun `w 加 h 能打出 wang`() {
        val session = InputSession(xiaohe)
        assertTrue(session.pressKey("w"))
        assertTrue(session.pressKey("h"))
        assertEquals("wang", session.pinyin)
    }

    /**
     * 零声母编码现在由方案的 zeroStage 反推而来（原型里是另一份硬编码表）。
     * 这条用例专门锁死两者一致，防止重构时悄悄跑偏。
     */
    @Test
    fun `零声母编码与原型硬编码表一致`() {
        val jsHardcoded = mapOf(
            "a" to "aa", "ai" to "ai", "an" to "an", "ang" to "ah", "ao" to "ao",
            "e" to "ee", "ei" to "ei", "en" to "en", "eng" to "eg", "er" to "er",
            "o" to "oo", "ou" to "ou",
        )
        for ((fin, expected) in jsHardcoded) {
            assertEquals("zero($fin)", expected, xiaohe.scheme.zeroInitialCodes[fin])
            assertEquals("zero($fin)", expected, ziranma.scheme.zeroInitialCodes[fin])
        }
    }

    // ---------------------------------------------------------------
    // 第一键：26 个键全部合法
    // ---------------------------------------------------------------

    @Test
    fun `26 个键都能作为第一键`() {
        val bad = mutableListOf<String>()
        for (k in 'a'..'z') {
            if (xiaohe.resolveFinals(k.toString()).isEmpty()) bad += k.toString()
        }
        assertTrue("这些键作为第一键无解：$bad", bad.isEmpty())
    }

    // ---------------------------------------------------------------
    // b 的第二阶段：16 个可用键，且歧义已消解
    // ---------------------------------------------------------------

    @Test
    fun `b 的第二阶段有 16 个可用键`() {
        assertEquals(16, xiaohe.resolveFinals("b").size)
    }

    @Test
    fun `b 加 d 得到 bai`() {
        val hit = xiaohe.resolveFinals("b")["d"]
        assertNotNull(hit)
        assertEquals("ai", hit!!.fin)
        assertEquals("bai", hit.syllable)
    }

    @Test
    fun `b 加 k 得到 bing`() {
        val hit = xiaohe.resolveFinals("b")["k"]
        assertNotNull(hit)
        assertEquals("ing", hit!!.fin)
        assertEquals("bing", hit.syllable)
    }

    @Test
    fun `b 加 e 不是合法音节`() {
        assertNull(xiaohe.resolveFinals("b")["e"])
    }

    @Test
    fun `b 的所有第二键都不带歧义`() {
        val ambiguous = xiaohe.resolveFinals("b").values.filter { it.fin.contains('/') }
        assertTrue("仍有歧义：$ambiguous", ambiguous.isEmpty())
    }

    // ---------------------------------------------------------------
    // 9 个歧义键在每个声母下都唯一
    // ---------------------------------------------------------------

    @Test
    fun `9 个歧义键在任意声母下都唯一`() {
        val ambiguousKeys = listOf("k", "s", "x", "l", "o", "v", "y", "t", "r")
        val unresolved = mutableListOf<String>()
        for (first in 'a'..'z') {
            val finals = xiaohe.resolveFinals(first.toString())
            for (k in ambiguousKeys) {
                val hit = finals[k] ?: continue
                if (hit.fin.contains('/')) unresolved += "$first+$k=${hit.fin}"
            }
        }
        assertTrue("未消解的歧义：${unresolved.joinToString(" | ")}", unresolved.isEmpty())
    }

    // ---------------------------------------------------------------
    // 每个音节都能解出合法编码
    // ---------------------------------------------------------------

    @Test
    fun `每个音节都有两键编码`() {
        val noCode = data.table.byInitialFinal.values
            .filter { xiaohe.encode(it).length != 2 }
        assertTrue("无合法编码的音节：${noCode.take(10)}", noCode.isEmpty())
    }

    // ---------------------------------------------------------------
    // 编码可逆
    // ---------------------------------------------------------------

    @Test
    fun `所有编码都可逆`() {
        val decoded = LinkedHashMap<String, String>()
        for (syl in data.table.byInitialFinal.values) {
            decoded[xiaohe.encode(syl)] = syl
        }
        val broken = decoded.mapNotNull { (code, syl) ->
            if (code.length != 2) return@mapNotNull "$code=$syl (编码长度非法)"
            val hit = xiaohe.resolveFinals(code[0].toString())[code[1].toString()]
            if (hit == null || hit.syllable != syl) {
                "$code=$syl vs ${hit?.syllable ?: "null"}"
            } else {
                null
            }
        }
        assertTrue("不可逆的编码：${broken.joinToString(" | ")}", broken.isEmpty())
    }

    // ---------------------------------------------------------------
    // 校验用例（两套方案各 10 条）
    // ---------------------------------------------------------------

    @Test
    fun `小鹤双拼校验用例`() {
        val cases = data.checkCases.filter { it.schemeId == "xiaohe" }
        assertEquals("校验用例条数应为 10", 10, cases.size)
        for (c in cases) {
            assertEquals("${c.hanzi} ${c.pinyin}", c.expectedCode, xiaohe.encode(c.pinyin))
        }
    }

    @Test
    fun `自然码双拼校验用例`() {
        val cases = data.checkCases.filter { it.schemeId == "ziranma" }
        assertEquals("校验用例条数应为 10", 10, cases.size)
        for (c in cases) {
            assertEquals("${c.hanzi} ${c.pinyin}", c.expectedCode, ziranma.encode(c.pinyin))
        }
    }

    // ---------------------------------------------------------------
    // 词表覆盖
    // ---------------------------------------------------------------

    @Test
    fun `常用词都能命中`() {
        val cases = mapOf(
            "nihao" to "你好",
            "zhongguo" to "中国",
            "shenme" to "什么",
            "women" to "我们",
            "xiexie" to "谢谢",
            "pengyou" to "朋友",
            "gongzuo" to "工作",
            "xuexi" to "学习",
            "shijian" to "时间",
            "zhongwen" to "中文",
            "dianhua" to "电话",
            "shouji" to "手机",
        )
        for ((py, word) in cases) {
            val candidates = data.dictionary.phraseCandidates(py)
            assertTrue(
                "$py 应能命中「$word」，实际候选：${candidates.take(5)}",
                candidates.contains(word),
            )
        }
    }

    @Test
    fun `词候选按词频降序`() {
        // 「你好」在 rime-ice 词库里权重最高；「拟好」同音但用得少，排第二
        val candidates = data.dictionary.phraseCandidates("nihao")
        assertTrue("nihao 应有候选", candidates.isNotEmpty())
        assertEquals("最高频的候选应是「你好」", "你好", candidates.first())

        // 多候选的情况
        val many = data.dictionary.phraseCandidates("shijian")
        assertTrue("shijian 应有多个候选，实际 ${many.size}", many.size >= 5)
        assertEquals("shijian 最高频应是「时间」", "时间", many.first())
    }

    @Test
    fun `词库覆盖音节表里的每个音节`() {
        // 真实词库会带出原型音节表的缺口——这条用例防止以后再漏
        val missing = data.table.byInitialFinal.values
            .distinct()
            .filter { data.dictionary.charsOf(it).isEmpty() }
        assertTrue("这些音节在字表里没有候选字（${missing.size}）：${missing.take(10)}", missing.isEmpty())
    }

    @Test
    fun `单字候选包含常用字`() {
        val chars = data.dictionary.charsOf("bai")
        assertTrue("bai 应有候选字", chars.isNotEmpty())
        assertTrue("「白」应在 bai 的候选里，实际：$chars", chars.contains("白"))
        assertTrue("「百」应在 bai 的候选里，实际：$chars", chars.contains("百"))
    }

    // ---------------------------------------------------------------
    // 整句：分词 + 组句
    // ---------------------------------------------------------------

    @Test
    fun `整句分词组句`() {
        val cases = listOf(
            listOf("wo", "xiang", "chi", "fan") to "我想吃饭",
            listOf("wo", "shi", "zhong", "guo", "ren") to "我是中国人",
            listOf("jin", "tian", "tian", "qi", "zhen", "hao", "a") to "今天天气真好啊",
            listOf("wo", "xi", "huan", "wo", "de", "bi", "ji", "ben") to "我喜欢我的笔记本",
            listOf("qing", "wen", "ni", "zhi", "dao", "ma") to "请问你知道吗",
            listOf("wo", "men", "shi", "yi", "ge", "tuan", "dui") to "我们是一个团队",
            listOf("ni", "hao") to "你好",
            listOf("zhong", "guo", "ren", "min") to "中国人民",
            listOf("zhe", "ge", "wen", "ti", "hen", "zhong", "yao") to "这个问题很重要",
            listOf("qing", "gei", "wo", "yi", "bei", "shui") to "请给我一杯水",
            listOf("qing", "bang", "wo", "kan", "kan", "zhe", "ge", "wen", "ti") to "请帮我看看这个问题",
        )
        for ((sylls, want) in cases) {
            assertEquals(
                "分词结果应为「$want」",
                want,
                xiaohe.sentence(sylls),
            )
        }
    }

    @Test
    fun `整句分词：词库没有的音节退回单字`() {
        val words = xiaohe.segmentWords(listOf("shi", "shi", "shi"))
        assertTrue("应能切出词", words.isNotEmpty())
        assertEquals("三个 shi 应拼回三个字的串", 3, words.joinToString("").length)
    }

    @Test
    fun `会话：预编辑区始终保留已完成的音节`() {
        val session = InputSession(xiaohe)
        session.pressKey("n")
        assertEquals("第一个音节未完成，预编辑显示 n", "n", session.composingText)
        session.pressKey("i")
        assertEquals("ni 已完成", "ni", session.composingText)
        // 敲下一个音节的第一个键：预编辑必须保留前面的 ni
        session.pressKey("h")
        assertEquals("敲下一个音节时不能丢掉已完成的 ni", "nih", session.composingText)
        session.pressKey("c")
        assertEquals("nihao", session.composingText)
    }

    @Test
    fun `整体认读音节的编码`() {
        // 小鹤规则：y/w 后面的韵母按**实际的韵母**取键位
        // 羊 = y + ang → yh；王 = w + ang → wh；万 = w + uan → wr
        //（以前「王」写成了 w + uang → wl，那是错的：
        //  按 w 之后韵母位上没有 ang，「wang」根本打不出来。）
        val cases = mapOf(
            "yang" to "yh",   // 羊
            "yan" to "yj",    // 眼
            "yao" to "yc",    // 要
            "ye" to "ye",     // 也
            "you" to "yz",    // 有
            "yong" to "ys",   // 用
            "ying" to "yk",   // 赢
            "yi" to "yi",     // 一
            "yu" to "yv",     // 于
            "yue" to "yt",    // 月
            "yuan" to "yr",   // 元
            "yun" to "yy",    // 云
            "wo" to "wo",     // 我
            "wang" to "wh",   // 王 —— 韵母是 **ang**（h 键），不是 uang（l 键）
            "wan" to "wr",    // 万
            "wai" to "wd",    // 外
            "wei" to "ww",    // 喂
            "wen" to "wf",    // 问
            "weng" to "wg",   // 翁
            "wu" to "wu",     // 乌
        )
        for ((syl, code) in cases) {
            assertEquals("编码 $syl", code, xiaohe.encode(syl))
        }
    }

    @Test
    fun `会话：整句上屏`() {
        val session = InputSession(xiaohe)
        // nihao -> ni, hao
        session.pressKey("n")
        session.pressKey("i")
        session.pressKey("h")
        session.pressKey("c")
        assertEquals("nihao", session.pinyin)
        assertEquals("你好", session.sentence)
        assertEquals("你好", session.commitTop())
        assertEquals("你好", session.committed)
    }

    @Test
    fun `会话：多音节整句上屏`() {
        val session = InputSession(xiaohe)
        // 小鹤双拼：wo(w o) + xiang(x l) + chi(i i) + fan(f j) —— 一共 8 键
        for (k in listOf("w", "o", "x", "l", "i", "i", "f", "j")) {
            session.pressKey(k)
        }
        assertEquals("woxiangchifan", session.pinyin)
        assertEquals("我想吃饭", session.sentence)
        assertEquals("我想吃饭", session.commitTop())
    }

    @Test
    fun `字表中的音节都存在于音节表`() {
        val zeroAll = data.table.zeroSyllables.values.toSet()
        val byIf = data.table.byInitialFinal.values.toSet()
        val orphan = data.dictionary.chars.keys.filter { sy ->
            if (sy in zeroAll) return@filter false
            if (sy in byIf) return@filter false
            !byIf.any { a -> (a == "ye" && sy == "yue") || (a == "yue" && sy == "ye") }
        }
        assertTrue("字表里有音节表不存在的音节：$orphan", orphan.isEmpty())
    }

    @Test
    fun `常用音节都在音节表里`() {
        val common = listOf(
            "hao", "hui", "gong", "song", "zai", "zhong", "shi", "de", "wo", "ni",
        )
        val zeroAll = data.table.zeroSyllables.values.toSet()
        val byIf = data.table.byInitialFinal.values.toSet()
        val missing = common.filter { it !in zeroAll && it !in byIf }
        assertTrue("缺少常用音节：$missing", missing.isEmpty())
    }

    // ---------------------------------------------------------------
    // 会话状态机（对应原型的交互流程）
    // ---------------------------------------------------------------

    @Test
    fun `会话：b 然后 d 得到 bai`() {
        val session = InputSession(xiaohe)
        assertTrue(session.pressKey("b"))
        assertEquals(InputStage.FINAL, session.stage)
        assertEquals(16, session.finals.size)
        assertTrue(session.pressKey("d"))
        assertEquals(InputStage.SYLLABLE, session.stage)
        assertEquals("bai", session.pinyin)
        assertEquals("白", session.previewChars)
    }

    @Test
    fun `会话：nihao 命中词组`() {
        val session = InputSession(xiaohe)
        for (k in listOf("n", "i", "h", "c")) {
            assertTrue("按键 $k 应被接受", session.pressKey(k))
        }
        assertEquals("nihao", session.pinyin)
        assertEquals("你好", session.phrase)
    }

    @Test
    fun `会话：零声母 a 加 i 得到爱`() {
        val session = InputSession(xiaohe)
        assertTrue(session.pressKey("a"))
        assertEquals(InputStage.FINAL, session.stage)
        assertTrue(session.pressKey("i"))
        assertEquals("ai", session.pinyin)
        assertEquals("爱", session.previewChars)
    }

    @Test
    fun `会话：整体认读 y 加 t 得到月`() {
        val session = InputSession(xiaohe)
        assertTrue(session.pressKey("y"))
        assertTrue(session.pressKey("t"))
        // y + t = yue（月）：ü 的键位是 t
        assertEquals("yue", session.pinyin)
        // rime-ice 的字频表里「越」比「月」稍高频，「月」在候选里但排第二
        assertTrue("候选里应有「月」，实际 ${session.candidates}", session.candidates.contains("月"))
    }

    @Test
    fun `会话：退格逐级回退`() {
        val session = InputSession(xiaohe)
        session.pressKey("b")
        session.pressKey("d")
        assertEquals(InputStage.SYLLABLE, session.stage)
        assertTrue(session.backspace())
        assertEquals(InputStage.FINAL, session.stage)
        assertTrue(session.backspace())
        assertEquals(InputStage.INITIAL, session.stage)
        // 声母位没有内容了，退格应报告「什么都没删」
        assertFalse(session.backspace())
        assertEquals("", session.committed)
    }

    @Test
    fun `会话：声母位退格先删音节再删上屏文本`() {
        val session = InputSession(xiaohe)
        // 打两个音节 nihao
        for (k in listOf("n", "i", "h", "c")) session.pressKey(k)
        assertEquals("nihao", session.pinyin)

        session.backspace()          // -> 韵母位，还剩 ni
        session.backspace()          // -> 声母位，还剩 ni
        assertEquals("ni", session.pinyin)

        // 声母位再退格：应该先删掉 ni 这个音节，而不是去删已上屏文本
        assertTrue(session.backspace())
        assertEquals("", session.pinyin)
        assertEquals("", session.committed)

        // 再退格才是删已上屏文本
        session.commitText("x")
        assertTrue(session.backspace())
        assertEquals("", session.committed)
    }

    @Test
    fun `会话：选字上屏`() {
        val session = InputSession(xiaohe)
        session.pressKey("b")
        session.pressKey("d")
        val text = session.commitChar("摆")
        assertEquals("摆", text)
        assertEquals("摆", session.committed)
        assertEquals(InputStage.INITIAL, session.stage)
    }
}
