package com.shuangpin.engine

/**
 * **光标位移的计算**。
 *
 * 没有输入的时候，用户在键盘的字母区左右滑动手指来挪宿主文本里的光标
 * （手指往哪个方向滑，光标就往哪个方向走）。
 *
 * 放在引擎层是因为它就是一段**纯文本计算**：
 * 「光标在第几个字符」「往左挪一个字符要不要算半个代理对」——
 * 不依赖 Android，可以单测，也不会跟 UI 搅在一起。
 */
object CursorMove {

    /**
     * 横向挪动。
     *
     * @param steps 正数 = 往右挪几个字，负数 = 往左挪几个字
     * @return 新的光标位置（字符下标）
     */
    fun horizontal(text: String, pos: Int, steps: Int): Int {
        if (steps == 0) return pos.coerceIn(0, text.length)
        val p = pos.coerceIn(0, text.length)
        var target = p
        if (steps > 0) {
            var left = steps
            while (left > 0 && target < text.length) {
                // 代理对算**一个**字（emoji 挪一半会变成乱码）
                val cp = text.codePointAt(target)
                target += Character.charCount(cp)
                left--
            }
        } else {
            var left = -steps
            while (left > 0 && target > 0) {
                val cp = text.codePointBefore(target)
                target -= Character.charCount(cp)
                left--
            }
        }
        return target
    }
}
