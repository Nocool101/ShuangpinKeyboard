package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * **小鹤双拼键盘布局**的测试。
 *
 * 守的是用户提的第一条需求：「把韵母布局换成小鹤双拼键盘布局」。
 *
 * 具体就是：**每个韵母永远在它那个键上**——
 * ao 在 c、ian 在 m、uang 在 l、ing 在 k……
 * 换声母也不动位置（位置记忆绑在键上）。
 *
 * 有歧义的键（r = er/uan/üan，k = ing/uai……）由引擎按声母预先消解，
 * 所以用户按下的一定是对的——见 [FinalLayoutTest] 与引擎里的消歧逻辑。
 */
class XiaoheLayoutTest {

    companion object {
        private lateinit var engine: PinyinEngine

        @BeforeClass
        @JvmStatic
        fun load() {
            engine = TestData.engine()
        }
    }

    /** 标准 QWERTY 三行。韵母位的键就是这套位置，一个都不许挪。 */
    private val rows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m"),
    )

    private val allKeys = rows.flatten().toSet()

    /** 小鹤双拼的标准韵母键位表。 */
    private val expected = mapOf(
        "a" to "a", "o" to "o", "e" to "e", "i" to "i", "u" to "u", "v" to "v",
        "ai" to "d", "ei" to "w", "ao" to "c", "ou" to "z", "iu" to "q", "ie" to "p",
        "ve" to "t", "er" to "r", "an" to "j", "en" to "f", "in" to "b",
        "un" to "y", "vn" to "y", "van" to "r", "ang" to "h", "eng" to "g",
        "ing" to "k", "ong" to "s", "ia" to "x", "ua" to "x", "uo" to "o",
        "uai" to "k", "uan" to "r", "uang" to "l", "iong" to "s", "iao" to "n",
        "ian" to "m", "iang" to "l",
    )

    @Test
    fun `小鹤韵母键位表`() {
        for ((fin, key) in expected) {
            assertEquals("韵母 $fin 应该在 $key 键", key, engine.scheme.finals[fin])
        }
    }

    @Test
    fun `韵母键位都在标准字母键上`() {
        for ((fin, key) in engine.scheme.finals) {
            assertTrue("韵母 $fin 的键位 $key 不在标准键盘上", key in allKeys)
        }
    }

    @Test
    fun `换任何声母都不改变韵母的键位`() {
        // 这是「固定布局」的全部意义：按完声母之后，
        // 每个韵母还在它固定的那个键上——用户不用重新认位置。
        //
        // 零声母键（a/e/o）走自己的二段表，不在这条规则里。
        for (initial in allKeys - ZERO_KEYS) {
            val resolved = engine.resolveFinals(initial)
            if (resolved.isEmpty()) continue
            for ((key, hit) in resolved) {
                assertTrue("键位 $key 不在标准键盘上", key in allKeys)
                val finalsOnKey = engine.scheme.finalsByKey[key].orEmpty()
                assertTrue(
                    "声母 $initial 下 $key 键给出的韵母 ${hit.fin} 不属于该键（应有 $finalsOnKey）",
                    finalsOnKey.contains(hit.fin),
                )
            }
        }
    }

    @Test
    fun `歧义键在每个声母下都唯一`() {
        // 歧义预消解：小鹤的 9 个天然歧义键，
        // 声母一旦确定，留下的韵母就唯一了——用户按不下去错的键。
        for (initial in allKeys) {
            val resolved = engine.resolveFinals(initial)
            resolved.forEach { (key, hit) ->
                assertEquals(
                    "声母 $initial 下 $key 键应该只对应一个韵母",
                    1,
                    hit.finals.size,
                )
            }
        }
    }

    @Test
    fun `常用韵母能打出来`() {
        // 抽查几条：键位对不对，直接看打出来的音节
        val cases = listOf(
            listOf("h", "c") to "hao",   // h + ao(c)
            listOf("n", "m") to "nian",  // n + ian(m)
            listOf("k", "l") to "kuang", // k + uang(l)
            listOf("j", "k") to "jing",  // j + ing(k)
        )
        for ((keys, syllable) in cases) {
            val session = InputSession(engine)
            for (k in keys) session.pressKey(k)
            assertEquals("按 ${keys.joinToString("")} 应该打出 $syllable", syllable, session.pinyin)
        }
    }
}
