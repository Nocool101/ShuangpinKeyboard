package com.shuangpin.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
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

        /**
         * 退格键**上滑后停顿**：把该删的一次删光。
         *
         * - 正在输入拼音 → 删掉**所有**已输入的拼音
         * - 候选栏没内容（没在输入） → 删掉**光标以前的所有内容**
         *
         * 下滑撤销能把删掉的原样恢复回来。
         */
        fun onSwipeUpDeleteAll() {}

        /** 退格键下滑：撤销 */
        fun onUndo()

        /**
         * 在键盘输入区（字母区）**横向滑动**。
         *
         * 这一下手势有两种含义，看服务层当前有没有在输入：
         *
         * | 当前状态 | 横向滑动 |
         * |---|---|
         * | **正在输入**（有预编辑内容） | 移动候选选择（手指往右 = 往后翻） |
         * | **没有输入** | **挪光标**（手指往右 = 光标往右，一个字一个字） |
         *
         * 「手往哪个方向滑，光标就往哪个方向走」——
         * 没输入的时候，字母区就是一块**跟手拖动板**：
         * 光标挪多远 = 手指走了多远 ÷ 一个字的宽度
         * （滑得快光标就走得快，滑得慢就走得慢）。
         *
         * 纵向的滑动**不算手势**（很容易误触，用户反馈）——
         * 吃掉但什么都不做，既不挪光标也不打字母。
         *
         * @param steps 位移步数（正 = 手指往右）：候选个数 / 字数
         */
        fun onLetterSwipe(steps: Int) {}

        /**
         * **长按之后**在字母区横向滑动 → 挪**拼音里的光标**。
         *
         * 直接滑动是「选候选」；**手指先按住不动**（长按），再滑，
         * 这一滑就变成「挪拼音光标」——
         * 手指不用离开键盘，就能把光标挪到打错的那个音节上，
         * 接着按正确的键就能改（不用去预编辑区里点那个字）。
         *
         * @param steps 位移步数（正 = 往右）：拼音的**字**数
         */
        fun onPinyinCaretMove(steps: Int) {}

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

    /**
     * 空格键的**长按**是不是被「按住说话」占着。
     *
     * 占着的时候，空格键上长按滑动仍然是「选候选」；
     * 没占着（语音没开 / 正在打拼音没法说话），
     * 空格键上的长按滑动就用来**挪拼音光标**——
     * 空格键最大最顺手，用户按住那儿滑是很自然的动作，
     * 不该浪费掉（用户反馈「按下停顿移动拼音光标……然后就滑不动了」：
     * 他多半就是按着空格滑的，滑出来的是候选移动，不是光标）。
     *
     * 服务层按当前状态改这个标志（见 `canTalkBySpace()`）。
     */
    var spaceLongPressForVoice: Boolean = true
        set(value) {
            if (field != value) field = value
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

    // 候选选择滑动 / 光标移动滑动
    //
    // 这一下手势**只从字母区起**——底栏是功能键（空格/退格/确认），
    // 侧边那条是语音键，都有自己的手势，不能抢。
    //
    // **只认横向**：正在输入时横向选候选，没输入时横向挪光标。
    // 纵向的滑动不算手势（用户反馈「上滑很容易误触」）。
    private var swipeEligible = false
    private var reportedSteps = 0     // 候选模式：横向候选步数
    private var reportedChars = 0     // 光标模式：横向字数
    private var swiping = false       // 这一下是不是滑动（横竖都算）

    // ---- 长按滑动 = 挪拼音光标 ----
    //
    // 直接滑动是「选候选」；**先按住不动**（长按），这一滑就变成「挪拼音光标」。
    // 这样用户不用把手挪到输入框里去点拼音中间，手指一直在键盘上就能改拼音。
    //
    // 长按到点了给一次震动 + 键盘顶上画一条蓝线，
    // 让用户知道「现在是挪光标，不是选候选」——不然这一滑啥反应都没有，
    // 用户会以为手势没生效。
    private var caretMode = false      // 长按已触发：这一下滑是挪拼音光标
    private var caretSteps = 0         // 已经挪了几个拼音字
    private var pressActive = false    // 这一下还按着（长按计时器用）

    // 长按退格连删
    private val handler = Handler(Looper.getMainLooper())
    private var repeating = false

    /**
     * 长按到点了 → 这一下滑变成「挪拼音光标」。
     *
     * 只在手指**没动**的时候触发：只要越过了滑动阈值，
     * 这一下就当成普通的滑动（选候选），长按计时器会被撤掉。
     */
    private val longPressStarter = object : Runnable {
        override fun run() {
            if (!pressActive || swiping || swiped) return
            caretMode = true
            caretSteps = 0
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

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

    /**
     * 现在是「挪光标」模式吗？——**没有待上屏的输入**的时候。
     */
    private fun cursorMode(): Boolean {
        val s = session ?: return true
        return s.composingText.isEmpty()
    }

    /**
     * 横向走了多远 = 光标挪几个**字**。
     *
     * 手指走过的距离 ÷ 一个字的宽度 = 字数。
     * **跟手**：手指滑得快，光标就走得快；滑得慢就走得慢。
     */
    private fun cursorCharSteps(dx: Float): Int =
        (dx / dp(CURSOR_PX_PER_CHAR_DP)).toInt()

    /**
     * 横向走了多远 = **拼音光标**挪几个字。
     *
     * 拼音的字母比正文小得多（一个字母 ~9 dp），
     * 所以一步比挪正文光标更短——不然轻轻一滑就跳过去好几个字，
     * 根本停不到想改的那个音节上。
     */
    private fun caretCharSteps(dx: Float): Int =
        (dx / dp(CARET_PX_PER_CHAR_DP)).toInt()

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

        // 单手模式：留白区那一列放常用标点（贴着屏幕远端），
        // 全键盘模式没有留白区，常用标点靠数字面板左列的「符号」键。
        if (oneHand) layoutSideColumn(gap, bottomH)
        // 数字面板**不要底栏**——它右列自带 退格/空格/确认（跟参考图一样）
        if (mode != Mode.NUMBER) {
            layoutBottomBar(pad, gap, bottomH, oneHand)
        }
        computeLetterArea()
    }

    /**
     * 字母区（键 + 键之间的缝隙）的范围。
     *
     * 手指落在**键与键的缝隙**里时，`findKey` 找不到键——
     * 老代码就认为「没按到键」，滑动不触发（用户反馈：
     * 「滑动起始位置在键盘交叉处无法触发滑动」）。
     *
     * 这里把字母键的**并集**算出来，缝隙也在里面：
     * 只要起点在字母区内，滑动就认。
     */
    private fun computeLetterArea() {
        var area: RectF? = null
        for (k in keys) {
            if (k.kind != Kind.PINYIN && k.kind != Kind.RAW) continue
            if (area == null) area = RectF(k.rect) else area.union(k.rect)
        }
        letterArea = area
    }

    /** 字母区的范围（可能是 null）。 */
    private var letterArea: RectF? = null

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
     * 整个标点面板在**数字面板左列的「符号」键**里（那边字更全）：
     * 底栏的「符」键已经拿掉（用户反馈底栏太挤，容易误触）。
     *
     * **这一列贴着屏幕的远端**（右手模式贴左，左手模式贴右）：
     * 键盘本体缩到拇指那一侧之后，中间空出来一大条，
     * 标点列贴在远端像一条「边栏」，不跟键盘本体挤在一起。
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
        // 标点列**把留白填满**（上限 64 dp）：留白太宽看着像屏幕那边坏了
        val w = stripW.coerceAtMost(dp(64f))
        // 贴远端：右手模式贴屏幕左边，左手模式贴屏幕右边
        val x = if (layout == Layout.RIGHT) stripLeft else stripRight - w
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
                // **底栏不放「符」键**：用户反馈底栏挤了六个键，
                // 手指不好找，而且「符」这个低频键把左边塞得满满当当。
                // 标点面板从**数字面板左列的「符号」键**进，功能没丢。
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

        // 退格键与动作键之间**留一段空隙**。
        //
        // 这俩挨着的时候，手指点退格很容易滑到旁边的「换行/发送」——
        // 一次误触就发出一条消息、或者多打一个换行，
        // 后果比打错字严重得多（用户反馈过）。
        // 给它们之间留出 4 倍于普通键缝的空隙，物理上就不容易连着点。
        val sep = dp(if (oneHand) 10f else 16f)

        // 宽度分配：空格要宽，但动作键不能太窄（它是常用键）
        //
        // ⚠ **单手模式下功能键必须跟着缩**：整块键盘只有 60% 宽了，
        // 键还按原来的宽度排，最后一个键会顶出屏幕右边（右手模式）。
        // 「中/英」是三个字，缩太窄字装不下，给它单独一档宽度（比例 1.5）。
        val others = items.size - 1
        val avail = blockWidth - pad * 2 - gap * (items.size - 1) - sep
        val cjkCount = items.count { it.id == "mode_english" }
        val cjkRatio = if (oneHand) 1.5f else 1.0f
        // 这一排是「密排」的一档：键窄一点，空格宽一点。
        // 判据用「是不是文字面板的底栏」，**不用键数**——
        // 拿掉「符」之后键数变了，按键数判会把键放大一档，版面就跟着变了。
        val compact = mode == Mode.CHINESE || mode == Mode.ENGLISH
        var otherW = dp(
            when {
                oneHand -> 26f
                compact -> 40f
                else -> 48f
            }
        )
        var actionW = dp(if (oneHand) 34f else 62f)
        // 动作键和退格键固定宽，其余（含空格）分剩下的
        val fixedOthers = others - 1   // 除动作键外的功能键个数
        val minSpace = dp(if (oneHand) 40f else 70f)
        // 三字键（「中/英」）比普通功能键多占的那一份
        val extraW = otherW * (cjkRatio - 1f) * cjkCount
        var spaceW = avail - otherW * fixedOthers - extraW - actionW
        // 屏宽很窄的时候，光固定键自己就排不下 —— 整体缩一档，别顶出屏幕
        if (spaceW < minSpace) {
            val fixed = otherW * fixedOthers + extraW + actionW
            val k = ((avail - minSpace) / fixed).coerceIn(0.4f, 1f)
            otherW *= k
            actionW *= k
            spaceW = avail - otherW * fixedOthers - otherW * (cjkRatio - 1f) * cjkCount - actionW
        }
        spaceW = spaceW.coerceAtLeast(minSpace)
        val cjkW = otherW * cjkRatio

        var x = blockLeft + pad
        for (item in items) {
            val w = when {
                item.kind == Kind.SPACE -> spaceW
                item.kind == Kind.ACTION -> actionW
                item.id == "mode_english" -> cjkW
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
            // 退格键后面多留一段空隙，把动作键推开（见上面 sep 的说明）
            if (item.kind == Kind.BACKSPACE) x += sep
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
        // 「挪拼音光标」模式**不画任何东西**：
        // 曾经在键盘顶上画过一条蓝条当提示，用户反馈「非常影响观感」。
        // 现在只靠一次震动 + 输入框里那个会动的光标告诉用户模式变了。
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

        // 键宽不够的时候（小鹤双拼的单手模式），键面只留**第一个**标签，
        // 字号也跟着缩——两个都塞进去只会糊成一片。
        val maxWidth = key.rect.width() - dp(6f)
        val narrow = key.rect.width() < dp(28f)

        // 键位字母角标：告诉用户「这个键上按的是哪个字母」。
        // 韵母位上主标签让给韵母了，角标就是唯一的找键线索，必须够大看得清。
        face.badge?.let { badge ->
            badgeText.color = if (face.dead) COLOR_DEAD_TEXT else COLOR_BADGE
            badgeText.textSize = dp(if (narrow) 9.5f else 11.5f)
            canvas.drawText(badge, key.rect.left + dp(3f), key.rect.top + dp(11f), badgeText)
        }

        val sub = face.sub
        val hasSub = !sub.isNullOrEmpty()

        // 主标签：声母位是声母，韵母位是韵母
        bigText.color = if (face.dead) COLOR_DEAD_TEXT else COLOR_INK
        bigText.textSize = dp(if (face.big.length >= 3) 14f else 17f)
        // 太长就缩字号，避免超出键宽（"er/uan"、"iang/uang" 这类）
        // 单手模式键窄，下限放低一点，不然「uang」这种缩到 9dp 还是装不下
        val minSize = if (narrow) dp(7f) else dp(9f)
        // 窄键上「ing/uai」这种带斜杠的**主标签**（韵母位里无效键的提示）
        // 只留第一个——塞不下两个，糊到隔壁键上更糟。
        var bigShown = if (narrow) face.big.substringBefore('/') else face.big
        while (bigText.measureText(bigShown) > maxWidth && bigText.textSize > minSize) {
            bigText.textSize -= dp(0.5f)
        }
        // 有副标签时，主标签往上抬一点，给底部的拼音腾地方
        val bigBaseline = key.rect.centerY() - dp(if (hasSub) 6f else 0f) -
            (bigText.descent() + bigText.ascent()) / 2f
        canvas.drawText(bigShown, key.rect.centerX(), bigBaseline, bigText)

        // 副标签（声母位：该键承载的常用韵母）
        if (hasSub) {
            // 键窄的时候只显示第一个韵母：「er/uan」→「er」。
            // 挤着显示两个在单手模式下根本看不清，倒不如只留常用的那个。
            var shown = if (narrow) sub.substringBefore('/') else sub
            smallText.color = if (face.dead) COLOR_DEAD_TEXT else COLOR_MUTED
            smallText.isFakeBoldText = true
            // **拼音提示是小字**：它只是提示，按的是键上的大字。
            // 长的（「ia/ua」「ing/uai」「iang/uang」）再小一号——
            // 那几个正好都在最下面一排，太大就把键面撑满了（用户反馈
            // 「最下面一排拼音有些大」）。
            //
            // 拼音是**灰色小字**，允许它贴到键边（留 2dp 就行）：
            // 主标签留 6dp 是因为它又大又粗，拼音不用。
            val subMax = key.rect.width() - dp(2f)
            smallText.textSize = dp(if (shown.length >= 4) 9f else 10f)
            while (smallText.measureText(shown) > subMax && smallText.textSize > dp(6f)) {
                smallText.textSize -= dp(0.5f)
            }
            // 还是装不下就换个短的（「整体认读」→「整读」），
            // 再装不下就不画——宁可少个提示，也不要糊到隔壁键上去。
            if (smallText.measureText(shown) > subMax) {
                val shorter = when {
                    shown.startsWith("整体认读") -> "整读"
                    else -> ""
                }
                if (shorter.isNotEmpty()) {
                    // 短的也要缩到装得下为止（单手模式键很窄），
                    // 宁可小一点，也要把「这个键是整体认读」这件事说出来。
                    smallText.textSize = dp(if (shown.length >= 4) 9f else 10f)
                    while (smallText.measureText(shorter) > subMax && smallText.textSize > dp(6f)) {
                        smallText.textSize -= dp(0.5f)
                    }
                    shown = if (smallText.measureText(shorter) <= subMax) shorter else ""
                } else {
                    shown = ""
                }
            }
            if (shown.isNotEmpty()) {
                // **拼音提示贴在键底**：以前它紧跟在主标签下面，悬在键面中间，
                // 键的下半截空着，顶排尤其明显（用户反馈
                // 「把最上面一排拼音适当向下降一些」）。
                // 钉到键底之后，三排的拼音在同一条基线上，手指点下去也正好压住大字。
                val subBaseline = key.rect.bottom - dp(4.5f)
                canvas.drawText(shown, key.rect.centerX(), subBaseline, smallText)
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
        // 键宽有限：字装不下就缩一号。
        // 单手模式的功能键跟着键盘缩了 2/3，「中/英」这种三字键
        // 不缩就会顶到隔壁键上（用户反馈「最下面一排拼音按键有些大」）。
        val maxW = key.rect.width() - dp(8f)
        while (bigText.measureText(label) > maxW && bigText.textSize > dp(9f)) {
            bigText.textSize -= dp(0.5f)
        }
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
                // 滑动选候选 / 挪光标：字母区和**空格键**都认
                // （空格键最大最顺手，用户多半在那儿滑；
                //  退格键和侧边语音键各有各的手势，不抢）。
                // 英文、数字、标点面板的键也算——没输入的时候，
                // 在哪儿滑都能挪光标。
                //
                // ⚠ **没按到键也算**：手指落在键与键的缝隙里时，
                // 只要起点在字母区范围内，滑动照样触发
                // （用户反馈「滑动起始位置在键盘交叉处无法触发滑动」）。
                swipeEligible = when {
                    key != null -> key.kind == Kind.PINYIN ||
                        key.kind == Kind.RAW ||
                        key.kind == Kind.SPACE
                    else -> letterArea?.contains(event.x, event.y) == true
                }
                reportedSteps = 0
                reportedChars = 0
                swiping = false
                caretMode = false
                caretSteps = 0
                pressActive = true
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
                // 长按滑动 = 挪拼音光标：按住不动到阈值，这一滑就变成挪光标。
                //
                // 字母区随时可以；**空格键**只有在「按住说话」没占着它的时候才可以
                // （见 [spaceLongPressForVoice]）——空格键最大最顺手，
                // 用户按住那儿滑是很自然的动作，不该浪费掉。
                val spaceOk = key?.kind != Kind.SPACE || !spaceLongPressForVoice
                if (swipeEligible && spaceOk) {
                    handler.postDelayed(longPressStarter, LONG_PRESS_MS)
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
                // 滑动识别：**只认横向**。
                //
                // 纵向的滑动（往上/往下划）**不算手势、不做任何事**——
                // 以前它会让光标换行（很容易误触，用户反馈），
                // 现在直接吃掉：既不挪光标，也不把那个字母打出来。
                if (swipeEligible && !swiping) {
                    val threshold = swipeThresholdPx()
                    if (abs(dx) > threshold || abs(dy) > threshold) {
                        swiping = true
                        swiped = false          // 别把它误判成退格键的上滑/下滑
                        repeating = false
                        pressed = null
                        // 手指已经滑起来了 → 这一下是**普通滑动**（选候选），
                        // 不是长按之后才触发的「挪拼音光标」。
                        handler.removeCallbacks(longPressStarter)
                        invalidate()
                    }
                }
                if (swiping) {
                    if (caretMode && !cursorMode()) {
                        // 长按之后的滑动：挪**拼音里的光标**。
                        // 手指不用离开键盘就能把光标挪到打错的音节上。
                        val chars = caretCharSteps(dx)
                        if (chars != caretSteps) {
                            Log.d(
                                "ShuangpinIME",
                                "swipe: 挪拼音光标 dx=$dx 步数=${chars - caretSteps} (累计 $chars 字)",
                            )
                            listener?.onPinyinCaretMove(chars - caretSteps)
                            caretSteps = chars
                        }
                    } else if (cursorMode()) {
                        // 挪光标：横向按**字**挪，挪多少 = 手指走了多远 ÷ 一个字的宽度
                        val chars = cursorCharSteps(dx)
                        if (chars != reportedChars) {
                            Log.d(
                                "ShuangpinIME",
                                "swipe: 挪光标 dx=$dx 步数=${chars - reportedChars} (累计 $chars 字)",
                            )
                            listener?.onLetterSwipe(chars - reportedChars)
                            reportedChars = chars
                        }
                    } else {
                        // 选候选：横向一步一个候选
                        val steps = candidateSteps(dx)
                        if (steps != reportedSteps) {
                            Log.d(
                                "ShuangpinIME",
                                "swipe: 选候选 dx=$dx 步数=${steps - reportedSteps} (累计 $steps 个)",
                            )
                            listener?.onLetterSwipe(steps - reportedSteps)
                            reportedSteps = steps
                        }
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
                        // 上滑之后**手指停住不动** = 「删全部」：
                        // 起一个计时器，手指还按着就说明用户是故意的停顿。
                        if (downY - event.y > 0) startSwipeHoldTimer()
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
                // 长按计时器同理：手指都抬了，不能再进「挪光标」模式
                handler.removeCallbacks(longPressStarter)
                pressActive = false
                caretMode = false
                invalidate()
                // 滑动：这一下**不是**按键，别把字母打出来
                if (swiping) {
                    swipeKey = null
                    swiped = false
                    swipeEligible = false
                    swiping = false
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
                    // 上滑 = 退一格，下滑 = 撤销
                    // 「上滑后停顿」已经触发过「删全部」，这一下就不要再退一格了
                    if (downY - event.y > 0) {
                        if (!swipeHoldFired) listener?.onSwipeUpDelete()
                    } else {
                        listener?.onUndo()
                    }
                    performClick()
                } else if (key != null && findKey(event.x, event.y) == key) {
                    fire(key)
                    performClick()
                }
                swipeKey = null
                swiped = false
                stopSwipeHoldTimer()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                swipeKey = null
                swiped = false
                swipeEligible = false
                swiping = false
                stopRepeat()
                stopSwipeHoldTimer()
                handler.removeCallbacks(voiceHoldStarter)
                handler.removeCallbacks(longPressStarter)
                pressActive = false
                caretMode = false
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

    // ---- 上滑停顿 = 删全部 ----
    //
    // 退格键往上滑一下 = 退一格；**滑上去停住不动** = 把该删的一次删光。
    // 靠「手指还按着」来判断：滑上去之后如果一直不动，就是故意的停顿。
    private var swipeHoldFired = false

    private val swipeHoldTimer = object : Runnable {
        override fun run() {
            swipeHoldFired = true
            listener?.onSwipeUpDeleteAll()
        }
    }

    private fun startSwipeHoldTimer() {
        swipeHoldFired = false
        handler.removeCallbacks(swipeHoldTimer)
        handler.postDelayed(swipeHoldTimer, SWIPE_HOLD_MS)
    }

    private fun stopSwipeHoldTimer() {
        handler.removeCallbacks(swipeHoldTimer)
        swipeHoldFired = false
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
         * 单手模式：整块键盘占屏宽的比例。
         *
         * **0.80 = 键宽 25 dp（比全键盘的 32 dp 小，但还能按得准）**。
         *
         * 这一版的比例来回改过两轮：
         *
         * | 比例 | 键宽 | 问题 |
         * |---|---|---|
         * | 0.86（旧） | 27 dp | 键够大，但整块横跨 86% 屏宽，**最左边几颗拇指够不着** |
         * | 0.60（第一轮） | 18 dp | 键太小按不准 + **左边空出 40% 一大片**（用户反馈「按键太小，且有大面积空白区域」）|
         * | **0.80（本轮）** | **25 dp** | 键比第一轮大 40%，空白压到一条标点列，整块仍贴着拇指那一侧 |
         *
         * ⚠ **「整块都在拇指下」和「键够大」在 QWERTY 布局下是死结**：
         * 一排 10 个键，键宽 25 dp 就要 270 dp，比一个拇指能扫过去的范围（约 150 dp）宽得多。
         * 想要整块都在拇指下，只能减键（10 键 → 6 键）或者把键做小——
         * 前者会毁掉双拼的**位置记忆**（小鹤 zh→v / ch→i / sh→u），后者就是第一轮被否掉的方案。
         * 所以这里取折中：**键保持能按准的大小，整块尽量往拇指那一侧挤**。
         *
         * ⚠ **只有单手模式收窄**：全键盘模式两根手都能按，
         * 键摊满屏宽才是对的（收窄只会白白浪费屏宽）。
         *
         * 候选栏**不跟着缩**（它有自己的一套比例，见 ShuangpinImeService）——
         * 候选栏缩了，一页能看见的候选太少，翻起来更累。
         * 这个常量是 public 的（服务层要拿它算留白）。
         */
        const val ONE_HAND_WIDTH_RATIO = 0.80f

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
         * 36 dp 正好对得上「滑一格换一个候选」。
         * （原来 44 dp 偏慢，滑半天换不了一个候选。）
         */
        private const val CANDIDATE_SWIPE_STEP_DP = 36f

        /**
         * **挪光标**时，手指走多远算挪一个字（dp）。
         *
         * 取一个字的宽度（正文里一个汉字差不多 16~20 dp），
         * 这样手指在屏幕上划过一个字，光标也正好挪一个字——**跟手**。
         * （原来 16 dp 偏快，容易一滑出去好几个字。）
         */
        private const val CURSOR_PX_PER_CHAR_DP = 20f

        /**
         * **长按**多久算「进入挪拼音光标模式」（ms）。
         *
         * 250 ms：比「按住说话」的 300 ms 还短一点，
         * 保证**空格键**上两个手势不打架（语音占着的时候空格键压根不进这个模式）。
         *
         * 不能再短了：手指一沾到键就开始滑的那种「快速滑选候选」会被误判成挪光标，
         * 候选就选不了了（用户反馈「滑动选词」是常用手势，不能被它吃掉）。
         */
        private const val LONG_PRESS_MS = 250L

        /**
         * 挪**拼音光标**时，手指走多远算挪一个拼音字（dp）。
         *
         * 拼音的字母比正文小得多（一个字母 ~9 dp），
         * 所以比挪正文光标（20 dp）短——不然轻轻一滑就跳过去好几个字，
         * 根本停不到想改的那个音节上。
         */
        private const val CARET_PX_PER_CHAR_DP = 12f

        /** 长按退格多久开始连删（ms）。 */
        private const val REPEAT_DELAY_MS = 350L

        /**
         * 退格键**上滑后停顿**多久算「删全部」（ms）。
         *
         * 手指滑上去一直不动 → 用户是故意的停顿，不是手滑。
         */
        private const val SWIPE_HOLD_MS = 400L

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
