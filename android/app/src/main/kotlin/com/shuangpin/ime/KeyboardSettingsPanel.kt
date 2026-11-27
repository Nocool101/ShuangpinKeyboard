package com.shuangpin.ime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * 键盘上的「**设置**」浮层。
 *
 * 入口是键盘顶部那颗 **⚙ 设置键**——
 * 它在**候选区左边**，单手模式下正好**正对常用标点竖列的顶端**
 * （跟系统输入法把工具键放在那一列上面是一个道理）。
 *
 * 之前改键盘设置只有「系统设置 → 语言和输入法 → 键盘 → 设置」那条很深的路，
 * 十个人九个找不到；现在在键盘上点一下就能改。
 *
 * 这一屏能改两件事：
 *
 * | 分区 | 能改什么 |
 * |---|---|
 * | **键盘布局** | 全键盘 / 单手·右手 / 单手·左手，以及韵母位的排法 |
 * | **键盘大小** | 高度、宽度都能自由拖（单手模式才有用宽度） |
 *
 * 跟系统输入法的「键盘调节」是一个意思：改完**立刻生效**，不用重启输入法。
 */
class KeyboardSettingsPanel(context: Context) : FrameLayout(context) {

    interface Listener {
        /** 选了新的键盘形态（`full` / `right` / `left`）。 */
        fun onLayoutChoice(value: String)

        /** 选了新的韵母位排法（`fixed` / `thumb`）。 */
        fun onFinalArrangeChoice(value: String)

        /** 键盘高度倍数变了（1.0 = 标准高度）。 */
        fun onHeightScale(scale: Float)

        /** 单手模式键盘宽度比例变了（0.8 = 标准宽度）。 */
        fun onWidthRatio(ratio: Float)

        /** 「重置」：全部回到默认值。 */
        fun onReset()

        /** 关掉这一屏。 */
        fun onClose()
    }

    var listener: Listener? = null

    // ---- 当前值（弹出来的时候按设置填进去）----

    private var layoutValue: String = ImeSettings.KEY_LAYOUT_RIGHT
    private var finalValue: String = ImeSettings.FINAL_ARRANGE_FIXED
    private var heightScale: Float = ImeSettings.DEFAULT_KB_HEIGHT_SCALE
    private var widthRatio: Float = ImeSettings.DEFAULT_KB_WIDTH_RATIO

    private val layoutChips = mutableMapOf<String, TextView>()
    private val finalChips = mutableMapOf<String, TextView>()
    private lateinit var heightValue: TextView
    private lateinit var widthValue: TextView
    private lateinit var heightBar: SeekBar
    private lateinit var widthBar: SeekBar

    init {
        // 半透明遮罩：把底下的键盘压暗，这一屏才浮得起来。
        setBackgroundColor(0xB3000000.toInt())
        // 遮罩必须吃掉点击——不然用户调设置的时候，按到的是底下的键盘键。
        isClickable = true
        setOnClickListener { /* 吃掉 */ }

        val scroll = ScrollView(context).apply {
            // 面板比键盘高的时候能滚，不至于把「重置/完成」挤出屏幕。
            // ⚠ **不要 fillViewport**：那会把卡片拉成跟键盘一样高，
            // 白底铺满整个键盘，看着像键盘变成一张白纸。
            isFillViewport = false
            clipToPadding = false
        }
        val card = buildCard()
        scroll.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).also {
                it.leftMargin = dp(8f).toInt()
                it.rightMargin = dp(8f).toInt()
                it.topMargin = dp(3f).toInt()
            },
        )
        addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /**
     * 按当前设置刷新这一屏（弹出来的时候调一次）。
     *
     * 滑杆的位置也跟着设置走——上次调到 60% 的高度，这次打开还是 60%。
     */
    fun bind(
        layout: String,
        finalArrange: String,
        height: Float,
        width: Float,
    ) {
        layoutValue = layout
        finalValue = finalArrange
        heightScale = height.coerceIn(ImeSettings.MIN_KB_HEIGHT_SCALE, ImeSettings.MAX_KB_HEIGHT_SCALE)
        widthRatio = width.coerceIn(ImeSettings.MIN_KB_WIDTH_RATIO, ImeSettings.MAX_KB_WIDTH_RATIO)
        heightBar.progress = heightPct(heightScale)
        widthBar.progress = widthPct(widthRatio)
        heightValue.text = "${(heightScale * 100).toInt()}%"
        widthValue.text = "${(widthRatio * 100).toInt()}%"
        refreshChips()
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private fun buildCard(): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f).toInt(), dp(6f).toInt(), dp(12f).toInt(), dp(6f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(14f)
                setColor(Color.WHITE)
            }
        }

        // ---- 标题行 ----
        val title = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(
            TextView(context).apply {
                text = "键盘设置"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(COLOR_INK)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        title.addView(
            TextView(context).apply {
                text = "✕"
                textSize = 17f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
                contentDescription = "关闭"
                setOnClickListener { listener?.onClose() }
            },
            LinearLayout.LayoutParams(dp(30f).toInt(), dp(26f).toInt()),
        )
        box.addView(title)

        // ---- 左右两栏 ----
        //
        // ⚠ **必须压进键盘那一块高度里**（216 dp）：
        // 面板是浮在键盘上面的，超出部分会被屏幕底边切掉——
        // 滑杆和「完成」按钮是最不能被切的东西。
        // 所以左边放「布局」那几颗按钮，右边放「大小」两根滑杆。
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        // ---- 左栏：键盘布局 ----
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        left.addView(sectionLabel("键盘布局"))
        val layoutOptions = listOf(
            "全键盘" to ImeSettings.KEY_LAYOUT_FULL,
            "单手·右手" to ImeSettings.KEY_LAYOUT_RIGHT,
            "单手·左手" to ImeSettings.KEY_LAYOUT_LEFT,
        )
        // 一行放不下三颗（「单手·右手」太宽），拆成两行
        for (chunk in layoutOptions.chunked(2)) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for ((label, value) in chunk) {
                val chip = makeChip(label) {
                    layoutValue = value
                    refreshChips()
                    listener?.onLayoutChoice(value)
                }
                layoutChips[value] = chip
                row.addView(chip)
            }
            left.addView(row)
        }

        // ---- 左栏：韵母位排法 ----
        left.addView(sectionLabel("韵母位排法"))
        val finalRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val finalOptions = listOf(
            "固定小鹤" to ImeSettings.FINAL_ARRANGE_FIXED,
            "拇指优先" to ImeSettings.FINAL_ARRANGE_THUMB,
        )
        for ((label, value) in finalOptions) {
            val chip = makeChip(label) {
                finalValue = value
                refreshChips()
                listener?.onFinalArrangeChoice(value)
            }
            finalChips[value] = chip
            finalRow.addView(chip)
        }
        left.addView(finalRow)

        body.addView(
            left,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.05f),
        )

        // ---- 右栏：键盘大小 ----
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f).toInt(), 0, 0, 0)
        }
        right.addView(sectionLabel("键盘大小"))

        heightValue = TextView(context).apply {
            textSize = 12f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(COLOR_MUTED)
        }
        heightBar = makeSeekBar(
            max = ((ImeSettings.MAX_KB_HEIGHT_SCALE - ImeSettings.MIN_KB_HEIGHT_SCALE) * 100).toInt(),
        ) { pct ->
            val scale = ImeSettings.MIN_KB_HEIGHT_SCALE + pct / 100f
            heightScale = scale
            heightValue.text = "${(scale * 100).toInt()}%"
            listener?.onHeightScale(scale)
        }
        right.addView(sliderRow("高度", heightBar, heightValue))

        widthValue = TextView(context).apply {
            textSize = 12f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(COLOR_MUTED)
        }
        widthBar = makeSeekBar(
            max = ((ImeSettings.MAX_KB_WIDTH_RATIO - ImeSettings.MIN_KB_WIDTH_RATIO) * 100).toInt(),
        ) { pct ->
            val ratio = ImeSettings.MIN_KB_WIDTH_RATIO + pct / 100f
            widthRatio = ratio
            widthValue.text = "${(ratio * 100).toInt()}%"
            listener?.onWidthRatio(ratio)
        }
        right.addView(sliderRow("宽度", widthBar, widthValue))

        // ---- 右栏：重置 / 完成 ----
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottom.addView(
            makeButton("重置") {
                layoutValue = ImeSettings.KEY_LAYOUT_RIGHT
                finalValue = ImeSettings.FINAL_ARRANGE_FIXED
                heightScale = ImeSettings.DEFAULT_KB_HEIGHT_SCALE
                widthRatio = ImeSettings.DEFAULT_KB_WIDTH_RATIO
                heightBar.progress = heightPct(heightScale)
                widthBar.progress = widthPct(widthRatio)
                heightValue.text = "${(heightScale * 100).toInt()}%"
                widthValue.text = "${(widthRatio * 100).toInt()}%"
                refreshChips()
                listener?.onReset()
            },
            LinearLayout.LayoutParams(0, dp(34f).toInt(), 1f),
        )
        bottom.addView(
            makeButton("完成", primary = true) { listener?.onClose() },
            LinearLayout.LayoutParams(0, dp(34f).toInt(), 1f).also {
                it.leftMargin = dp(8f).toInt()
            },
        )
        right.addView(
            bottom,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(6f).toInt() },
        )

        body.addView(
            right,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        box.addView(body)

        return box
    }

    private fun sectionLabel(text: String): View = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(COLOR_MUTED)
        setPadding(0, dp(4f).toInt(), 0, dp(2f).toInt())
    }

    /**
     * 一项滑杆：**上面一行是小标签 + 数值，下面是滑杆**。
     *
     * 滑杆单独占一行才拖得动——挤在标签右边那一行里，
     * 手指想拖滑块很容易按到旁边的标签上。
     */
    private fun sliderRow(label: String, bar: SeekBar, value: View): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(context).apply {
                text = label
                textSize = 13f
                setTextColor(COLOR_INK)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(
            value,
            LinearLayout.LayoutParams(dp(42f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        col.addView(head)
        col.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(24f).toInt(),
            ),
        )
        return col
    }

    private fun makeSeekBar(max: Int, onChange: (Int) -> Unit): SeekBar = SeekBar(context).apply {
        this.max = max
        // 系统默认的滑杆左右留白太宽，实际能拖的轨道就剩一小截。
        val pad = dp(2f).toInt()
        setPadding(pad, 0, pad, 0)
        progressTintList = ColorStateList.valueOf(COLOR_ACCENT)
        thumbTintList = ColorStateList.valueOf(COLOR_ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(0xFFC8CDD8.toInt())
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                onChange(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun makeChip(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        includeFontPadding = false
        // ⚠ **必须单行**：列宽有限，文字一换行芯片就变成两行高，
        // 整个面板就被撑出屏幕底边了。
        isSingleLine = true
        setTextColor(COLOR_INK)
        setPadding(dp(9f).toInt(), dp(5f).toInt(), dp(9f).toInt(), dp(5f).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).also { it.rightMargin = dp(6f).toInt() }
        setOnClickListener { onClick() }
    }

    private fun makeButton(text: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else COLOR_INK)
            background = GradientDrawable().apply {
                cornerRadius = dp(9f)
                setColor(if (primary) COLOR_ACCENT else 0xFFEDEFF3.toInt())
            }
            setOnClickListener { onClick() }
        }

    /** 把「选中的」那几颗点成蓝底白字，其余是灰底黑字。 */
    private fun refreshChips() {
        for ((value, chip) in layoutChips) {
            val on = value == layoutValue
            chip.setTextColor(if (on) Color.WHITE else COLOR_INK)
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(8f)
                setColor(if (on) COLOR_ACCENT else 0xFFEDEFF3.toInt())
            }
        }
        for ((value, chip) in finalChips) {
            val on = value == finalValue
            chip.setTextColor(if (on) Color.WHITE else COLOR_INK)
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(8f)
                setColor(if (on) COLOR_ACCENT else 0xFFEDEFF3.toInt())
            }
        }
    }

    // ------------------------------------------------------------------

    private fun heightPct(scale: Float): Int =
        ((scale - ImeSettings.MIN_KB_HEIGHT_SCALE) * 100).toInt()

    private fun widthPct(ratio: Float): Int =
        ((ratio - ImeSettings.MIN_KB_WIDTH_RATIO) * 100).toInt()

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        private val COLOR_INK = 0xFF171B23.toInt()
        private val COLOR_MUTED = 0xFF6B7280.toInt()
        private val COLOR_ACCENT = 0xFF2F6DF6.toInt()
    }
}
