package com.shuangpin.engine

/**
 * 韵母位（按完声母、还没定音节）的键位排布。
 *
 * ## 为什么单手模式要重排
 *
 * 双拼的**声母位**字母位置一律不动——位置记忆绑在字母上（小鹤 zh→v、ch→i、sh→u），
 * 打乱了就废。
 *
 * 但**韵母位**不一样：这一屏每个键显示的是韵母，字母只是左上角的角标，
 * 用户读的是「我要按的那个韵母在哪儿」。
 * 单手模式下拇指够不到整块键盘，把能按的韵母全堆到拇指那一侧，
 * 够得着的韵母就多了。
 *
 * ## 排法
 *
 * - 从**拇指那一侧的底行**开始填，底行填满再往上一行
 * - 行内方向也是从拇指那一侧往里（右手模式：右 → 左）
 * - 填的顺序是韵母的常用度（[com.shuangpin.engine.InputSession.finals] 的顺序），
 *   最常用的韵母落在最好够到的位置
 * - 填不上的坑（无效键）保持字母表顺序填进去，键位总数不变，
 *   所以键盘的整体形状和键宽都不变——用户不会觉得键盘「塌了」
 *
 * 全键盘模式**不重排**：双手都能按，位置记忆更重要。
 */
object FinalLayout {

    /** 拇指在哪一侧。 */
    enum class Hand {
        LEFT,
        RIGHT,
    }

    /**
     * 把 [rows] 里的字母重排：有效的韵母键聚到拇指侧，无效键填剩下的坑。
     *
     * @param rows 原始键位分行（字母，每个字母一个键）
     * @param valid 有效的韵母键，按常用度排序（前面的更常用）
     * @param hand 拇指在哪一侧
     * @return 重排后的分行；字母个数与 [rows] 完全一致
     */
    fun pack(rows: List<List<String>>, valid: List<String>, hand: Hand): List<List<String>> {
        val all = rows.flatten()
        val inRows = all.toSet()
        // 只认键位表里真实存在的键（防御：方案表里出现了键位表没有的字母时不至于崩）
        val validList = valid.filter { it in inRows }
        if (validList.isEmpty()) return rows

        val validSet = validList.toSet()
        val dead = all.filter { it !in validSet }
        val ordered = validList + dead

        // 槽位顺序：从拇指那一侧的底行开始，逐行往里
        val slots = ArrayList<Pair<Int, Int>>(all.size)
        for (r in rows.indices.reversed()) {
            val cols = if (hand == Hand.RIGHT) rows[r].indices.reversed() else rows[r].indices
            for (c in cols) slots += r to c
        }

        val grid = rows.map { it.toMutableList() }
        var k = 0
        for ((r, c) in slots) {
            if (k >= ordered.size) break
            grid[r][c] = ordered[k]
            k++
        }
        return grid
    }
}
