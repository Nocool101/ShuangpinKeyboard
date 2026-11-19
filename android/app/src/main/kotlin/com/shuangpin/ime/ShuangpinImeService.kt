package com.shuangpin.ime

import android.inputmethodservice.InputMethodService
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.shuangpin.engine.Candidate
import com.shuangpin.engine.CandidateKind
import com.shuangpin.engine.Candidates
import com.shuangpin.engine.DataLoader
import com.shuangpin.engine.InputSession
import com.shuangpin.engine.InputStage
import com.shuangpin.engine.LexemeRequest
import com.shuangpin.engine.PinyinEngine
import com.shuangpin.engine.SwipeRemoval
import com.shuangpin.ime.voice.AsrConfig
import com.shuangpin.ime.voice.VoiceController

/**
 * 双拼输入法服务。
 *
 * 只做三件事：
 * 1. 持有引擎与输入会话
 * 2. 把「预编辑」与「已上屏」正确地同步给宿主应用
 * 3. 处理硬件键盘与编辑框差异（密码框、IME action）
 */
class ShuangpinImeService : InputMethodService() {

    private lateinit var engine: PinyinEngine
    private lateinit var session: InputSession
    private var keyboard: ShuangpinKeyboardView? = null

    private var candidateBar: LinearLayout? = null
    private var candidateScroll: HorizontalScrollView? = null

    /** 候选栏右侧的页码（"1/3"）。 */
    private var candidateIndicator: TextView? = null

    /**
     * 上一次渲染的候选内容签名。
     *
     * 签名相同就跳过重建 chip——接了联想之后候选数量翻几倍，
     * 每次按键都 `removeAllViews` 重建一遍会明显掉帧。
     */
    private var lastCandidateSig: String? = null

    /**
     * 上一次渲染时的拼音串。
     *
     * 用来判断「是不是换了一轮输入」——换了就要把候选栏翻回第一页。
     */
    private var lastCandidatePinyin: String? = null

    /** 端侧重排：后台线程算，结果到了再更新候选栏。 */
    private val reranker = RerankController(this)

    /**
     * 云端「下一词」预测：默认关闭，开了才会把输入内容发出去。
     *
     * **必须在 onCreate 里建**：字段初始化跑在 Service 构造期，
     * 那时 Context 还没 attachBaseContext，`getSharedPreferences` 会抛——
     * 结果是输入法根本起不来（键盘完全不显示）。
     */
    private var lexeme: LexemeController? = null

    /** 云端给的下一词建议（还没到就是空的）。 */
    private var nextWords: List<String> = emptyList()

    /**
     * 云端按上文消歧后给出的优先顺序（新的在前）。
     *
     * 和 [nextWords] 是两回事：
     * - `nextWords` 是**新出现的词**（「智慧」之后可能打「城市」），做成新的候选 chip；
     * - `cloudBoost` 是**已有候选的重新排序**（「智慧/只会/指挥」里哪个更对），
     *   它不新增 chip，只把命中的那个提到最前面。
     */
    private var cloudBoost: List<String> = emptyList()

    /** 这个消歧结果对应的是哪一轮输入的拼音串（对不上就作废）。 */
    private var cloudBoostPinyin: String? = null

    /**
     * 语音输入：没有输入的时候按住空格说话，松手结束。
     *
     * 跟 [lexeme] 一样，**必须在 onCreate 里建**（构造期没有 Context）。
     */
    private var voice: VoiceController? = null

    /** 语音的状态（录音中 / 识别中），用来在候选栏上提示。 */
    private var voiceState: VoiceController.State = VoiceController.State.IDLE

    /**
     * 纠错建议：刚上屏那段文字 + 大模型给的改正版。
     *
     * `target` 是被纠的那段原文（点一下 chip 时按它的长度删掉再替换），
     * `fixed` 是改正后的整段。
     */
    private var correctionTarget: String? = null
    private var correctionFixed: String? = null

    /** 全部设置（云端模型、端侧重排、语音、纠错）。 */
    private var settings: ImeSettings? = null

    /**
     * 输入停下来之后再问云端。
     *
     * 打字过程中每敲一个键就问一次纯属浪费——用户往往是一串敲完才停下来看候选。
     * 停 280 ms 再问，问的才是「他真正在犹豫的那一串」。
     */
    private val cloudDebounce = Runnable { requestCloudDisambiguation() }

    /** 模型给出的等长候选顺序（新的在前）。 */
    private var rerankedOrder: List<String> = emptyList()

    /** 这个顺序对应的是哪一轮输入的拼音串（对不上就作废）。 */
    private var rerankedPinyin: String? = null

    /** 上一次请求重排的键（拼音串 + 候选集合）。同一批候选不重复请求。 */
    private var lastRerankKey: String? = null

    /**
     * 候选栏的第一个候选——**空格键会上屏的就是它**。
     *
     * 模型重排之后，第一个候选可能已经不是引擎的最优整句了，
     * 空格必须跟着变，否则用户看到的第一候选和空格上屏的会不一致。
     */
    private var topCandidateText: String? = null

    private var editorAction: Int = EditorInfo.IME_ACTION_NONE
    private var isPasswordField: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dictionaryThread: Thread? = null

    /** 当前是否在宿主应用里留着预编辑区。 */
    private var hasComposing: Boolean = false

    /** 当前已设置的输入法窗口高度（px），用来避免重复 relayout。 */
    private var windowHeightPx: Int = 0

    /** 本次会话已上屏的文本（删除全部时要靠它还原内容）。 */
    private val committedText = StringBuilder()

    /** 撤销栈：最近上屏的文本片段。 */
    private val undoStack = ArrayDeque<UndoOp>()

    /**
     * 当前布局模式。
     *
     * 视图那边改 `mode` 的同时会回调 `onModeChanged`，所以这里记一份，
     * 切换时才知道「切换**前**」是中文还是英文——
     * 回调时视图的 mode 已经是新的了，光看它会搞反。
     */
    private var currentMode: ShuangpinKeyboardView.Mode = ShuangpinKeyboardView.Mode.CHINESE

    /** 当前是否处于非中文模式（英文 / 数字 / 标点）。 */
    private val rawMode: Boolean
        get() = currentMode != ShuangpinKeyboardView.Mode.CHINESE

    /**
     * 预编辑区应显示的内容。
     *
     * **只有中文模式才有预编辑内容。**
     *
     * 英文/数字/标点是直接上屏的字符，如果也把它们写进宿主的预编辑区，
     * 宿主里就会同时存在「已上屏的字符」和「预编辑区的同一个字符」两份——
     * 表现就是「按一下键上屏两个字符」，而且宿主的输入校验（记账类 App 常见）
     * 会因为这份重复内容判定文本非法，把字符整个吞掉，于是变成「标点打不进去」。
     */
    private val composing: String
        get() = session.composingText

    override fun onCreate() {
        super.onCreate()

        // 设置：模型、端点、语音接口都在这儿
        settings = ImeSettings(this)

        // 云端联想控制器：必须在这里建（见字段注释）
        lexeme = LexemeController(this)

        // 语音：按住空格说话
        voice = VoiceController(
            context = this,
            onState = { state ->
                mainHandler.post {
                    voiceState = state
                    renderCandidates()
                }
            },
            onText = { text ->
                mainHandler.post { commitVoiceText(text) }
            },
            onError = { msg ->
                mainHandler.post {
                    voiceState = VoiceController.State.IDLE
                    renderCandidates()
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            },
        )

        // 端侧重排：模型在后台线程加载，好了就通知刷新一次。
        // 加载失败不是致命错误——不重排而已，输入法照常用。
        reranker.onReady = {
            mainHandler.post { refresh() }
        }
        reranker.ensureLoaded()

        // 小数据（键位表+音节表）只有几十 KB，同步加载，键盘立刻可用
        val core = DataLoader().loadCore()
        val scheme = core.schemes[SCHEME_ID] ?: error("缺少方案：$SCHEME_ID")
        engine = PinyinEngine(scheme, core.table)
        session = InputSession(engine)

        // 大数据（词库，上百万条）必须后台加载，否则输入法启动会卡住
        dictionaryThread = Thread {
            val dictionary = DataLoader().loadDictionary()
            engine.dictionary = dictionary
            mainHandler.post { refresh() }
        }.apply {
            name = "shuangpin-dict"
            isDaemon = true
            start()
        }
    }

    override fun onDestroy() {
        dictionaryThread?.interrupt()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 输入视图
    // ------------------------------------------------------------------

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFE9EBF0.toInt())
            // 底部留白：给系统导航条（地球切换键那一行）腾位置。
            // 系统是按「输入视图的测量高度」决定输入法窗口高度的，
            // 而导航条是画在窗口底部、盖在内容之上的——
            // 底部不留出这段空间，地球键就会压住键盘的底行。
            setPadding(0, 0, 0, dp(NAV_BAR_SLACK_DP).toInt())
        }

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(6f).toInt(), 0, dp(6f).toInt(), 0)
        }
        candidateBar = bar
        candidateScroll = scroll
        scroll.addView(bar)

        // 候选栏 = [横滑区] + [页码]。
        //
        // 页码是候选栏**内部**的固定一块，不额外占高度——
        // 候选栏高度必须恒定（38dp），一变键盘就上下漂，用户的肌肉记忆就废了
        // （见 README 真机调试笔记 6）。
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val indicator = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(dp(2f).toInt(), 0, dp(4f).toInt(), 0)
            // 一开始就 INVISIBLE 而不是 GONE：GONE 会让横滑区宽度突变，
            // 候选排版跟着跳。用 INVISIBLE 占位，布局永远稳定。
            visibility = View.INVISIBLE
        }
        candidateIndicator = indicator

        // 滚动时实时更新页码；手指抬起后吸附到整页。
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> updatePageInfo() }
        scroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleSnap()
            }
            false
        }

        row.addView(
            scroll,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )
        row.addView(
            indicator,
            LinearLayout.LayoutParams(dp(PAGE_INDICATOR_DP).toInt(), LinearLayout.LayoutParams.MATCH_PARENT),
        )

        // 候选栏里**不再放「设置」按钮**了。
        //
        // 以前放这儿是因为「系统设置 → 语言和输入法 → 键盘 → 设置」那条路太深，
        // 十个用户九个找不到。现在 App 有桌面图标了，设置就在图标里：
        // 点一下图标进设置页，要比在候选栏里挤一个 40dp 的按钮干净得多——
        // 候选栏右侧的页码也能离候选更近一点。

        root.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(CANDIDATE_BAR_DP).toInt(),
            ),
        )

        val kb = ShuangpinKeyboardView(this)
        kb.bind(session, engine)
        kb.listener = object : ShuangpinKeyboardView.Listener {
            override fun onPinyinKey(key: String) {
                session.pressKey(key)
                // 新一轮输入开始了：上一轮的下一词建议跟这轮没关系，
                // 留着只会挤占候选栏，还会让人以为那些词是这一轮的候选。
                resetLexeme()
                refresh()
                scheduleCloudDisambiguation()
            }

            override fun onRawChar(text: String) {
                // 数字/符号/英文**直接上屏**，不用再按一次空格。
                // 双拼的中文输入要靠空格确认整句，但这些字符没有「候选」可言，
                // 按一下才上屏太反直觉（用户反馈「必须按一下空格键，否则不能上屏」）。
                //
                // 关键：**一个字符只上屏一次，且绝不进预编辑区**。
                commitRaw(text)
            }

            override fun onSpace() {
                onSpacePressed()
            }

            override fun onBackspace() {
                onBackspacePressed()
            }

            override fun onSwipeUpDelete() {
                swipeUpDelete()
            }

            override fun onUndo() {
                undo()
            }

            override fun onModeChanged(mode: ShuangpinKeyboardView.Mode) {
                switchMode(mode)
            }

            override fun onCapsChanged(upper: Boolean) {
                // 大写状态由键盘自己维护，这里不需要额外处理
            }

            override fun onSpaceDown(): Boolean = canTalkBySpace()

            override fun onVoiceStart() {
                startVoice()
            }

            override fun onVoiceStop() {
                voice?.stop()
            }

            override fun onVoiceCancel() {
                voice?.cancel()
            }
        }
        keyboard = kb
        root.addView(
            kb,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                // 键盘高度固定不变——按键位置必须始终在同一个地方，
                // 否则候选栏一出现键盘就整体下移，用户瞄准的第二个键会按空。
                dp(KEYBOARD_HEIGHT_DP).toInt(),
            ),
        )
        applyWindowHeight()
        return root
    }

    /**
     * 显式设定输入法窗口高度。
     *
     * 只靠 onComputeInsets 的 contentTopInsets 在部分 ROM（含本机 MagicOS）会被忽略，
     * 结果键盘被撑满整屏、键被拉成又窄又高。这里直接改 Window 的 LayoutParams 才稳。
     */
    private fun applyWindowHeight() {
        val win = window?.window ?: return
        if (windowHeightPx == 0) {
            windowHeightPx = dp(IME_WINDOW_HEIGHT_DP).toInt()
            val lp = win.attributes
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = windowHeightPx
            win.attributes = lp
        }
        applySoftInputMode()
    }

    /**
     * 告诉系统输入法的内容区和可触摸区域。
     *
     * 这里**只报可触摸区域**，contentTopInsets 交给框架默认处理——
     * 之前手动报 contentTopInsets = 窗口高度，结果宿主应用（Via 浏览器这种
     * 地址栏在底部的）根本不缩放窗口，输入框被键盘盖住。
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val decor = window?.window?.decorView
        val w = decor?.width?.coerceAtLeast(1) ?: 1
        val h = decor?.height?.coerceAtLeast(1) ?: 1
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, 0, w, h)
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        session.clear()
        undoStack.clear()
        committedText.clear()
        // 换了一个输入框：上一处的联想一个字都不该带过来
        resetLexeme()

        val inputType = info?.inputType ?: 0
        isPasswordField = (inputType and InputType.TYPE_MASK_VARIATION) ==
            InputType.TYPE_TEXT_VARIATION_PASSWORD

        // 密码框禁止截屏（截图里会带上输入内容）。
        // 注意：InputMethodService.getWindow() 返回的是 Dialog，真正的 Window 在 Dialog 里。
        val dialogWindow = window?.window
        if (isPasswordField) {
            dialogWindow?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        editorAction = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        refresh()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyWindowHeight()
        applySoftInputMode()
        refresh()
    }

    /**
     * IME 窗口的 softInputMode 必须是 **ADJUST_RESIZE**。
     *
     * 框架默认给输入法窗口设 `adjust=pan`，后果是宿主应用（Via 浏览器这种
     * 地址栏在底部的）根本不缩放窗口，输入框被键盘盖住。
     *
     * 这个值要在几个时机都设一遍：在 `onCreateInputView` 会被框架覆盖，
     * 在 `onStartInputView` 才稳定；直接改 LayoutParams 再赋值回去最可靠。
     */
    private fun applySoftInputMode() {
        val win = window?.window ?: return
        val lp = win.attributes
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        win.attributes = lp
    }

    override fun onWindowShown() {
        super.onWindowShown()
        applySoftInputMode()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        session.clear()
        // 键盘要收起来了，候选栏里的联想也就没有意义了
        resetLexeme()
        if (hasComposing) {
            currentInputConnection?.setComposingText("", 1)
            hasComposing = false
        }
    }

    // ------------------------------------------------------------------
    // 预编辑与上屏
    // ------------------------------------------------------------------

    private fun refresh() {
        val ic = currentInputConnection ?: return

        // 预编辑区显示已输入内容，让用户看到自己打的是什么。
        // 没有预编辑内容时不要反复调 setComposingText("")——那会动宿主的光标，
        // 有些应用会因此出现光标跳动。
        val text = composing
        if (text.isNotEmpty()) {
            ic.setComposingText(text, 1)
            hasComposing = true
        } else if (hasComposing) {
            ic.setComposingText("", 1)
            hasComposing = false
        }
        renderCandidates()
        keyboard?.invalidate()
    }

    private fun renderCandidates() {
        val bar = candidateBar ?: return
        val scroll = candidateScroll ?: return

        // 非中文模式没有候选，候选栏整体隐藏但**保留占位**（INVISIBLE 而非 GONE），
        // 否则布局塌陷、键盘上移，键位就漂了。
        if (rawMode) {
            scroll.visibility = View.INVISIBLE
            candidateIndicator?.visibility = View.INVISIBLE
            lastCandidateSig = null
            lastCandidatePinyin = null
            topCandidateText = null
            scroll.scrollTo(0, 0)
            return
        }

        // 语音进行中：候选栏只显示状态，别让用户以为那些候选还能点
        if (voiceState != VoiceController.State.IDLE) {
            showVoiceStatus()
            return
        }

        val hasCandidates = session.pending.isNotEmpty()
        // 上屏之后没有预编辑内容了，但**下一词建议还得显示**——
        // 那正是「用户刚打完一个词，接下来可能想打什么」的用处所在。
        val hasNext = nextWords.isNotEmpty()
        val hasCorrection = correctionFixed != null
        scroll.visibility = if (hasCandidates || hasNext || hasCorrection) {
            View.VISIBLE
        } else {
            View.INVISIBLE
        }
        if (!hasCandidates && !hasNext && !hasCorrection) {
            candidateIndicator?.visibility = View.INVISIBLE
            lastCandidateSig = null
            lastCandidatePinyin = null
            topCandidateText = null
            scroll.scrollTo(0, 0)
            return
        }

        // 候选内容没变就不重建 chip（见 lastCandidateSig 的注释）。
        // 候选的装配规则在引擎层（Candidates.build）——那里定义的才是
        // 「这个输入法给出什么候选」，端侧模型评估用的是同一份逻辑。
        // 自动纠错候选只在这里打开（评估端默认关，基线才可比）。
        val items = Candidates.build(engine, session, includeCorrections = true)
        val ordered = applyRerank(items)
        val boosted = applyCloudBoost(ordered)
        val withNext = insertNextWords(boosted)
        val withCorrection = insertCorrection(withNext)
        val sig = session.pinyin + "|" + withCorrection.joinToString("|") { it.text }
        if (sig == lastCandidateSig) {
            updatePageInfo()
            return
        }
        // 换了新一轮输入（拼音串变了）就回到第一页。
        // 不复位的话：用户滑到第 3 页，再打一个字，
        // 新候选还停在第 3 页——看起来像「候选没更新」。
        val newRound = session.pinyin != lastCandidatePinyin
        lastCandidatePinyin = session.pinyin
        lastCandidateSig = sig

        bar.removeAllViews()
        withCorrection.forEachIndexed { i, item -> addChip(item, isFirst = i == 0) }
        // 第一候选 = 空格会上屏的那个
        topCandidateText = withCorrection.firstOrNull()?.text
        // 布局完才知道总宽与页数
        bar.post {
            if (newRound) scroll.scrollTo(0, 0)
            updatePageInfo()
        }

        // 让模型重排等长候选（后台线程，结果到了再刷新）
        requestRerank(items)
    }

    /**
     * 等长候选：**长度等于音节数**的候选（一个音节对应一个字）。
     *
     * 注意不只是整句与备选整句——词候选里也有等长的
     * （「zhihui」的候选有「智慧」「指挥」这些词，和整句一样是 2 字）。
     * 评测集里就是按「长度 == 音节数」划的等长候选，
     * 这里必须用同一条口径，否则喂给模型的池子跟评估时不是一个池子。
     */
    private fun equalLengthCandidates(items: List<Candidate>): List<Candidate> {
        val syllables = session.pending.size
        if (syllables <= 0) return emptyList()
        return items.filter { it.text.length == syllables }
    }

    /**
     * 把模型重排的结果套到候选上。
     *
     * **只动等长候选**——模型的训练分布如此。
     * 把单字和词也塞给它，它会偏向短候选：实测整句准确率从 77% 砸到 32%。
     */
    private fun applyRerank(items: List<Candidate>): List<Candidate> {
        // 纠错候选**不参与重排**：端侧模型是按「这串按键真的能打出来」训的，
        // 纠错候选对应的拼音跟用户实际敲的键对不上，
        // 喂给它只会让它排到最后——那就白纠了。
        // （本地拼音容错 CORRECTION 和云端整句纠错 CORRECTION_TEXT 都不参与。）
        val corrections = items.filter { isCorrectionKind(it.kind) }
        val rest = items.filter { !isCorrectionKind(it.kind) }
        val order = rerankedOrder
        if (order.isEmpty() || rerankedPinyin != session.pinyin) return corrections + rest
        val rank = HashMap<String, Int>()
        order.forEachIndexed { i, text -> rank[text] = i }
        val equal = equalLengthCandidates(rest)
        val others = rest.filter { it.text.length != session.pending.size }
        // sortedBy 是稳定排序，没在模型结果里的排最后
        return corrections + equal.sortedBy { rank[it.text] ?: Int.MAX_VALUE } + others
    }

    /**
     * 把云端按上文消歧的结果套到候选上。
     *
     * 云端只能**重新排序**已有候选，不能新增——
     * 它要是给出一个用户根本按不出来的词，那个词就不该出现在候选栏里
     * （引擎层的 promote 会把它滤掉）。
     *
     * 它排在端侧重排**之后**：端侧模型看的是「这串按键最可能接什么」，
     * 云端看的是「结合上文哪个候选更说得通」——后者更贴近用户真实意图。
     */
    private fun applyCloudBoost(items: List<Candidate>): List<Candidate> {
        if (cloudBoost.isEmpty()) return items
        if (cloudBoostPinyin != session.pinyin) return items
        return Candidates.promote(cloudBoost, items)
    }

    /**
     * 请求模型重排。
     *
     * 同一批候选不重复请求——否则「重排 → 刷新 → 又触发重排」会绕圈。
     */
    private fun requestRerank(items: List<Candidate>) {
        // 纠错候选不进重排池（原因见 applyRerank）
        val pool = items.filter { !isCorrectionKind(it.kind) }
        val equal = equalLengthCandidates(pool)
        if (equal.size < 2 || !reranker.ready) return
        val key = session.pinyin + "|" + equal.joinToString("|") { it.text }
        if (key == lastRerankKey) return
        lastRerankKey = key

        val ctx = rerankContext()
        val pinyin = session.pinyin
        val t = reranker.newToken()
        reranker.request(ctx, equal.map { it.text }, t) { ranked ->
            mainHandler.post {
                rerankedOrder = ranked
                rerankedPinyin = pinyin
                renderCandidates()
            }
        }
    }

    /**
     * 重排模型的上文：**本会话已上屏的中文文本**。
     *
     * 不用宿主的 `getTextBeforeCursor`：那里会把正在输入的拼音串（预编辑区）
     * 一起算进去——模型只认中文，那串拉丁字母全是噪声；
     * 而且有些宿主（WebView 型的输入框）直接返回 null，上文就空了。
     */
    private fun rerankContext(): String {
        val text = committedText.toString()
        return if (text.length > RERANK_CONTEXT_CHARS) text.takeLast(RERANK_CONTEXT_CHARS) else text
    }

    private fun addChip(item: Candidate, isFirst: Boolean) {
        val tv = TextView(this).apply {
            text = item.text
            textSize = when (item.kind) {
                CandidateKind.SENTENCE -> 20f
                // 纠错候选字号稍大一点：它是最需要被看见的那个
                CandidateKind.CORRECTION -> 19f
                CandidateKind.SENTENCE_ALT -> 18f
                CandidateKind.WORD, CandidateKind.CHAR -> 18f
                // 扩展候选、下一词建议：字号小一档，视觉上和主候选分开
                CandidateKind.EXTENSION, CandidateKind.NEXT_WORD -> 17f
                // 云端整句纠错：一整句话，字号小一档才放得下
                CandidateKind.CORRECTION_TEXT -> 16f
            }
            setTextColor(
                when {
                    // 第一候选就是「空格会上屏的那个」，用主候选的样式
                    isFirst -> 0xFFA8620A.toInt()
                    item.kind == CandidateKind.SENTENCE_ALT -> 0xFF3F4757.toInt()
                    item.kind == CandidateKind.EXTENSION -> 0xFF5B6472.toInt()
                    // 下一词建议：蓝色，和本地候选区分开
                    item.kind == CandidateKind.NEXT_WORD -> 0xFF2F6DF6.toInt()
                    // 自动纠错（本地拼音容错）：深绿——「这条是帮你改过的」
                    item.kind == CandidateKind.CORRECTION -> 0xFF0F7B4A.toInt()
                    // 云端整句纠错：紫红——「这是大模型改的整句」
                    item.kind == CandidateKind.CORRECTION_TEXT -> 0xFF8E44AD.toInt()
                    // 云端按上文消歧后提上来的：深蓝，一眼能看出「这个是结合上文挑的」
                    item.promoted -> 0xFF1F5FBF.toInt()
                    else -> 0xFF171B23.toInt()
                }
            )
            setPadding(dp(12f).toInt(), dp(4f).toInt(), dp(12f).toInt(), dp(4f).toInt())
            if (isFirst) setBackgroundColor(0xFFFFF8E8.toInt())
            else if (item.kind == CandidateKind.CORRECTION) setBackgroundColor(0xFFE4F4EA.toInt())
            else if (item.kind == CandidateKind.CORRECTION_TEXT) setBackgroundColor(0xFFF3E8F8.toInt())
            else if (item.promoted) setBackgroundColor(0xFFEDF3FE.toInt())
            setOnClickListener {
                when (item.kind) {
                    // 单字是「前面的音节 + 这个字」
                    CandidateKind.CHAR -> commitChar(item.text)
                    // 云端整句纠错：把已上屏的那段换掉
                    CandidateKind.CORRECTION_TEXT -> applyCorrection()
                    // 其余是整条上屏
                    else -> commitText(item.text)
                }
            }
        }
        candidateBar?.addView(tv)
    }

    /** 是不是「纠错」类的候选（本地拼音容错 + 云端整句纠错）。 */
    private fun isCorrectionKind(kind: CandidateKind): Boolean =
        kind == CandidateKind.CORRECTION || kind == CandidateKind.CORRECTION_TEXT

    // ------------------------------------------------------------------
    // 候选栏翻页
    // ------------------------------------------------------------------

    /**
     * 刷新页码。
     *
     * 内容只有一页时把页码藏起来（INVISIBLE，不改布局）。
     */
    private fun updatePageInfo() {
        val scroll = candidateScroll ?: return
        val indicator = candidateIndicator ?: return
        val bar = candidateBar ?: return
        val pageW = scroll.width
        if (pageW <= 0 || bar.childCount == 0) {
            indicator.visibility = View.INVISIBLE
            return
        }
        // 内容总宽：逐个累加 chip 的宽度（不依赖 bar.width，
        // 因为 fillViewport 会把 bar 拉宽到视口宽）
        var contentW = 0
        for (i in 0 until bar.childCount) contentW += bar.getChildAt(i).width
        val pages = if (contentW <= pageW) 1 else (contentW + pageW - 1) / pageW
        val current = (scroll.scrollX / pageW) + 1
        if (pages <= 1) {
            indicator.visibility = View.INVISIBLE
            indicator.text = ""
        } else {
            indicator.visibility = View.VISIBLE
            indicator.text = "$current/$pages"
        }
    }

    /** 手指抬起后延迟一点再吸附——手一抬就跳会让用户以为误触。 */
    private fun scheduleSnap() {
        mainHandler.removeCallbacks(snapToPage)
        mainHandler.postDelayed(snapToPage, SNAP_DELAY_MS)
    }

    /** 吸附到最近的整页边界。 */
    private val snapToPage = Runnable {
        val scroll = candidateScroll ?: return@Runnable
        val pageW = scroll.width
        if (pageW <= 0) return@Runnable
        val maxScroll = ((scroll.getChildAt(0)?.width ?: 0) - pageW).coerceAtLeast(0)
        val target = (Math.round(scroll.scrollX.toFloat() / pageW).toInt() * pageW)
            .coerceIn(0, maxScroll)
        if (target != scroll.scrollX) scroll.smoothScrollTo(target, 0)
    }

    /**
     * 把文本真正上屏到宿主应用。
     *
     * 文本**怎么算出来**由 session 负责，这里只管往宿主写——
     * 之前服务层自己又算了一遍，导致候选栏显示「我想吃饭」而实际上屏成了「我想吃翻」。
     */
    private fun commitToHost(text: String) {
        if (text.isEmpty()) return
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        // commitText 会顶掉宿主里的预编辑区；顶不掉的（有些 App 自己实现了 InputConnection）
        // 这里再补一次清理，别让「已上屏」和「预编辑」两份内容并存。
        clearComposing()
        committedText.append(text)
        pushUndo(text)
        // 上下文变了，上一轮的下一词建议作废
        nextWords = emptyList()
        // 上一轮的云端消歧同样作废（它对应的是刚才那串拼音）
        cloudBoost = emptyList()
        cloudBoostPinyin = null
        refresh()
        // 云端下一词预测：上屏之后异步问一次
        requestNextWords()
        // 自动纠错：上屏之后问一次大模型，这段有没有错别字
        requestCorrection(text)
    }

    /**
     * 请求云端的「下一词」建议。
     *
     * 只在**中文**上屏后触发——英文/数字后面接什么词，没意义。
     * 失败就当没有：候选栏该怎样还怎样。
     */
    private fun requestNextWords() {
        val lc = lexeme ?: return
        if (!lc.enabled) return
        val ctx = committedText.toString()
        if (ctx.none { it.code in 0x4E00..0x9FFF }) return
        lc.request(
            LexemeRequest(text = ctx.takeLast(LEXEME_CONTEXT_CHARS)),
            CHANNEL_NEXT,
        ) { words ->
            if (words.isEmpty()) return@request
            mainHandler.post {
                // 期间用户已经开始了新一轮输入 → 这个建议过时了，丢掉
                if (session.pending.isNotEmpty()) return@post
                nextWords = words
                renderCandidates()
            }
        }
    }

    /**
     * 输入停下来之后，按「上文 + 当前拼音 + 候选池」问一次云端。
     *
     * 这一步是「提高输入准确率」的关键：
     * 本地分词分不出「智慧」还是「指挥」，但**有了上文就能判断**——
     * 上文是「智慧城市」的时候，用户敲「zhihui」多半是想接着说「智慧」，
     * 而不是「指挥」。本地模型看不到那么长的上下文，云端看得见。
     *
     * 云端只能**重新排序**已有候选，不能凭空造词——
     * 用户按不出来的词进了候选栏就是bug。
     */
    private fun requestCloudDisambiguation() {
        val lc = lexeme ?: return
        if (!lc.enabled) return
        // 拼音还是半截的时候问了也没意义——至少要有一个完整的音节
        if (session.stage != InputStage.SYLLABLE) return
        val pinyin = session.pinyin
        if (pinyin.isEmpty()) return
        // 候选池里带上纠错候选：用户十有八九是想打那个被纠正出来的词
        val pool = Candidates.build(engine, session, includeCorrections = true).map { it.text }
        if (pool.isEmpty()) return
        val ctx = committedText.toString().takeLast(LEXEME_CONTEXT_CHARS)
        lc.request(
            LexemeRequest(
                text = ctx,
                pinyin = pinyin,
                candidates = pool.take(CLOUD_POOL_LIMIT),
                model = settings?.llmModel ?: "",
                apiKey = settings?.llmApiKey ?: "",
                temperature = settings?.llmTemperature ?: 0.3f,
            ),
            CHANNEL_DISAMBIGUATE,
        ) { words ->
            if (words.isEmpty()) return@request
            mainHandler.post {
                // 已经换了输入 → 这个消歧结果作废
                if (session.pinyin != pinyin) return@post
                cloudBoost = words
                cloudBoostPinyin = pinyin
                renderCandidates()
            }
        }
    }

    /** 用户停下来一会儿才问云端——一串敲完再问，问的才是他真正在犹豫的那一串。 */
    private fun scheduleCloudDisambiguation() {
        mainHandler.removeCallbacks(cloudDebounce)
        mainHandler.postDelayed(cloudDebounce, CLOUD_DEBOUNCE_MS)
    }

    /** 候选栏里现在有没有联想内容（下一词建议，或云端消歧提上来的候选）。 */
    private val hasLexeme: Boolean
        get() = nextWords.isNotEmpty() || cloudBoost.isNotEmpty() || correctionFixed != null

    /**
     * 停掉联想。
     *
     * 清空候选栏里的联想内容，并作废在途的云端请求——
     * 请求晚回来也不能再把联想塞回候选栏。
     *
     * **只停「下一词」和「消歧」两条通道**：
     * 纠错是针对**已经上屏**的文字的，跟当前这一轮输入没关系，
     * 用户开始打字不该把纠错结果一起作废掉。
     */
    private fun resetLexeme() {
        nextWords = emptyList()
        cloudBoost = emptyList()
        cloudBoostPinyin = null
        mainHandler.removeCallbacks(cloudDebounce)
        lexeme?.cancel(CHANNEL_NEXT)
        lexeme?.cancel(CHANNEL_DISAMBIGUATE)
    }

    /**
     * 语音进行中，候选栏只显示一行状态。
     *
     * 不显示候选是有意的：录音的时候用户看不清字，
     * 那些候选也点不中（点一下就变成别的操作了）。
     */
    private fun showVoiceStatus() {
        val bar = candidateBar ?: return
        val scroll = candidateScroll ?: return
        val text = when (voiceState) {
            VoiceController.State.RECORDING -> "● 录音中… 松手结束"
            VoiceController.State.RECOGNIZING -> "识别中…"
            VoiceController.State.IDLE -> ""
        }
        scroll.visibility = View.VISIBLE
        candidateIndicator?.visibility = View.INVISIBLE
        lastCandidateSig = null
        topCandidateText = null
        val old = bar.getChildAt(0)
        if (bar.childCount == 1 && old is TextView && old.text == text) return
        bar.removeAllViews()
        bar.addView(
            TextView(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(
                    if (voiceState == VoiceController.State.RECORDING) 0xFFC62828.toInt()
                    else 0xFF5B6472.toInt()
                )
                setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
            }
        )
    }

    /**
     * 把云端给的「下一词」建议插进候选栏（单字之前）。
     *
     * 插在单字前面：下一词是「接下来该打什么」，单字是「这个音节有哪些字」，
     * 前者更该被看见。
     */
    private fun insertNextWords(items: List<Candidate>): List<Candidate> {
        if (nextWords.isEmpty()) return items
        val at = items.indexOfFirst { it.kind == CandidateKind.CHAR }
            .let { if (it < 0) items.size else it }
        val next = nextWords.map { Candidate(it, CandidateKind.NEXT_WORD) }
        return items.toMutableList().apply { addAll(at, next) }
    }

    /**
     * 把「云端整句纠错建议」放到候选栏**最后**。
     *
     * 放最后是有意的：
     * - 它不是候选词，是一整句改正后的文字，抢第一的位置会让人误按空格；
     * - 本地拼音容错的纠错候选（引擎给的 CORRECTION）才应该排在最前面。
     */
    private fun insertCorrection(items: List<Candidate>): List<Candidate> {
        val fixed = correctionFixed ?: return items
        return items + Candidate(fixed, CandidateKind.CORRECTION_TEXT)
    }

    /**
     * 原始字符（英文 / 数字 / 标点）上屏。
     *
     * 和中文上屏只差两点：
     * 1. 没有候选，不需要按空格确认；
     * 2. **绝不进预编辑区**。
     *
     * 第二点是「按一下键上屏两个字符」的根因：
     * 字符已经 commit 给宿主了，如果宿主里又有一份预编辑区里的同样的字符，
     * 用户看到的就是两个。而且记账这类 App 会在 onTextChanged 里校验金额格式，
     * 重复出来的第二个字符会让文本变成非法格式，于是整个字符被 App 吞掉——
     * 表现成「这个 App 里标点打不进去」。
     */
    private fun commitRaw(text: String) {
        if (text.isEmpty()) return
        val ic = currentInputConnection ?: return
        // 从中文模式切过来时可能还留着半截拼音的预编辑区，先收掉，
        // 否则这次 commit 的字符会把预编辑区顶掉，那半个拼音就凭空消失了。
        clearComposing()
        ic.commitText(text, 1)
        committedText.append(text)
        pushUndo(text)
        refresh()
    }

    /** 清掉宿主里的预编辑区，不留残影。 */
    private fun clearComposing() {
        if (!hasComposing) return
        currentInputConnection?.setComposingText("", 1)
        hasComposing = false
    }

    private fun commitText(text: String) {
        session.commitText(text)
        commitToHost(text)
    }

    private fun commitChar(ch: String) {
        // 同样统一走 session 里的实现，别在服务层再抄一遍
        val text = session.commitChar(ch)
        commitToHost(text)
    }

    private fun onSpacePressed() {
        if (rawMode) {
            // 英文/数字/标点模式下字符是直接上屏的，没有「待上屏的缓冲」，
            // 所以空格就只是一个普通的空格字符。
            // （之前这里因为缓冲恒为空，空格变成了「触发编辑框 action」，
            //   结果英文里根本打不出空格。）
            commitRaw(" ")
            return
        }
        // 空格上屏「候选栏的第一个候选」。
        //
        // 模型重排之后，第一个候选可能已经不是引擎的最优整句了——
        // 那它就是新的「整句」，空格必须跟着变，
        // 否则用户看到的第一候选和空格上屏的会是两个东西。
        val top = topCandidateText
        if (top != null && session.pending.isNotEmpty()) {
            commitText(top)
            return
        }
        // 没有预编辑内容，但有云端给的下一词建议：空格把它打上去
        if (top != null && nextWords.isNotEmpty()) {
            commitNextWord(top)
            return
        }
        // 候选栏还没有内容（不该发生），退回引擎的最优切分
        val text = session.commitTop()
        if (text != null) {
            commitToHost(text)
            return
        }
        performEditorActionIfAny()
    }

    /**
     * 上屏一个云端给的下一词建议。
     *
     * 它不是引擎算出来的，所以不走 `commitText`（那会动会话状态），
     * 但要记进 `committedText`——上下文要跟着长，下一轮预测才准。
     */
    private fun commitNextWord(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        session.commitText(text)
        committedText.append(text)
        pushUndo(text)
        nextWords = emptyList()
        refresh()
    }

    private fun performEditorActionIfAny() {
        // 没有预编辑内容时，若编辑框声明了 action（如「搜索」），直接触发
        if (editorAction != EditorInfo.IME_ACTION_NONE) {
            currentInputConnection?.performEditorAction(editorAction)
        }
    }

    private fun onBackspacePressed() {
        if (rawMode) {
            // 原始字符是直接上屏的，退格直接删宿主里的一个字
            currentInputConnection?.deleteSurroundingText(1, 0)
            return
        }
        val ic = currentInputConnection ?: return
        // 会话内部还有内容时先回退会话
        if (session.stage != InputStage.INITIAL || session.pending.isNotEmpty()) {
            if (session.backspace()) {
                refresh()
                return
            }
        }
        // 没有待上屏的输入，但候选栏里还挂着联想词：
        // 这一下退格先把联想清掉（并停掉在途的云端请求），
        // 而不是去删宿主里的字——用户按退格就是想「把这儿的联想清掉」。
        if (hasLexeme) {
            resetLexeme()
            refresh()
            return
        }
        // 删宿主应用里的一个字
        ic.deleteSurroundingText(1, 0)
        // 上下文也跟着变：不然下一轮联想会基于已经删掉的字
        if (committedText.isNotEmpty()) committedText.setLength(committedText.length - 1)
        // 上文被删光了，联想也就没有依据了
        if (committedText.isEmpty() && hasLexeme) {
            resetLexeme()
            refresh()
        }
    }

    // ------------------------------------------------------------------
    // 语音输入：没有输入的时候按住空格说话，松手结束
    // ------------------------------------------------------------------

    /**
     * 这一下空格能不能拿去说话。
     *
     * 条件：
     * - 语音功能开了、识别接口地址填了
     * - 当前**没有待上屏的输入**——用户正在打拼音的时候，
     *   空格是「上屏」，不能拿去说话
     */
    private fun canTalkBySpace(): Boolean {
        val s = settings ?: return false
        if (!s.voiceEnabled) return false
        if (rawMode) return false
        if (session.pending.isNotEmpty() || composing.isNotEmpty()) return false
        if (voice?.isBusy == true) return false
        if (s.asrEndpoint.isBlank()) return false
        return true
    }

    /** 开始录音。 */
    private fun startVoice() {
        val s = settings ?: return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(
                this, "需要录音权限，去设置里授予", android.widget.Toast.LENGTH_LONG
            ).show()
            openSettings()
            return
        }
        val cfg = AsrConfig(
            endpoint = s.asrEndpoint,
            model = s.asrModel,
            apiKey = s.asrApiKey,
            language = s.asrLanguage,
            protocol = s.asrProtocol,
        )
        voice?.start(cfg)
    }

    /** 识别结果上屏。 */
    private fun commitVoiceText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val ic = currentInputConnection ?: return
        ic.commitText(t, 1)
        committedText.append(t)
        pushUndo(t)
        refresh()
    }

    /** 打开设置页。 */
    private fun openSettings() {
        try {
            startActivity(
                Intent(this, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (ignored: Throwable) {
            // 某些 ROM 不让 IME 拉起 Activity，忽略
        }
    }

    // ------------------------------------------------------------------
    // 自动纠错
    // ------------------------------------------------------------------

    /**
     * 上屏之后问一次大模型：这段文字有没有错别字。
     *
     * 结果有两种落点（看设置里的「直接改正」开关）：
     * - 关：候选栏给一个「纠错建议」chip，点了才改（稳）
     * - 开：识别出明显错别字就直接改（快，但有误改风险）
     */
    private fun requestCorrection(text: String) {
        val s = settings ?: return
        val lc = lexeme ?: return
        if (!s.correctEnabled || !lc.enabled) return
        if (text.length < MIN_CORRECT_CHARS) return
        if (text.any { it.code !in 0x4E00..0x9FFF }) return   // 含英文/数字的不纠
        // 上文 = 刚才那段之前的内容
        val before = committedText.toString()
            .removeSuffix(text)
            .takeLast(LEXEME_CONTEXT_CHARS)
        lc.request(
            LexemeRequest(
                text = before,
                input = text,
                model = s.llmModel,
                apiKey = s.llmApiKey,
                temperature = s.llmTemperature,
            ),
            CHANNEL_CORRECT,
        ) { words ->
            val fixed = words.firstOrNull()?.trim()
            if (fixed.isNullOrEmpty() || fixed == text) return@request
            mainHandler.post {
                // 期间用户又改了这段文字 → 作废
                if (!committedText.toString().endsWith(text)) return@post
                correctionTarget = text
                correctionFixed = fixed
                if (s.correctAutoApply) {
                    applyCorrection()
                } else {
                    renderCandidates()
                }
            }
        }
    }

    /** 把纠错建议真正用上：删掉原来那段，插入改正后的。 */
    private fun applyCorrection() {
        val target = correctionTarget ?: return
        val fixed = correctionFixed ?: return
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(target.length, 0)
        ic.commitText(fixed, 1)
        // 上下文也跟着改
        val cur = committedText.toString()
        if (cur.endsWith(target)) {
            committedText.setLength(cur.length - target.length)
            committedText.append(fixed)
        }
        correctionTarget = null
        correctionFixed = null
        refresh()
    }

    // ------------------------------------------------------------------
    // 退格键手势：上滑逐格退 / 下滑撤销
    // ------------------------------------------------------------------

    /**
     * 一次可撤销的操作。
     *
     * 上滑删除和下滑撤销是一对：上滑删掉的内容要能原样恢复，
     * 所以撤销栈里存的是「操作」而不是单纯的文本。
     */
    private sealed interface UndoOp {
        /** 撤销一次上屏：删掉这么多个字符 */
        data class Delete(val length: Int) : UndoOp

        /** 撤销一次删除：插入这段文本 */
        data class Insert(val text: String) : UndoOp

        /** 撤销一次上滑删除：把删掉的音节放回会话 */
        data class RestoreInput(val removal: SwipeRemoval) : UndoOp
    }

    /**
     * 退格键上滑：**一次退一格**，从后往前退。
     *
     * "nihao" → 退成 "ni" → 退成空 → 再退就是删已上屏的文字。
     *
     * 为什么不一步删光：用户上滑往往只是想退回一两个音节重打，
     * 一步全删等于把已经打对的字也丢了。
     */
    private fun swipeUpDelete() {
        // ① 先退当前这一轮输入（一次一个音节）
        val removal = session.swipeDelete()
        if (removal != null) {
            undoStack.addLast(UndoOp.RestoreInput(removal))
            refresh()
            return
        }
        // ② 没有输入可退了：删掉已上屏的文字
        deleteCommitted()
    }

    /** 删掉本会话已上屏的全部文字（可撤销）。 */
    private fun deleteCommitted() {
        val ic = currentInputConnection ?: return
        val deleted = committedText.toString()
        if (deleted.isEmpty()) return
        ic.deleteSurroundingText(deleted.length, 0)
        // 记一笔「插入」，滑下来就能原样恢复
        undoStack.addLast(UndoOp.Insert(deleted))
        committedText.clear()
        session.clear()
        // 上文没了，联想也就没有依据了
        resetLexeme()
        refresh()
    }

    /** 下滑：撤销最近一次操作。 */
    private fun undo() {
        val ic = currentInputConnection ?: return
        val op = undoStack.removeLastOrNull() ?: return
        when (op) {
            is UndoOp.Delete -> {
                ic.deleteSurroundingText(op.length, 0)
                val keep = (committedText.length - op.length).coerceAtLeast(0)
                committedText.setLength(keep)
            }

            is UndoOp.Insert -> {
                ic.commitText(op.text, 1)
                committedText.append(op.text)
            }

            is UndoOp.RestoreInput -> {
                // 把上滑删掉的音节放回会话，预编辑区跟着恢复
                session.undoSwipe(op.removal)
            }
        }
        refresh()
    }

    private fun pushUndo(text: String) {
        if (text.isEmpty()) return
        undoStack.addLast(UndoOp.Delete(text.length))
        while (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
    }

    // ------------------------------------------------------------------
    // 布局切换
    // ------------------------------------------------------------------

    private fun switchMode(next: ShuangpinKeyboardView.Mode) {
        // 切换前先把当前这一轮输入上屏，别让用户白打。
        //
        // 判断要用「切换**前**」的模式（currentMode）——回调到这儿的时候，
        // 视图的 mode 已经被改成新模式了，光看它会把「中文→英文」当成
        // 「英文→中文」，结果中文那半轮拼音根本不上屏。
        if (currentMode == ShuangpinKeyboardView.Mode.CHINESE) {
            session.commitTop()?.let { commitToHost(it) }
            // 只按了声母、没定音节的半截拼音没法上屏，直接丢掉。
            // 别让它留在预编辑区里：下一个字符会把它顶掉，那半个拼音就凭空消失了。
            if (session.composingText.isNotEmpty()) {
                session.clear()
                clearComposing()
            }
        }
        currentMode = next
        keyboard?.mode = next
        refresh()
    }

    // ------------------------------------------------------------------
    // 硬件键盘
    // ------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            val ch = ('a' + (keyCode - KeyEvent.KEYCODE_A)).toString()
            if (rawMode) {
                // 和触摸键盘保持一致：直接上屏，不进预编辑区
                commitRaw(ch)
            } else {
                session.pressKey(ch)
                refresh()
            }
            return true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> { onSpacePressed(); true }
            KeyEvent.KEYCODE_DEL -> { onBackspacePressed(); true }
            KeyEvent.KEYCODE_ENTER -> {
                if (!rawMode && session.pending.isNotEmpty()) onSpacePressed()
                else performEditorActionIfAny()
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ------------------------------------------------------------------

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        /**
         * 键盘本体高度（dp），**固定不变**。
         * 键位坐标由它决定，所以它一变，用户的肌肉记忆就废。
         */
        const val KEYBOARD_HEIGHT_DP = 224f

        /**
         * 候选栏高度（dp）。
         * 这一条**永远占位**，没候选时用 INVISIBLE 而不是 GONE——
         * GONE 会让布局塌陷、键盘跟着往上跳，用户的第二个键就按空了。
         */
        const val CANDIDATE_BAR_DP = 38f

        /**
         * 输入法窗口高度（dp），**固定不变** = 候选栏 38 + 键盘 224 + 底部留白 56。
         */
        const val IME_WINDOW_HEIGHT_DP = 318f

        /**
         * 内容区高度（dp）= 候选栏 38 + 键盘 224。
         *
         * onComputeInsets 报的是**内容区**高度，不是整个窗口——
         * 报窗口高度的话，宿主应用会把输入框挤得更靠上，某些 App 里反而被遮住。
         */
        const val CONTENT_HEIGHT_DP = 262f

        /**
         * 根布局底部留白（dp），给系统导航条（地球切换键那一行）腾位置。
         *
         * 系统按输入视图的测量高度决定输入法窗口高度，
         * 而导航条是画在窗口底部、盖在内容之上的——不留这段空间，
         * 地球键就会压在键盘底行上（实测：底行整体被顶低 53dp）。
         *
         * 56dp 是个实测值：地球键是 48dp 的系统按钮，中心离屏幕底 26dp，
         * 触摸区上沿到屏幕底约 50dp——留白必须大于它，否则底栏最左边的键
         * （中/英、abc 那些）根本点不到，会被地球键吃掉点击。
         */
        const val NAV_BAR_SLACK_DP = 56f

        /**
         * 页码的固定宽度（dp）。固定宽度，避免页码出现/消失时横滑区宽度突变。
         */
        const val PAGE_INDICATOR_DP = 42f

        /**
         * 取多少字的上文交给重排模型。
         *
         * 与模型的 `context_chars`（64）一致——再多模型也读不完。
         */
        const val RERANK_CONTEXT_CHARS = 64

        /**
         * 取多少字的上文发给云端做下一词预测。
         *
         * 比本地重排宽一些：下一词预测要的是句子级的语境。
         */
        const val LEXEME_CONTEXT_CHARS = 200

        /** 云端请求的通道：下一词建议。 */
        const val CHANNEL_NEXT = "next"

        /** 云端请求的通道：按上文消歧。 */
        const val CHANNEL_DISAMBIGUATE = "disambiguate"

        /** 云端请求的通道：整句纠错。 */
        const val CHANNEL_CORRECT = "correct"

        /**
         * 少于这么几个字不送去纠错。
         *
         * 一两个字纠不出什么名堂（大模型对单字基本只会原样返回），
         * 白花一次调用。
         */
        const val MIN_CORRECT_CHARS = 2

        /**
         * 用户停下来多久之后再问云端消歧（ms）。
         *
         * 人手打一个键大约 200~400 ms，音节之间经常有停顿。
         * 这个值太小（比如 280 ms）会变成「每敲完一个音节就问一次」——
         * 一句话问三四次，模型调用白烧，还互相作废。
         * 500 ms 大致是「用户停下来看候选了」，这时候问才有意义。
         */
        const val CLOUD_DEBOUNCE_MS = 500L

        /**
         * 发给云端的候选池上限。
         *
         * 候选池可能有几十个，全发过去既慢又费 token；
         * 而且云端只需要知道「有哪些可能」，前面那些就够了。
         */
        const val CLOUD_POOL_LIMIT = 40

        /** 手指抬起后多久吸附到整页（ms）。 */
        const val SNAP_DELAY_MS = 140L

        /** 撤销栈深度。 */
        const val UNDO_DEPTH = 20

        /** 当前使用的双拼方案。 */
        const val SCHEME_ID = "xiaohe"
    }
}
