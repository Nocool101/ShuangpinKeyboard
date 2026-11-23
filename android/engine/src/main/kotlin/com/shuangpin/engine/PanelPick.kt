package com.shuangpin.engine

/**
 * **选字面板的结果拼装**。
 *
 * 面板左边是这一轮拼音的音节，右边是候选字；用户在「单字」页挑字，
 * 挑完就把整句上屏。
 *
 * ## 为什么不能只拼「挑过的字」
 *
 * 用户常常只想改**最后一个**字：点 ⌄ 打开面板 → 点最后一个音节 → 挑一个字。
 * 前面那些音节他没挑过——但它们**同样要上屏**（用默认首字），
 * 否则上屏的就只有最后那一个字，前面的内容全丢了
 * （用户反馈「只修改最后一个字时，前面的字不会上屏，只会上屏最后一个字」）。
 *
 * 放在引擎层是因为它跟「候选装配」是同一类东西：
 * **这个输入法到底给用户什么结果**，不是 UI 排版问题。
 */
object PanelPick {

    /**
     * 把面板里挑好的字拼成整句。
     *
     * @param picked   每个音节挑中的字（null = 没挑，用默认首字）
     * @param defaults 每个音节的默认首字（按音节顺序）
     * @return 上屏的整句文本
     */
    fun assemble(picked: List<String?>, defaults: List<String>): String {
        val n = maxOf(picked.size, defaults.size)
        val sb = StringBuilder()
        for (i in 0 until n) {
            val ch = picked.getOrNull(i)
            sb.append(if (!ch.isNullOrEmpty()) ch else defaults.getOrNull(i).orEmpty())
        }
        return sb.toString()
    }

    /**
     * 这一轮里**用户明确挑过**的那些字（按音节顺序）。
     *
     * 学习词库只用这些：没挑过的音节用的是默认首字，
     * 那是「凑出来」的整句，不该当成用户打出来的词记进去。
     *
     * @return (音节下标, 字) 列表
     */
    fun explicitPicks(picked: List<String?>): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        for (i in picked.indices) {
            val ch = picked[i]
            if (!ch.isNullOrEmpty()) out += i to ch
        }
        return out
    }
}
