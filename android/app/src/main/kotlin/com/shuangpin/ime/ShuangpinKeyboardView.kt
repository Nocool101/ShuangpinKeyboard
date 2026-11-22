package com.shuangpin.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.shuangpin.engine.Display
import com.shuangpin.engine.FinalLayout
import com.shuangpin.engine.InputSession
import com.shuangpin.engine.InputStage
import com.shuangpin.engine.KeyFace
import com.shuangpin.engine.KeyboardFaces
import com.shuangpin.engine.PinyinEngine
import com.shuangpin.engine.ZERO_KEYS
import kotlin.math.abs

/**
 * 双层标签键盘。
 *
 * 这是整个方案的核心交互：**26 个物理键常驻，按下声母后只换标签、不换布局**。
 *
 * - 声母位：主标签是声母（zh/ch/sh 直接写在键面上），副标签是该键承载的常用韵母
 * - 韵母位：主标签变成**唯一确定**的韵母，无效键整体变灰
 * - 因为声母已经确定，双拼里那 9 个天然歧义键（k/s/x/l/o/v/y/t/r）
 *   在这里被引擎预先消解掉，用户按下的一定是对的
 *
 * **每个键的左上角始终标着键位字母。**
 * 这是必要的：韵母位的主标签是韵母（「uo」而不是「o」），
 * 如果不把字母标出来，用户根本找不到该按哪个键——字母只在键底当小字的话几乎看不见。
 *
 * 另外提供三套非中文布局：26 键英文、数字、标点。
 */
class ShuangpinKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 键盘布局模式。 */
    enum class Mode {
        /** 中文双拼 */
        CHINESE,

        /** 26 键英文 */
        ENGLISH,

        /** 数字 */
        NUMBER,

        /** 标点 */
        PUNCTUATION,
    }

    /**
     * 键盘形态。
     *
     * - [FULL]：横跨整个屏宽，两只手都能用
     * - [RIGHT]：**单手·右手**——字母区收窄到右侧，拇指够得到
     * - [LEFT]：**单手·左手**——字母区收窄到左侧
     *
     * **字母的相对位置一律不变**——双拼的位置记忆绑定在字母键位上
     * （小鹤 zh→v、ch→i、sh→u），打乱了就废。
     * 单手模式只改「整块键盘摆在哪儿、每行多宽」。
     */
    enum class Layout {
        FULL,
        RIGHT,
        LEFT,
    }

    /**
     * 韵母位的排法。
     *
     * - [FIXED]（默认）：**小鹤双拼标准布局**——每个韵母永远在它那个键上
     *   （ao 永远在 c、ian 永远在 m、uang 永远在 l……）。
     *   位置记忆绑在键上，打熟了不用看。
     * - [THUMB]：单手模式下把韵母聚到拇指那一侧（按常用度重排）。
     *   够得着是够着了，但**每换一个声母键位就换一套**——
     *   用户反馈「目前的布局不容易记忆」，所以它不再是默认。
     */
    enum class FinalArrange {
        FIXED,
        THUMB,
    }

    interface Listener {
        /** 双拼字母键（中文模式） */
        fun onPinyinKey(key: String)

        /** 原始字符（英文/数字/标点模式） */
        fun onRawChar(text: String)

        fun onSpace()
        fun onBackspace()

        /**
         * **动作键**（右下角那个蓝的）。
         *
         * 它**不做「选词上屏」**——上屏只有空格一个入口。
         * 它做的是「把当前这轮输入收尾，然后执行这个宿主该有的动作」：
         *
         * | 宿主 | 键面 | 行为 |
         * |---|---|---|
         * | 微信等聊天工具 | 发送 | 发出这条消息 |
         * | 多行文本框 | 换行 | 换到下一行 |
         * | 浏览器地址栏 | 前往 | 搜索 / 打开网址 |
         * | 普通单行输入框 | 完成 | 收尾这一轮输入 |
         */
        fun onAction() {}

        /** 退格键上滑：一次退一格（先退最后输入的音节，再往前退，最后退已上屏的文字） */
        fun onSwipeUpDelete()

        /** 退格键下滑：撤销 */
        fun onUndo()

        /**
         * 在键盘输入区（字母区）**横向滑动**：移动候选选择。
         *
         * 方向跟选中的候选在候选栏里的移动方向一致：
         * **手指往右 = 往后翻（往右边的候选走）**，往左 = 往前翻。
         *
         * @param steps 位移步数（正 = 往后，负 = 往前）
         */
        fun onCandidateSwipe(steps: Int) {}

        /** 切换布局 */
        fun onModeChanged(mode: Mode)

        /** 英文模式的大小写切换 */
        fun onCapsChanged(upper: Boolean)

        /**
         * 空格键按下。
         *
         * 返回 `true` 表示**这一下交给语音**（按住说话），
         * 空格本身不上屏；返回 `false` 就是普通的空格。
         *
         * 什么时候该返回 true：语音功能开着、接口配好了、而且**当前没有待上屏的输入**
         * （用户正在打拼音的时候，空格是「上屏」，不能拿去说话）。
         */
        fun onSpaceDown(): Boolean = false

        /** 按住空格超过阈值，语音开始。 */
        fun onVoiceStart() {}

        /** 松手，语音结束。 */
        fun onVoiceStop() {}

        /** 手势被打断（手指滑走了、窗口没了）。 */
        fun onVoiceCancel() {}
    }

    var listener: Listener? = null

    /** 当前布局。 */
    var mode: Mode = Mode.CHINESE
        set(value) {
            if (field != value) {
                field = value
                layoutKeys()
                invalidate()
            }
        }

    /**
     * 进数字/标点面板**之前**的那个文字面板。
     *
     * 数字面板的「返回」键要回到它：从英文进的回英文，从中文进的回中文。
     */
    private var previousTextMode: Mode = Mode.CHINESE

    /**
     * 键盘形态。默认**单手右手**。
     *
     * 设在设置页里（常驻生效，不在键盘上放切换键——
     * 底栏每个像素都是手指要够的地方）。
     */
    var layout: Layout = Layout.RIGHT
        set(value) {
            if (field != value) {
                field = value
                layoutKeys()
                invalidate()
            }
        }

    /** 英文模式是否大写。 */
    var upperCase: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 空格键的文案。**固定是「空格」**——不跟着编辑框的 action 变。 */
    var spaceLabel: String = "空格"
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * 韵母位的排法。默认**小鹤双拼固定布局**（见 [FinalArrange]）。
     */
    var finalArrange: FinalArrange = FinalArrange.FIXED
        set(value) {
            if (field != value) {
                field = value
                layoutKeys()
                invalidate()
            }
        }

    /**
     * 动作键的文案，随编辑框的上下文变化（发送 / 换行 / 前往 / 完成）。
     */
    var actionLabel: String = "完成"
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var session: InputSession? = null
    private var engine: PinyinEngine? = null

    private val keys = mutableListOf<Key>()
    private var pressed: Key? = null

    // 退格键滑动手势
    private var swipeKey: Key? = null
    private var downX = 0f
    private var downY = 0f
    private var swiped = false

    // 候选选择滑动（需求：滑动键盘输入区可以选候选区文字）
    //
    // 这一下手势**只从字母区起**——底栏是功能键（空格/退格/确认），
    // 侧边那条是语音键，都有自己的手势，不能抢。
    private var swipeEligible = false
    private var reportedSteps = 0
    private var horizontalSwiped = false

    // 长按退格连删
    private val handler = Handler(Looper.getMainLooper())
    private var repeating = false

    private val repeatDelete = object : Runnable {
        override fun run() {
            if (!repeating) return
            listener?.onBackspace()
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    // ---- 按住空格说话 ----
    //
    // 按下空格先「装填」：如果服务层说这一下可以拿去说话，
    // 就等一小会儿；用户没松手就开始录音，松手就结束。
    // 快速点一下（没到阈值）还是照常打空格。
    //
    // ⚠ **「装填」之前先确认手指没动**：空格键上横滑是「选候选」，
    // 手指一动就取消装填——不然用户想滑候选，结果先把录音开了。
    private var voiceArmed = false
    private var voiceStarted = false

    private val voiceHoldStarter = object : Runnable {
        override fun run() {
            // 到点了：这时手指还没动过（动过就早取消了），问服务层能不能说话
            voiceArmed = listener?.onSpaceDown() ?: false
            if (!voiceArmed) return
            voiceStarted = true
            listener?.onVoiceStart()
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** 候选滑动的触发阈值（px）：横向移动超过它就算「滑动」，不是「点歪了」。 */
    private fun swipeThresholdPx(): Float = dp(CANDIDATE_SWIPE_THRESHOLD_DP)

    /** 候选滑动一步的距离（px）：一步 = 挪一个候选。 */
    private fun swipeStepPx(): Float = dp(CANDIDATE_SWIPE_STEP_DP)

    /**
     * 横向滑动了多远 = 挪几个候选。
     *
     * **头一步按触发阈值算**：滑过阈值就至少挪一个。
     * 以前一步要 44 dp（154 px），而阈值只有 12 dp（42 px）——
     * 结果是「滑了但没动」：42~153 px 的滑动被判定成滑动，却一步都不挪，
     * 用户以为没触发，只好一遍遍滑。
     * 后面每再滑 44 dp 多挪一个。
     *
     * ⚠ **方向必须带上**：手指往右 = 往后翻（正数），往左 = 往前翻（负数）。
     * 以前这里取的是 `abs(dx)`，方向整个丢了——**往左滑也会往后翻**，
     * 来回滑几下方向就乱了（用户反馈「滑动选词不跟手，往右滑选词却往左」，
     * 尤其在「滑到下一页之后」和「多次左右滑动之后」）。
     */
    private fun candidateSteps(dx: Float): Int {
        val threshold = swipeThresholdPx()
        val d = abs(dx)
        if (d < threshold) return 0
        val steps = 1 + ((d - threshold) / swipeStepPx()).toInt()
        return if (dx > 0) steps else -steps
    }

    // ---- 画笔 ----
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private data class Key(
        val id: String,
        val big: String,
        val sub: String?,
        val badge: String?,
        val rect: RectF,
        val kind: Kind,
    )

    private enum class Kind { PINYIN, RAW, SPACE, BACKSPACE, MODE, ACTION }

    fun bind(session: InputSession, engine: PinyinEngine) {
        this.session = session
        this.engine = engine
        laidOutStage = session.stage
        layoutKeys()
        invalidate()
    }

    /**
     * 会话阶段变了就重排键位。
     *
     * 韵母位在「拇指优先」模式下要重排（把韵母聚到拇指那一侧），
     * 所以阶段一变必须重新 layout——只 invalidate 是不够的。
     */
    fun syncSession() {
        val stage = session?.stage
        if (stage != laidOutStage) {
            laidOutStage = stage
            layoutKeys()
        }
        invalidate()
    }

    /** 上一次排键位时的会话阶段（变了就要重排）。 */
    private var laidOutStage: InputStage? = null

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    private fun layoutKeys() {
        keys.clear()
        if (width <= 0 || height <= 0) return

        val pad = dp(3f)
        val gap = dp(4f)
        val rowGap = dp(6f)
        val oneHand = layout != Layout.FULL

        // 字母区（和底栏）占的横向范围。
        // 单手模式只占屏宽的一部分，**贴着拇指那一侧**——
        // 拇指的支点在手机右下角，键盘越靠右越好够。
        blockWidth = if (oneHand) width * ONE_HAND_WIDTH_RATIO else width.toFloat()
        blockLeft = if (layout == Layout.RIGHT) width - blockWidth else 0f

        // 单手模式底栏矮一点，把高度让给字母键——
        // 键变窄了，高度得补回来，点击面积才不至于缩水太多。
        val bottomH = dp(if (oneHand) 44f else 50f)
        val letterH = ((height - bottomH - rowGap * 3 - pad * 2) / 3f).coerceAtLeast(dp(34f))

        when (mode) {
            Mode.CHINESE -> layoutChinese(pad, gap, rowGap, letterH)
            Mode.ENGLISH -> layoutEnglish(pad, gap, rowGap, letterH)
            Mode.NUMBER -> layoutNumber(pad, gap, rowGap, letterH, bottomH)
            Mode.PUNCTUATION -> layoutPunctuation(pad, gap, rowGap, letterH)
        }

        // 单手模式：低频的 123 / 符 挪到留白区那一列，
        // 底栏就全留给「中/英 + 空格 + 退格」了。
        if (oneHand) layoutSideColumn(gap, bottomH)
        // 数字面板**不要底栏**——它右列自带 退格/空格/确认（跟参考图一样）
        if (mode != Mode.NUMBER) {
            layoutBottomBar(pad, gap, bottomH, oneHand)
        }
    }

    /** 字母区（和底栏）的横向范围。单手模式收窄到拇指那一侧。 */
    private var blockLeft = 0f
    private var blockWidth = 0f

    /** 三行字母，大小随行内键数自适应。 */
    private fun layoutRows(rows: List<List<Key>>, pad: Float, gap: Float, rowGap: Float, rowH: Float) {
        rows.forEachIndexed { ri, row ->
            val w = keyWidth(row.size)
            val rowW = row.size * w + (row.size - 1) * gap
            // 行在**字母区里**居中（不是整个屏宽居中）
            val x0 = blockLeft + (blockWidth - rowW) / 2f
            val y0 = pad + ri * (rowH + rowGap)
            row.forEachIndexed { i, key ->
                val left = x0 + i * (w + gap)
                keys += key.copy(rect = RectF(left, y0, left + w, y0 + rowH))
            }
        }
    }

    private fun keyWidth(count: Int): Float =
        (blockWidth - dp(6f) * 2 - dp(4f) * (count - 1)) / count

    /**
     * 单手模式：留白区里的「**常用标点**」竖排。
     *
     * 留白区只有 51 dp 宽，正好摆一列标点：
     * 以前这里是一个竖长的**语音键**，但语音已经有「按住空格说话」了，
     * 那个键就多余了（用户反馈「左侧语音按键改为常用标点」）。
     *
     * 从上到下排 ，。？！；： —— 点一下直接上屏。
     * 整个标点面板还在底栏的「符」键里（那边字更全）。
     *
     * 全键盘模式没有留白区，标点走底栏的「符」键。
     */
    private fun layoutSideColumn(gap: Float, bottomH: Float) {
        // 留白条有多宽取决于屏宽和 ONE_HAND_WIDTH_RATIO，
        // 不能写死 52dp——窄屏上会把这一列标点推到屏幕外（左侧按不到）。
        val stripLeft: Float
        val stripRight: Float
        if (layout == Layout.RIGHT) {
            stripLeft = 0f
            stripRight = (blockLeft - dp(4f)).coerceAtLeast(0f)
        } else {
            stripLeft = blockLeft + blockWidth + dp(4f)
            stripRight = width.toFloat()
        }
        val stripW = stripRight - stripLeft
        if (stripW < dp(30f)) return   // 太窄就干脆不放，别做一个按不到的键
        val w = stripW.coerceAtMost(dp(52f))
        val x = if (layout == Layout.RIGHT) stripRight - w else stripLeft
        // 竖长：从键盘顶一直到底，每个标点占一格
        val top = dp(3f)
        val h = height - top - dp(3f)
        val n = SIDE_PUNCT.size
        val kh = h / n
        SIDE_PUNCT.forEachIndexed { i, ch ->
            val y0 = top + i * kh
            keys += Key(ch, ch, null, null, RectF(x, y0, x + w, y0 + kh), Kind.RAW)
        }
    }

    private fun layoutBottomBar(pad: Float, gap: Float, bottomH: Float, oneHand: Boolean) {
        val y0 = height - bottomH - pad

        data class BottomKey(val id: String, val label: String, val kind: Kind)

        // 底栏参考系统输入法的常见排布：
        // 低频的「符号 / 123 / 中英」在左，**动作键在右下角**——
        // 那是右手拇指最舒服的位置，也是全键盘模式下动作键的位置。
        val items = when (mode) {
            Mode.CHINESE, Mode.ENGLISH -> listOf(
                BottomKey("mode_punct", "符", Kind.MODE),
                BottomKey("mode_number", "123", Kind.MODE),
                BottomKey("mode_english", "中/英", Kind.MODE),
                BottomKey("space", "空格", Kind.SPACE),
                BottomKey("back", "⌫", Kind.BACKSPACE),
                BottomKey("action", actionLabel, Kind.ACTION),
            )

            Mode.NUMBER -> listOf(
                BottomKey("mode_english", "abc", Kind.MODE),
                BottomKey("space", "空格", Kind.SPACE),
                BottomKey("back", "⌫", Kind.BACKSPACE),
                BottomKey("action", actionLabel, Kind.ACTION),
            )

            Mode.PUNCTUATION -> listOf(
                // 标点面板是**数字面板唯一的出口**（数字面板按参考图没有底栏），
                // 所以这里直接给「中文」，别绕到英文面板再切回来。
                BottomKey("mode_chinese", "中文", Kind.MODE),
                BottomKey("mode_number", "123", Kind.MODE),
                BottomKey("space", "空格", Kind.SPACE),
                BottomKey("back", "⌫", Kind.BACKSPACE),
                BottomKey("action", actionLabel, Kind.ACTION),
            )
        }

        // 宽度分配：空格要宽，但动作键不能太窄（它是常用键）
        val others = items.size - 1
        val avail = blockWidth - pad * 2 - gap * (items.size - 1)
        val otherW = dp(
            when {
                oneHand -> 42f
                items.size >= 6 -> 40f
                else -> 48f
            }
        )
        val actionW = dp(if (oneHand) 54f else 62f)
        // 动作键和退格键固定宽，其余（含空格）分剩下的
        val fixedOthers = others - 1   // 除动作键外的功能键个数
        val spaceW = (avail - otherW * fixedOthers - actionW).coerceAtLeast(dp(70f))

        var x = blockLeft + pad
        for (item in items) {
            val w = when (item.kind) {
                Kind.SPACE -> spaceW
                Kind.ACTION -> actionW
                else -> otherW
            }
            keys += Key(
                id = item.id,
                big = item.label,
                sub = null,
                badge = null,
                rect = RectF(x, y0, x + w, y0 + bottomH),
                kind = item.kind,
            )
            x += w + gap
        }
    }

    private fun layoutChinese(pad: Float, gap: Float, rowGap: Float, rowH: Float) {
        // 标签在**绘制时**才算（见 KeyboardFaces），
        // 这里只放键位和矩形——状态一变，键面立刻就换。
        val rows = chineseLetterRows().map { letters ->
            letters.map { k ->
                Key(
                    id = k,
                    big = k,
                    sub = null,
                    badge = k,
                    rect = RectF(),
                    kind = Kind.PINYIN,
                )
            }
        }
        layoutRows(rows, pad, gap, rowGap, rowH)
    }

    /**
     * 字母区的字母排布。
     *
     * - **声母位**：标准 QWERTY，一动都不动——
     *   双拼的位置记忆绑在字母键位上（小鹤 zh→v、ch→i、sh→u），打乱了就废。
     * - **韵母位 + 固定布局（默认）**：**也是标准 QWERTY，一动都不动**——
     *   这就是小鹤双拼的键盘布局：ao 永远在 c、ian 永远在 m、uang 永远在 l。
     *   键面标签换成「这个键在当前声母下的那个韵母」，位置不变。
     *   用户反馈「目前的布局不容易记忆」——重排过的那套每换一个声母就要重新认一遍，
     *   固定之后肌肉记忆才立得住。
     * - **韵母位 + 拇指优先（可选）**：把能按的韵母键**聚到拇指那一侧**
     *   （右手模式贴右，左手模式贴左，见 [FinalLayout]）。
     */
    private fun chineseLetterRows(): List<List<String>> {
        val s = session ?: return PINYIN_ROWS
        if (s.stage != InputStage.FINAL) return PINYIN_ROWS
        // 固定布局：位置跟声母位一模一样，什么都不用动
        if (finalArrange != FinalArrange.THUMB) return PINYIN_ROWS
        val valid = s.finals.keys.toList()
        if (valid.isEmpty()) return PINYIN_ROWS
        val hand = if (layout == Layout.RIGHT) FinalLayout.Hand.RIGHT else FinalLayout.Hand.LEFT
        return FinalLayout.pack(PINYIN_ROWS, valid, hand)
    }

    private fun layoutEnglish(pad: Float, gap: Float, rowGap: Float, rowH: Float) {
        val rows = ENGLISH_ROWS.map { letters ->
            letters.map { k ->
                Key(
                    id = k,
                    big = if (upperCase) k.uppercase() else k,
                    sub = null,
                    badge = null,
                    rect = RectF(),
                    kind = Kind.RAW,
                )
            }
        }
        layoutRows(rows, pad, gap, rowGap, rowH)
    }

    /**
     * 数字面板：按参考图的排法重做。
     *
     * ```
     *   · \ / : ; ： ( | ×          ← 符号顶行
     *   % │ 1   2   3 │ ⌫
     *   + │ 4   5   6 │ 空格
     *   − │ 7   8   9 │ 动作键（蓝，跨两行）
     *   * │ 返回 0  . │
     * 符号│           │
     * ```
     *
     * 跟字母面板那套「几行几个」不是一个排法，所以这里单独算。
     */
    private fun layoutNumber(pad: Float, gap: Float, rowGap: Float, rowH: Float, bottomH: Float) {
        // 顶行符号
        val topH = dp(20f)
        val topY = pad
        val syms = listOf("·", "\\", "/", ":", ";", "：", "(", "|", "×")
        val symW = (blockWidth - pad * 2 - gap * (syms.size - 1)) / syms.size
        syms.forEachIndexed { i, s ->
            val x = blockLeft + pad + i * (symW + gap)
            // **id 就是字符本身**：Kind.RAW 的键上屏的是 id，
            // 用 "sym_·" 这种内部 id 会把 "sym_·" 打进输入框。
            keys += Key(s, s, null, null, RectF(x, topY, x + symW, topY + topH), Kind.RAW)
        }

        // 数字区（**这一面板没有底栏**——右列自带 退格/空格/确认）
        val areaTop = topY + topH + rowGap
        val areaBottom = height - pad
        val areaH = areaBottom - areaTop

        val leftW = dp(48f)
        val rightW = dp(52f)
        val centerLeft = blockLeft + pad + leftW + gap
        val centerRight = blockLeft + blockWidth - pad - rightW - gap
        val colW = (centerRight - centerLeft - gap * 2) / 3f

        // 左列：% + − * 符号（5 格，比中区的 4 行密一点，跟参考图一致）
        val lh = (areaH - gap * 4) / 5f
        listOf("%", "+", "−", "*").forEachIndexed { i, s ->
            val y = areaTop + i * (lh + gap)
            keys += Key(s, s, null, null, RectF(blockLeft + pad, y, blockLeft + pad + leftW, y + lh), Kind.RAW)
        }
        val symY = areaTop + 4 * (lh + gap)
        keys += Key(
            "mode_punct", "符号", null, null,
            RectF(blockLeft + pad, symY, blockLeft + pad + leftW, areaBottom),
            Kind.MODE,
        )

        // 中间 3 列 × 4 行
        val rh = (areaH - gap * 3) / 4f
        val grid = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("返回", "0", "."),
        )
        grid.forEachIndexed { r, line ->
            line.forEachIndexed { c, s ->
                val x = centerLeft + c * (colW + gap)
                val y = areaTop + r * (rh + gap)
                if (s == "返回") {
                    // 「返回」= **回到文字输入键盘**（不是退格）。
                    // 之前它是个退格：键面写着「返回」却删字，越用越别扭。
                    // 这个面板自己的退格在**右列**（⌫），不冲突。
                    keys += Key("mode_back", "返回", null, null, RectF(x, y, x + colW, y + rh), Kind.MODE)
                } else {
                    // id = 数字本身，Kind.RAW 直接上屏 id（见上面顶行符号的说明）
                    keys += Key(s, s, null, null, RectF(x, y, x + colW, y + rh), Kind.RAW)
                }
            }
        }

        // 右列：退格 / 空格 / 确认（确认跨两行）
        val rx = centerRight + gap
        keys += Key("back", "⌫", null, null, RectF(rx, areaTop, rx + rightW, areaTop + rh), Kind.BACKSPACE)
        keys += Key(
            "space", "空格", null, null,
            RectF(rx, areaTop + rh + gap, rx + rightW, areaTop + 2 * rh + gap),
            Kind.SPACE,
        )
        keys += Key(
            "action", actionLabel, null, null,
            RectF(rx, areaTop + 2 * (rh + gap), rx + rightW, areaBottom),
            Kind.ACTION,
        )
    }

    private fun layoutPunctuation(pad: Float, gap: Float, rowGap: Float, rowH: Float) {
        val rows = PUNCT_ROWS.map { line ->
            line.map { k ->
                Key(k, k, null, null, RectF(), Kind.RAW)
            }
        }
        layoutRows(rows, pad, gap, rowGap, rowH)
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(COLOR_BG)
        if (layout != Layout.FULL) {
            // 单手模式：字母区跟留白区之间画一道淡线，
            // 让「键盘只占这一边」这件事一眼看得出来，不至于以为屏幕那边坏了。
            val edge = if (layout == Layout.RIGHT) blockLeft else blockWidth
            stroke.strokeWidth = dp(1f)
            stroke.color = COLOR_DEAD_BORDER
            canvas.drawLine(edge, 0f, edge, height.toFloat(), stroke)
        }
        for (key in keys) {
            when (key.kind) {
                Kind.PINYIN -> drawPinyin(canvas, key)
                else -> drawPlain(canvas, key)
            }
        }
    }

    private fun drawPinyin(canvas: Canvas, key: Key) {
        // 键面内容在**绘制时**按当前状态计算，状态一变键面立刻换
        val s = session
        val e = engine
        val face = if (s != null && e != null) {
            KeyboardFaces.forKey(e.scheme, s.stage, s.finals, key.id)
        } else {
            KeyFace(big = key.id, sub = null, badge = key.id, dead = false, accent = false)
        }

        fill.color = when {
            key === pressed -> COLOR_PRESSED
            face.dead -> COLOR_DEAD_BG
            else -> COLOR_KEY_BG
        }
        canvas.drawRoundRect(key.rect, dp(8f), dp(8f), fill)

        stroke.strokeWidth = dp(if (face.accent) 1.6f else 1f)
        stroke.color = when {
            face.accent -> COLOR_ACCENT
            face.dead -> COLOR_DEAD_BORDER
            else -> COLOR_KEY_BORDER
        }
        canvas.drawRoundRect(key.rect, dp(8f), dp(8f), stroke)

        // 键位字母角标：告诉用户「这个键上按的是哪个字母」。
        // 韵母位上主标签让给韵母了，角标就是唯一的找键线索，必须够大看得清。
        face.badge?.let { badge ->
            badgeText.color = if (face.dead) COLOR_DEAD_TEXT else COLOR_BADGE
            badgeText.textSize = dp(11.5f)
            canvas.drawText(badge, key.rect.left + dp(3f), key.rect.top + dp(11f), badgeText)
        }

        val sub = face.sub
        val hasSub = !sub.isNullOrEmpty()

        // 主标签：声母位是声母，韵母位是韵母
        bigText.color = if (face.dead) COLOR_DEAD_TEXT else COLOR_INK
        bigText.textSize = dp(if (face.big.length >= 3) 14f else 17f)
        // 太长就缩字号，避免超出键宽（"er/uan"、"iang/uang" 这类）
        val maxWidth = key.rect.width() - dp(6f)
        // 单手模式键窄，下限放低一点，不然「uang」这种缩到 9dp 还是装不下
        val narrow = key.rect.width() < dp(28f)
        val minSize = if (narrow) dp(7f) else dp(9f)
        // 窄键上「ing/uai」这种带斜杠的**主标签**（韵母位里无效键的提示）
        // 只留第一个——塞不下两个，糊到隔壁键上更糟。
        var bigShown = if (narrow) face.big.substringBefore('/') else face.big
        while (bigText.measureText(bigShown) > maxWidth && bigText.textSize > minSize) {
            bigText.textSize -= dp(0.5f)
        }
        val bigBaseline = key.rect.centerY() - dp(if (hasSub) 4f else 0f) -
            (bigText.descent() + bigText.ascent()) / 2f
        canvas.drawText(bigShown, key.rect.centerX(), bigBaseline, bigText)

        // 副标签（声母位：该键承载的常用韵母）
        if (hasSub) {
            // 键窄的时候只显示第一个韵母：「er/uan」→「er」。
            // 挤着显示两个在单手模式下根本看不清，倒不如只留常用的那个。
            var shown = if (narrow) sub.substringBefore('/') else sub
            smallText.color = if (face.dead) COLOR_DEAD_TEXT else COLOR_MUTED
            smallText.isFakeBoldText = true
            smallText.textSize = dp(11f)
            while (smallText.measureText(shown) > maxWidth && smallText.textSize > dp(6f)) {
                smallText.textSize -= dp(0.5f)
            }
            // 还是装不下就换个短的（「整体认读」→「整读」），
            // 再装不下就不画——宁可少个提示，也不要糊到隔壁键上去。
            if (smallText.measureText(shown) > maxWidth) {
                val shorter = when {
                    shown.startsWith("整体认读") -> "整读"
                    else -> ""
                }
                if (shorter.isNotEmpty() && smallText.measureText(shorter) <= maxWidth) {
                    shown = shorter
                } else {
                    shown = ""
                }
            }
            if (shown.isNotEmpty()) {
                canvas.drawText(shown, key.rect.centerX(), bigBaseline + dp(14f), smallText)
            }
        }
    }

    private fun drawPlain(canvas: Canvas, key: Key) {
        // 字符键跟中文键一样白底，只有底栏的功能键是灰底
        val isAction = key.kind == Kind.SPACE || key.kind == Kind.BACKSPACE ||
            key.kind == Kind.MODE
        fill.color = when {
            key === pressed -> COLOR_PRESSED
            // 动作键是品牌蓝——它是最该被一眼看到的那颗键
            key.kind == Kind.ACTION -> COLOR_ACCENT
            isAction -> COLOR_ACTION_BG
            else -> COLOR_KEY_BG
        }
        canvas.drawRoundRect(key.rect, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = if (key.kind == Kind.ACTION) COLOR_ACCENT else COLOR_KEY_BORDER
        canvas.drawRoundRect(key.rect, dp(8f), dp(8f), stroke)

        val label = when (key.kind) {
            Kind.SPACE -> spaceLabel
            else -> key.big
        }
        bigText.color = when (key.kind) {
            Kind.BACKSPACE -> COLOR_SUB
            Kind.ACTION -> Color.WHITE
            else -> COLOR_INK
        }
        bigText.textSize = dp(
            when {
                key.kind == Kind.BACKSPACE -> 18f
                label.length >= 3 -> 13f
                else -> 17f
            }
        )
        val baseline = key.rect.centerY() - (bigText.descent() + bigText.ascent()) / 2f
        canvas.drawText(label, key.rect.centerX(), baseline, bigText)
    }

    // ------------------------------------------------------------------
    // 触控
    // ------------------------------------------------------------------

    private fun findKey(x: Float, y: Float): Key? =
        keys.firstOrNull { it.rect.contains(x, y) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val key = findKey(event.x, event.y)
                pressed = key
                swipeKey = if (key?.kind == Kind.BACKSPACE) key else null
                downX = event.x
                downY = event.y
                swiped = false
                // 横向滑动选候选：字母区和**空格键**都认
                // （空格键最大最顺手，用户多半在那儿滑；
                //  退格键和侧边语音键各有各的手势，不抢）
                swipeEligible = key?.kind == Kind.PINYIN || key?.kind == Kind.SPACE
                reportedSteps = 0
                horizontalSwiped = false
                if (key != null) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                }
                // 长按退格：先等一小会儿，没抬手就开始连删
                stopRepeat()
                if (key?.kind == Kind.BACKSPACE) {
                    repeating = true
                    handler.postDelayed(repeatDelete, REPEAT_DELAY_MS)
                }
                // 按住空格说话：**延迟到「手指没动」再问服务层**
                // （有输入的时候空格是上屏，不能拿去说话）。
                // 空格键上横滑是「选候选」，手指一动就把这一下取消掉。
                if (key?.kind == Kind.SPACE) {
                    voiceArmed = false
                    voiceStarted = false
                    handler.postDelayed(voiceHoldStarter, VOICE_HOLD_MS)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val key = findKey(event.x, event.y)
                if (key !== pressed) {
                    pressed = null
                    invalidate()
                }
                val dx = event.x - downX
                val dy = event.y - downY
                // 手指一动就取消「长按连删」和「按住说话」——
                // 否则用户慢慢往下滑的时候，连删/录音已经先开始了，
                // 滑动手势再触发一次，删掉的/录到的都不是他想要的那一段。
                if (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP) {
                    if (repeating) stopRepeat()
                    if (!voiceStarted) {
                        handler.removeCallbacks(voiceHoldStarter)
                        voiceArmed = false
                    }
                }
                // 横向滑动 = 选候选。
                // 字母键上**没有别的手势**（退格键的上下滑只发生在退格键上），
                // 所以只要横向位移够了就算滑动——手指先往下带一点再往右滑，
                // 那是人手的自然动作，不该因为 dy 稍大就判成「没滑」。
                if (swipeEligible && !horizontalSwiped && abs(dx) > swipeThresholdPx()) {
                    horizontalSwiped = true
                    swiped = false          // 别把它误判成退格键的上滑/下滑
                    repeating = false
                    pressed = null
                    invalidate()
                }
                if (horizontalSwiped) {
                    // **手指往右 = 候选往右走**（跟选中的候选在候选栏里的移动方向一致）：
                    // 手指往右就往后翻，往左就往前翻。
                    val steps = candidateSteps(dx)
                    if (steps != reportedSteps) {
                        listener?.onCandidateSwipe(steps - reportedSteps)
                        reportedSteps = steps
                    }
                    return true
                }
                // 退格键上的纵向滑动
                if (swipeKey != null && !swiped) {
                    // 用 `>=` 而不是 `>`：斜着滑（45°）也要认，
                    // 否则用户「斜着往上划」会被当成没滑，退格键毫无反应。
                    if (abs(dy) > SWIPE_THRESHOLD && abs(dy) >= abs(dx)) {
                        swiped = true
                        repeating = false
                        pressed = null
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val key = pressed
                pressed = null
                stopRepeat()
                // 「按住说话」的装填是**延迟**问的：手指一抬就必须撤掉，
                // 否则松手之后才开始录音（那时候用户已经不在按了）。
                handler.removeCallbacks(voiceHoldStarter)
                invalidate()
                // 横向滑动选候选：这一下**不是**按键，别把字母打出来
                if (horizontalSwiped) {
                    swipeKey = null
                    swiped = false
                    swipeEligible = false
                    horizontalSwiped = false
                    performClick()
                    return true
                }
                // 「按住说话」：松手结束录音
                if (voiceArmed) {
                    // 这一下是「按住说话」：松手结束录音，不再上屏空格
                    handler.removeCallbacks(voiceHoldStarter)
                    if (voiceStarted) {
                        listener?.onVoiceStop()
                    } else {
                        // 没到阈值就松手了 —— 那是普通的空格
                        if (key != null && findKey(event.x, event.y) == key) {
                            fire(key)
                            performClick()
                        }
                    }
                    voiceArmed = false
                    voiceStarted = false
                    swipeKey = null
                    swiped = false
                    return true
                }
                if (swiped && swipeKey != null) {
                    // 上滑 = 逐格往回退，下滑 = 撤销
                    if (downY - event.y > 0) listener?.onSwipeUpDelete() else listener?.onUndo()
                    performClick()
                } else if (key != null && findKey(event.x, event.y) == key) {
                    fire(key)
                    performClick()
                }
                swipeKey = null
                swiped = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                swipeKey = null
                swiped = false
                swipeEligible = false
                horizontalSwiped = false
                stopRepeat()
                handler.removeCallbacks(voiceHoldStarter)
                if (voiceStarted) listener?.onVoiceCancel()
                voiceArmed = false
                voiceStarted = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 停掉长按连删。 */
    private fun stopRepeat() {
        repeating = false
        handler.removeCallbacks(repeatDelete)
    }

    private fun fire(key: Key) {
        when (key.kind) {
            Kind.PINYIN -> listener?.onPinyinKey(key.id)
            Kind.RAW -> listener?.onRawChar(key.id)
            Kind.SPACE -> listener?.onSpace()
            Kind.BACKSPACE -> listener?.onBackspace()
            Kind.ACTION -> listener?.onAction()
            Kind.MODE -> when (key.id) {
                "mode_english" -> {
                    val next = if (mode == Mode.CHINESE) Mode.ENGLISH else Mode.CHINESE
                    mode = next
                    listener?.onModeChanged(next)
                }

                "mode_chinese" -> {
                    mode = Mode.CHINESE
                    listener?.onModeChanged(Mode.CHINESE)
                }

                // 「返回」：回到进数字/标点面板之前的那个文字面板
                // （从英文进的回英文，从中文进的回中文）
                "mode_back" -> {
                    val next = previousTextMode
                    mode = next
                    listener?.onModeChanged(next)
                }

                "mode_number" -> {
                    previousTextMode = mode
                    mode = Mode.NUMBER
                    listener?.onModeChanged(Mode.NUMBER)
                }

                "mode_punct" -> {
                    previousTextMode = mode
                    mode = Mode.PUNCTUATION
                    listener?.onModeChanged(Mode.PUNCTUATION)
                }
            }
        }
    }

    companion object {
        /**
         * 单手模式：字母区占屏宽的比例。
         *
         * 0.86 是「拇指够得到」和「键还够大」之间的折中：
         * 全屏宽时每键 31.7 dp，收窄到 86% 后每键约 26.6 dp。
         *
         * **为什么不是更窄**：收到 72% 的时候反馈是「左侧大片空白浪费，键还有点小」——
         * 收窄换来的只是键变小，空白还是浪费。86% 把浪费压到一条 51dp 的窄条，
         * 那条窄条放一个竖长的语音键，不浪费。
         *
         * 候选栏要用同一个比例收窄（见 ShuangpinImeService.applyKeyLayout），
         * 所以这个常量是 public 的。
         */
        const val ONE_HAND_WIDTH_RATIO = 0.86f

        /** 退格键纵向滑动触发阈值（px）。 */
        private const val SWIPE_THRESHOLD = 60f

        /**
         * 手指移动多少（px）就算「动了」（px，不是 dp）。
         *
         * 超过这个距离就取消长按连删——用户是在滑，不是在长按。
         */
        private const val TOUCH_SLOP = 8f

        /**
         * 候选滑动的触发阈值（dp）。
         *
         * 太小会跟「点键时手抖一下」打架（抖一下就把字母打出来了），
         * 太大就滑不动。12 dp 大约是手指自然抖动的两倍。
         */
        private const val CANDIDATE_SWIPE_THRESHOLD_DP = 12f

        /**
         * 候选滑动一步的距离（dp）：一步 = 挪一个候选。
         *
         * 单手模式下候选栏宽 310 dp，一个候选大概 40 dp 宽，
         * 44 dp 正好对得上「滑一格换一个候选」。
         */
        private const val CANDIDATE_SWIPE_STEP_DP = 44f

        /** 长按退格多久开始连删（ms）。 */
        private const val REPEAT_DELAY_MS = 350L

        /** 长按连删的间隔（ms）。 */
        private const val REPEAT_INTERVAL_MS = 60L

        /**
         * 按住空格多久开始录音（ms）。
         *
         * 太短会误触发（想打空格结果开始录音），
         * 太长用户会以为没反应——300 ms 是「明显是长按」的手感。
         */
        private const val VOICE_HOLD_MS = 300L

        /** 双拼布局：与原型一致。 */
        private val PINYIN_ROWS = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf("z", "x", "c", "v", "b", "n", "m"),
        )

        /** 英文 26 键 QWERTY。 */
        private val ENGLISH_ROWS = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf("z", "x", "c", "v", "b", "n", "m"),
        )

        private val NUMBER_ROWS = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("-", "/", ":", ";", "(", ")", "¥", "@", "#", "&"),
            listOf(".", ",", "?", "!", "'", "\"", "~", "`"),
        )

        private val PUNCT_ROWS = listOf(
            listOf(",", ".", "?", "!", ";", ":", "'", "\"", "(", ")"),
            listOf("，", "。", "？", "！", "、", "；", "：", "“", "”", "‘"),
            listOf("）", "《", "》", "【", "】", "￥", "…", "·", "～", "—"),
        )

        /**
         * 单手模式留白区那一列**常用标点**。
         *
         * 以前那里是一个竖长的语音键，但语音已经有「按住空格说话」了，
         * 那个键就多余了（用户反馈「左侧语音按键改为常用标点」）。
         */
        private val SIDE_PUNCT = listOf("，", "。", "？", "！", "、", "；")

        private const val COLOR_BG = 0xFFE9EBF0.toInt()
        private const val COLOR_KEY_BG = 0xFFFFFFFF.toInt()
        private const val COLOR_PRESSED = 0xFFDCE6FF.toInt()
        private const val COLOR_ACTION_BG = 0xFFDDE1E8.toInt()
        private const val COLOR_DEAD_BG = 0xFFE2E5EA.toInt()
        private const val COLOR_KEY_BORDER = 0xFFD8DCE4.toInt()
        private const val COLOR_DEAD_BORDER = 0xFFC9CED8.toInt()
        private const val COLOR_INK = 0xFF171B23.toInt()
        private const val COLOR_SUB = 0xFF5B6472.toInt()
        private const val COLOR_MUTED = 0xFF98A1B0.toInt()
        private const val COLOR_BADGE = 0xFF7A8494.toInt()
        private const val COLOR_DEAD_TEXT = 0xFF7D8695.toInt()
        private const val COLOR_ACCENT = 0xFF2F6DF6.toInt()
    }
}
