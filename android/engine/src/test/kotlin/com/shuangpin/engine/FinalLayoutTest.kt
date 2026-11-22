package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 韵母位重排的测试。
 *
 * 守的是这条规则：**单手模式下，所有能按的韵母都要落在拇指够得到的那一侧**，
 * 全键盘模式不动。
 */
class FinalLayoutTest {

    private val rows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m"),
    )

    /** 每一行里，有效韵母是不是都贴着拇指那一侧（右手=右边，左手=左边）。 */
    private fun validColsPerRow(out: List<List<String>>, valid: Set<String>): List<List<Int>> =
        out.map { row -> row.mapIndexed { c, k -> if (k in valid) c else -1 }.filter { it >= 0 } }

    @Test
    fun `右手模式把有效韵母聚到右边`() {
        val valid = listOf("a", "o", "e", "i", "u", "v")
        val out = FinalLayout.pack(rows, valid, FinalLayout.Hand.RIGHT)

        // 键位总数不能变（键盘形状不变）
        assertEquals(rows.flatten().toSet(), out.flatten().toSet())
        // 有效韵母一个都不能丢
        assertTrue(out.flatten().containsAll(valid))
        // 每一行里，有效韵母都占住最右边的一段
        validColsPerRow(out, valid.toSet()).forEach { cols ->
            if (cols.isEmpty()) return@forEach
            assertEquals("每一行的有效韵母应该连续", cols.size, cols.max() - cols.min() + 1)
            assertEquals("有效韵母应该贴着右边", cols.max(), 6.coerceAtMost(9))
        }
        // 六个韵母塞得下底行，全在底行
        assertEquals(6, out[2].count { it in valid.toSet() })
    }

    @Test
    fun `左手模式把有效韵母聚到左边`() {
        val valid = listOf("a", "o", "e", "i", "u", "v")
        val out = FinalLayout.pack(rows, valid, FinalLayout.Hand.LEFT)

        assertEquals(rows.flatten().toSet(), out.flatten().toSet())
        validColsPerRow(out, valid.toSet()).forEach { cols ->
            if (cols.isEmpty()) return@forEach
            assertEquals("每一行的有效韵母应该连续", cols.size, cols.max() - cols.min() + 1)
            assertEquals("有效韵母应该贴着左边", cols.min(), 0)
        }
        assertEquals(6, out[2].count { it in valid.toSet() })
    }

    @Test
    fun `常用度高的韵母落在最靠近拇指的位置`() {
        // 右手模式：最常用的韵母应该占住右下角（底行最右）
        val valid = listOf("a", "o", "e", "i", "u", "v")
        val out = FinalLayout.pack(rows, valid, FinalLayout.Hand.RIGHT)
        assertEquals("a", out[2][6])

        // 左手模式：镜像——同一个韵母占住左下角
        val outL = FinalLayout.pack(rows, valid, FinalLayout.Hand.LEFT)
        assertEquals("a", outL[2][0])
    }

    @Test
    fun `无效键填满剩下的坑`() {
        val valid = listOf("a", "o")
        val out = FinalLayout.pack(rows, valid, FinalLayout.Hand.RIGHT)
        assertEquals(26, out.flatten().size)
        assertEquals(26, out.flatten().toSet().size)
        assertEquals(rows.flatten().toSet(), out.flatten().toSet())
    }

    @Test
    fun `没有有效韵母时原样返回`() {
        assertEquals(rows, FinalLayout.pack(rows, emptyList(), FinalLayout.Hand.RIGHT))
    }

    @Test
    fun `左右手模式互为镜像`() {
        val valid = listOf("a", "o", "e", "i", "u", "v", "d", "w")
        val right = FinalLayout.pack(rows, valid, FinalLayout.Hand.RIGHT)
        val left = FinalLayout.pack(rows, valid, FinalLayout.Hand.LEFT)
        // 每一行都是彼此的反转
        right.forEachIndexed { r, row ->
            assertEquals(row.reversed(), left[r])
        }
    }

    @Test
    fun `韵母多到装不下一行时往上溢出但仍贴着拇指侧`() {
        val valid = listOf("a", "o", "e", "i", "u", "v", "d", "w", "c", "z", "x", "b")
        val out = FinalLayout.pack(rows, valid, FinalLayout.Hand.RIGHT)
        assertEquals(7, out[2].count { it in valid.toSet() })   // 底行放满 7 个
        assertEquals(5, out[1].count { it in valid.toSet() })   // 溢出 5 个到中间行
        assertEquals(0, out[0].count { it in valid.toSet() })   // 顶行（够不着）一个不放
        // 中间行的 5 个也都贴着右边
        val cols = validColsPerRow(out, valid.toSet())[1]
        assertEquals(listOf(4, 5, 6, 7, 8), cols)
    }

    @Test
    fun `真实声母的韵母也全落在拇指侧`() {
        val data = TestData.data
        val engine = data.engine("xiaohe")
        val session = InputSession(engine)

        session.pressKey("v")   // zh
        assertEquals(InputStage.FINAL, session.stage)
        val valid = session.finals.keys.toSet()
        assertTrue("zh 下应该有若干有效韵母键", valid.size >= 5)

        val out = FinalLayout.pack(rows, valid.toList(), FinalLayout.Hand.RIGHT)
        // 一个有效韵母都不能丢
        assertTrue(out.flatten().containsAll(valid))
        // 每一行里都贴着右边（连续的一段）
        validColsPerRow(out, valid).forEach { cols ->
            if (cols.isEmpty()) return@forEach
            assertEquals("每一行的有效韵母应该连续", cols.size, cols.max() - cols.min() + 1)
        }
    }
}
