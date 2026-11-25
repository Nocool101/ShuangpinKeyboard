package com.shuangpin.ime

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shuangpin.engine.Candidate
import com.shuangpin.engine.CandidateKind

/**
 * 选字面板：候选栏右端的 **⌄** 展开出来的那一屏（参考手机自带输入法的「选词界面」）。
 *
 * ```
 *  nihao                          [全部][单字]   ▲
 *  ┌────────┬───────────────────────────────┐
 *  │ ni     │ 你  好  呢  尼 …              │  ← 当前音节的候选字
 *  │ hao    │                               │
 *  └────────┴───────────────────────────────┘
 * ```
 *
 * - **左边**是这一轮拼音的**音节**，点一下就切到那个音节——
 *   这样能选到**前面的**音节（「从前选择」），不用先把字打出来再改。
 * - **右边**：「全部」页是整条候选；「单字」页是当前音节的候选字。
 * - 「单字」页选一个字就**自动前进到下一个音节**；退格键**退回上一个音节**。
 * - 全部音节都选完 → 上屏拼出来的整句。
 */
class CandidatePanel(context: Context) : LinearLayout(context) {

    interface Listener {
        /** 「全部」页选了整条候选（要上屏）。 */
        fun onPickWhole(item: Candidate)

        /** 「单字」页选了某个音节的字。 */
        fun onPickChar(segmentIndex: Int, ch: String)

        /** 点了左边的音节。 */
        fun onSelectSegment(segmentIndex: Int)

        /** 按了退格键：退回上一个音节。 */
        fun onBack()

        /** 点 ▲ 收起面板。 */
        fun onCollapse()
    }

    var listener: Listener? = null

    /** 现在是「全部」页还是「单字」页。 */
    var charMode: Boolean = false

    /** 这一轮拼音的音节（左边那一列）。 */
    var segments: List<String> = emptyList()

    /** 每个音节选中的字（null = 还没选）。 */
    var picked: MutableList<String?> = mutableListOf()

    /** 当前在第几个音节。 */
    var segmentIndex: Int = 0

    /** 「全部」页的候选。 */
    var wholeItems: List<Candidate> = emptyList()

    /** 「单字」页的候选字（当前音节的）。 */
    var charItems: List<String> = emptyList()

    private val pinyinLabel = TextView(context)
    private val allTab = TextView(context)
    private val charTab = TextView(context)
    private val segmentBox = LinearLayout(context)
    private val itemBox = LinearLayout(context)
    private val itemScroll = ScrollView(context)

    /** 左边那一列（音节）的滚动容器。 */
    private var segmentScroll: ScrollView? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(COL_BG)

        // ---------------- 顶栏：拼音 + [全部][单字] + ▲ ----------------
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f).toInt(), 0, dp(4f).toInt(), 0)
        }
        pinyinLabel.apply {
            textSize = 14f
            setTextColor(COL_MUTED)
            // 拼音列表要**看得全**。
            //
            // 以前是「单行 + 省略号」：拼音一长就被截掉（用户反馈
            // 「全键盘模式下打完拼音，点击向下箭头旁边的拼音列表拼音显示不全」，
            // 单手模式输入得多了也一样）。
            // 现在允许折行，标题栏的高度跟着内容长——
            // 面板是独立的一屏（跟键盘同高），长一点不影响键位位置。
            maxLines = 4
            ellipsize = null
        }
        header.addView(
            pinyinLabel,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
        )
        allTab.apply {
            text = "全部"
            textSize = 13f
            setTextColor(COL_SUB)
            gravity = Gravity.CENTER
            setPadding(dp(12f).toInt(), dp(4f).toInt(), dp(12f).toInt(), dp(4f).toInt())
            setOnClickListener {
                charMode = false
                render()
            }
        }
        charTab.apply {
            text = "单字"
            textSize = 13f
            setTextColor(COL_SUB)
            gravity = Gravity.CENTER
            setPadding(dp(12f).toInt(), dp(4f).toInt(), dp(12f).toInt(), dp(4f).toInt())
            setOnClickListener {
                charMode = true
                render()
            }
        }
        val collapse = TextView(context).apply {
            text = "▲"
            textSize = 16f
            setTextColor(COL_SUB)
            gravity = Gravity.CENTER
            setPadding(dp(10f).toInt(), 0, dp(10f).toInt(), 0)
            setOnClickListener { listener?.onCollapse() }
        }
        header.addView(allTab, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        header.addView(charTab, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        header.addView(collapse, LayoutParams(dp(34f).toInt(), LayoutParams.MATCH_PARENT))
        // 高度**跟着内容**：拼音多的时候要折行（见上面 pinyinLabel 的说明），
        // 写死 30 dp 的话折出来的第二行会被切掉。
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ---------------- 主体：左边音节 + 右边候选 ----------------
        val body = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }
        segmentBox.apply {
            orientation = VERTICAL
            setBackgroundColor(COL_SEG_BG)
        }
        // 音节多的时候这一列要能**滚**。
        //
        // 以前每个音节按权重各占 1/n 的高度：音节一多，每格就只剩十几 dp，
        // 文字被压扁、显示不全（用户反馈「输入的过多，拼音显示不全」）。
        // 现在每格按内容高度排，列本身可以上下滑——音节再多也看得见。
        segmentScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        segmentScroll?.addView(segmentBox)
        body.addView(
            segmentScroll,
            LayoutParams(dp(64f).toInt(), LayoutParams.MATCH_PARENT),
        )
        itemScroll.apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            // 边缘不要光晕/拉伸效果——面板里的滚动要「到边就停」。
            overScrollMode = View.OVER_SCROLL_NEVER
            // **自己把滚动位置钳住**。
            //
            // 框架 ScrollView 允许内容被拖过边界（over-scroll）：
            // 滑到最后一行再继续拖，内容会被推出视口——
            // 整块列表看上去就是「一片空白」，而且不会自己弹回来
            // （用户反馈「滑到最后一行还能继续下滑，导致空白」）。
            //
            // 这里在滚动回调里把位置限制在 [0, 内容高 - 视口高]，
            // 越界立刻拉回来，最后一行永远看得见。
            setOnScrollChangeListener { v, _, scrollY, _, _ ->
                val child = (v as? ViewGroup)?.getChildAt(0) ?: return@setOnScrollChangeListener
                // 还没量出高度就不管（布局过程中钳会打架）
                if (child.height <= 0) return@setOnScrollChangeListener
                val max = (child.height - v.height).coerceAtLeast(0)
                val clamped = scrollY.coerceIn(0, max)
                if (clamped != scrollY) v.scrollTo(v.scrollX, clamped)
            }
        }
        itemScroll.addView(itemBox)
        body.addView(
            itemScroll,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // ---------------- 底栏：退格（退回上一个音节） ----------------
        //
        // 面板把键盘挡住了，键盘上的退格键点不到——
        // 退格得在面板里自己有一个（跟系统输入法的选词界面一样）。
        val footer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        footer.addView(
            TextView(context).apply {
                text = "⌫"
                textSize = 20f
                setTextColor(COL_SUB)
                gravity = Gravity.CENTER
                setBackgroundColor(COL_SEG_BG)
                setOnClickListener { listener?.onBack() }
                contentDescription = "退回上一个音节"
            },
            LayoutParams(dp(48f).toInt(), LayoutParams.MATCH_PARENT),
        )
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, dp(36f).toInt()))
    }

    /** 重新画一遍（数据变了就调它）。 */
    fun render() {
        renderHeader()
        renderSegments()
        renderItems()
    }

    private fun renderHeader() {
        // 顶栏：左边是这一轮拼音，右边带出已经选好的字
        val chosen = picked.mapIndexed { i, c ->
            if (c != null) segments.getOrNull(i).orEmpty() + "→" + c else segments.getOrNull(i).orEmpty()
        }.joinToString(" ")
        pinyinLabel.text = if (chosen.isBlank()) "" else chosen
        allTab.setTextColor(if (charMode) COL_SUB else COL_ACCENT)
        charTab.setTextColor(if (charMode) COL_ACCENT else COL_SUB)
    }

    private fun renderSegments() {
        segmentBox.removeAllViews()
        segments.forEachIndexed { i, seg ->
            val pickedChar = picked.getOrNull(i)
            val tv = TextView(context).apply {
                text = if (pickedChar != null) "$seg\n$pickedChar" else seg
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (i == segmentIndex) COL_ACCENT else COL_SUB)
                setPadding(0, dp(6f).toInt(), 0, dp(6f).toInt())
                // 音节按内容高度排（不按权重瓜分高度）——
                // 音节一多，每格只剩十几 dp，文字就显示不全了。
                minHeight = dp(30f).toInt()
                setOnClickListener { listener?.onSelectSegment(i) }
            }
            segmentBox.addView(
                tv,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
        // 换音节了就把左列滚回最上面（选中的那个露出来）
        segmentScroll?.scrollTo(0, 0)
    }

    /** 上一次画出来的候选内容签名。内容没变就不重建。 */
    private var lastItemsSig: String? = null

    private fun renderItems() {
        // 「单字」页 = 当前音节的候选字；「全部」页 = 整条候选
        val cells: List<Pair<String, () -> Unit>> = if (charMode) {
            charItems.map { ch -> ch to { listener?.onPickChar(segmentIndex, ch) } }
        } else {
            wholeItems.map { item -> item.text to { listener?.onPickWhole(item) } }
        }

        // 内容没变就别重建：字表扩了之后一个音节能有几百个字，
        // 每切一个音节就重建几百个 TextView，那一下卡顿是能感觉到的。
        val sig = (if (charMode) "c" else "w") + "|" + segmentIndex + "|" +
            cells.joinToString("") { it.first }
        if (sig == lastItemsSig) return
        lastItemsSig = sig

        itemBox.removeAllViews()
        itemBox.orientation = VERTICAL
        // 内容换了（切音节 / 换页签）→ 列表回到最上面。
        //
        // 不复位的话：用户在「ni」翻到第 3 页挑了个字，
        // 切到「hao」时看到的还是第 3 页——高频字全不在眼前了
        // （用户反馈「选了第一个拼音的字后选第二个时不在第一页高频字里」）。
        itemScroll.scrollTo(0, 0)

        if (cells.isEmpty()) {
            itemBox.addView(hintView(if (charMode) "这个音节没有候选字" else "没有候选"))
            return
        }

        // 字多的时候先说一声有几个字、可以滑——
        // 不然用户只看到眼前这几个，会以为「就这些字」了。
        if (charMode && cells.size > HINT_THRESHOLD) {
            itemBox.addView(hintView("共 ${cells.size} 个字，往上滑看更多"))
        }

        // 每行 3 个（跟系统输入法的选词界面一样），竖着滚
        for (i in cells.indices step 3) {            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (j in i until minOf(i + 3, cells.size)) {
                val (text, action) = cells[j]
                row.addView(
                    TextView(context).apply {
                        this.text = text
                        textSize = if (charMode) 20f else 18f
                        setTextColor(COL_INK)
                        gravity = Gravity.CENTER
                        setPadding(dp(10f).toInt(), dp(8f).toInt(), dp(10f).toInt(), dp(8f).toInt())
                        setOnClickListener { action() }
                    },
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            itemBox.addView(
                row,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private fun hintView(text: String) = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(COL_MUTED)
        setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        const val COL_BG = 0xFFE9EBF0.toInt()
        const val COL_SEG_BG = 0xFFE1E4EA.toInt()
        const val COL_INK = 0xFF171B23.toInt()
        const val COL_SUB = 0xFF5B6472.toInt()
        const val COL_MUTED = 0xFF98A1B0.toInt()
        const val COL_ACCENT = 0xFF2F6DF6.toInt()

        /** 超过这么多字才提示「可以往上滑」（一屏大约放得下 9 个）。 */
        const val HINT_THRESHOLD = 12
    }
}
