package com.shuangpin.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.shuangpin.engine.Display
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

    interface Listener {
        /** 双拼字母键（中文模式） */
        fun onPinyinKey(key: String)

        /** 原始字符（英文/数字/标点模式） */
        fun onRawChar(text: String)

        fun onSpace()
        fun onBackspace()

        /** 退格键上滑：一次退一格（先退最后输入的音节，再往前退，最后退已上屏的文字） */
        fun onSwipeUpDelete()

        /** 退格键下滑：撤销 */
        fun onUndo()

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

    /** 英文模式是否大写。 */
    var upperCase: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 空格键的文案，随编辑框的 IME action 变化（搜索/发送/完成…）。 */
    var spaceLabel: String = "空格"
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
    private var voiceArmed = false
    private var voiceStarted = false

    private val voiceHoldStarter = object : Runnable {
        override fun run() {
            if (!voiceArmed) return
            voiceStarted = true
            listener?.onVoiceStart()
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

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

    private enum class Kind { PINYIN, RAW, SPACE, BACKSPACE, MODE }

    fun bind(session: InputSession, engine: PinyinEngine) {
        this.session = session
        this.engine = engine
        layoutKeys()
        invalidate()
    }

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
        val bottomH = dp(50f)
        val letterH = ((height - bottomH - rowGap * 3 - pad * 2) / 3f).coerceAtLeast(dp(34f))

        when (mode) {
            Mode.CHINESE -> layoutChinese(pad, gap, rowGap, letterH)
            Mode.ENGLISH -> layoutEnglish(pad, gap, rowGap, letterH)
            Mode.NUMBER -> layoutNumber(pad, gap, rowGap, letterH)
            Mode.PUNCTUATION -> layoutPunctuation(pad, gap, rowGap, letterH)
        }

        layoutBottomBar(pad, gap, bottomH)
    }

    /** 三行字母，大小随行内键数自适应。 */
    private fun layoutRows(rows: List<List<Key>>, pad: Float, gap: Float, rowGap: Float, rowH: Float) {
        rows.forEachIndexed { ri, row ->
            val rowW = row.size * keyWidth(row.size) + (row.size - 1) * gap
            val x0 = (width - rowW) / 2f
            val y0 = pad + ri * (rowH + rowGap)
            row.forEachIndexed { i, key ->
                val w = keyWidth(row.size)
                val left = x0 + i * (w + gap)
                keys += key.copy(rect = RectF(left, y0, left + w, y0 + rowH))
            }
        }
    }

    private fun keyWidth(count: Int): Float = (width - dp(6f) * 2 - dp(4f) * (count - 1)) / count

    private fun layoutChinese(pad: Float, gap: Float, rowGap: Float, rowH: Float) {
        // 标签在**绘制时**才算（见 KeyboardFaces），
        // 这里只放键位和矩形——状态一变，键面立刻就换。
        val rows = PINYIN_ROWS.map { letters ->
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

    private fun layoutNumber(pad: Float, gap: Float, rowGap: Float, rowH: Float) {
        val rows = NUMBER_ROWS.map { line ->
            line.map { k ->
                Key(k, k, null, null, RectF(), Kind.RAW)
            }
        }
        layoutRows(rows, pad, gap, rowGap, rowH)
    }

    private fun layoutPunctuation(pad: Float, gap: Float, rowGap: Float, rowH: Float) {
        val rows = PUNCT_ROWS.map { line ->
            line.map { k ->
                Key(k, k, null, null, RectF(), Kind.RAW)
            }
        }
        layoutRows(rows, pad, gap, rowGap, rowH)
    }

    private fun layoutBottomBar(pad: Float, gap: Float, bottomH: Float) {
        val y0 = height - bottomH - pad

        data class BottomKey(val id: String, val label: String, val kind: Kind)

        val items = when (mode) {
            Mode.CHINESE, Mode.ENGLISH -> listOf(
                BottomKey("mode_english", if (mode == Mode.CHINESE) "中/英" else "中/英", Kind.MODE),
                BottomKey("mode_number", "123", Kind.MODE),
                BottomKey("mode_punct", "符", Kind.MODE),
                BottomKey("space", "空格", Kind.SPACE),
                BottomKey("back", "⌫", Kind.BACKSPACE),
            )

            Mode.NUMBER -> listOf(
                BottomKey("mode_english", "abc", Kind.MODE),
                BottomKey("mode_punct", "符", Kind.MODE),
                BottomKey("space", "空格", Kind.SPACE),
                BottomKey("back", "⌫", Kind.BACKSPACE),
            )

            Mode.PUNCTUATION -> listOf(
                BottomKey("mode_english", "abc", Kind.MODE),
                BottomKey("mode_number", "123", Kind.MODE),
                BottomKey("space", "空格", Kind.SPACE),
                BottomKey("back", "⌫", Kind.BACKSPACE),
            )
        }

        // 空格键要**最宽**——它是输入法的第二常用键，
        // 之前算成了最窄的那个，用户根本找不到（报「空格键没有了」）。
        val otherCount = items.size - 1
        val avail = width - pad * 2 - gap * (items.size - 1)
        val otherW = dp(52f)
        val spaceW = (avail - otherW * otherCount).coerceAtLeast(dp(96f))

        var x = pad
        for (item in items) {
            val w = if (item.kind == Kind.SPACE) spaceW else otherW
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

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(COLOR_BG)
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
        while (bigText.measureText(face.big) > maxWidth && bigText.textSize > dp(9f)) {
            bigText.textSize -= dp(0.5f)
        }
        val bigBaseline = key.rect.centerY() - dp(if (hasSub) 4f else 0f) -
            (bigText.descent() + bigText.ascent()) / 2f
        canvas.drawText(face.big, key.rect.centerX(), bigBaseline, bigText)

        // 副标签（声母位：该键承载的常用韵母）
        if (hasSub) {
            smallText.color = if (face.dead) COLOR_DEAD_TEXT else COLOR_MUTED
            smallText.isFakeBoldText = true
            smallText.textSize = dp(11f)
            while (smallText.measureText(sub) > maxWidth && smallText.textSize > dp(7f)) {
                smallText.textSize -= dp(0.5f)
            }
            canvas.drawText(sub, key.rect.centerX(), bigBaseline + dp(14f), smallText)
        }
    }

    private fun drawPlain(canvas: Canvas, key: Key) {
        // 字符键跟中文键一样白底，只有底栏的功能键是灰底
        val isAction = key.kind == Kind.SPACE || key.kind == Kind.BACKSPACE || key.kind == Kind.MODE
        fill.color = when {
            key === pressed -> COLOR_PRESSED
            isAction -> COLOR_ACTION_BG
            else -> COLOR_KEY_BG
        }
        canvas.drawRoundRect(key.rect, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = COLOR_KEY_BORDER
        canvas.drawRoundRect(key.rect, dp(8f), dp(8f), stroke)

        val label = when (key.kind) {
            Kind.BACKSPACE -> "⌫"
            Kind.SPACE -> spaceLabel
            else -> key.big
        }
        bigText.color = if (key.kind == Kind.BACKSPACE) COLOR_SUB else COLOR_INK
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
                // 按住空格说话：服务层说了算（有输入的时候空格是上屏，不能拿去说话）
                if (key?.kind == Kind.SPACE) {
                    voiceArmed = listener?.onSpaceDown() ?: false
                    voiceStarted = false
                    if (voiceArmed) {
                        handler.postDelayed(voiceHoldStarter, VOICE_HOLD_MS)
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val key = findKey(event.x, event.y)
                if (key !== pressed) {
                    pressed = null
                    invalidate()
                }
                // 退格键上的纵向滑动
                if (swipeKey != null && !swiped) {
                    val dy = event.y - downY
                    val dx = event.x - downX
                    if (abs(dy) > SWIPE_THRESHOLD && abs(dy) > abs(dx)) {
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
                invalidate()
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
            Kind.MODE -> when (key.id) {
                "mode_english" -> {
                    val next = if (mode == Mode.CHINESE) Mode.ENGLISH else Mode.CHINESE
                    mode = next
                    listener?.onModeChanged(next)
                }

                "mode_number" -> {
                    mode = Mode.NUMBER
                    listener?.onModeChanged(Mode.NUMBER)
                }

                "mode_punct" -> {
                    mode = Mode.PUNCTUATION
                    listener?.onModeChanged(Mode.PUNCTUATION)
                }
            }
        }
    }

    companion object {
        /** 退格键纵向滑动触发阈值（px）。 */
        private const val SWIPE_THRESHOLD = 60f

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
