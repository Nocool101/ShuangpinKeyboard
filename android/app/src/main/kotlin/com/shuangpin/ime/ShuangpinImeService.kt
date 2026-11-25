package com.shuangpin.ime

import android.inputmethodservice.InputMethodService
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.shuangpin.engine.ActionKey
import com.shuangpin.engine.ActionKind
import com.shuangpin.engine.Candidate
import com.shuangpin.engine.CandidateKind
import com.shuangpin.engine.Candidates
import com.shuangpin.engine.Corrections
import com.shuangpin.engine.CursorMove
import com.shuangpin.engine.DataLoader
import com.shuangpin.engine.FileUserDict
import com.shuangpin.engine.InputSession
import com.shuangpin.engine.InputStage
import com.shuangpin.engine.InputSnapshot
import com.shuangpin.engine.Learning
import com.shuangpin.engine.LexemeRequest
import com.shuangpin.engine.NextWords
import com.shuangpin.engine.PanelPick
import com.shuangpin.engine.PinyinEngine
import com.shuangpin.engine.SwipeRemoval
import com.shuangpin.ime.voice.AsrConfig
import com.shuangpin.ime.voice.VoiceController
import java.io.File

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

    /** 候选栏那一行（横滑区 + 页码）。单手模式靠它加内边距把整行挪到一侧。 */
    private var candidateRow: LinearLayout? = null

    /**
     * 候选栏右端的 **⌄** 箭头。
     *
     * - **没有输入**时点它 = **收起键盘**（跟系统输入法一样）
     * - **有输入**时点它 = 展开**选字面板**（见 [CandidatePanel]）
     */
    private var candidateArrow: TextView? = null

    /** 选字面板（⌄ 展开的那一屏）。默认不显示。 */
    private var panel: CandidatePanel? = null

    /** 选字面板是不是开着。 */
    private var panelOpen: Boolean = false

    /** 选字面板：这一轮拼音的音节（左边那一列）。 */
    private var panelSegments: List<String> = emptyList()

    /** 选字面板：每个音节选中的字（null = 还没选）。 */
    private var panelPicked: MutableList<String?> = mutableListOf()

    /** 选字面板：当前在第几个音节。 */
    private var panelSegmentIndex: Int = 0

    /**
     * 上一次渲染的候选内容签名。
     *
     * 签名相同就跳过重建 chip——接了联想之后候选数量翻几倍，
     * 每次按键都 `removeAllViews` 重建一遍会明显掉帧。
     */
    private var lastCandidateSig: String? = null

    /**
     * 本会话每次上屏的**那一块文字**（按上屏先后）。
     *
     * 用来做「上滑退一格」——退的时候按**词/句**退，而不是一个字一个字退。
     *
     * 用户反馈「打一段话不能修改之前的文字，只能修改最后一个字」：
     * 一次删一个字，想改中间那个错字就得把后面的一起删光重打。
     * 按词退的话，退两次就到那儿了。
     *
     * 存**文字**而不是长度：上滑退格要拿它跟宿主里光标前面的真实文字比对，
     * 才知道这一块还在不在。记账跟宿主一旦脱节（宿主自己清过内容、
     * 云端纠错改写过长度……），按长度删就会「删了个寂寞」。
     */
    private val committedUnits = ArrayDeque<String>()

    /**
     * 上一块已上屏内容的**拼音**（null = 没有可用的上文）。
     *
     * 上下文选词全靠它：
     * - **跨界组合**：「结」（jie）+「guo」→ 词库里的「结果」→ 该给「果」
     * - **下一词预测**：刚上屏「结」→ 「果」「婚」这些接下来最可能接的字
     *
     * 记的是**拼音**而不是文字：只有拼音能在词库里查。
     *
     * 原始字符（英文/数字/标点/空格）会把上下文打断——那不是词，
     * 拿它去查词库只会查出一堆不相干的东西。
     */
    private var lastCommitPinyin: String? = null

    /** 上一块已上屏内容的文字（跟 [lastCommitPinyin] 配对）。 */
    private var lastCommitText: String? = null

    /**
     * **本地下一词**（词库给的，不用等云端）。
     *
     * 刚上屏「结」，它立刻就能给出「果」「婚」——
     * 全部来自词库，1 ms 内出结果。
     * 云端那份到了会补在后面（它更聪明，但要走一次大模型）。
     */
    private var localNextWords: List<String> = emptyList()

    /**
     * **剪贴板里刚复制的那段文字**（null = 没有 / 已经用过了）。
     *
     * 用户复制的东西直接摆在候选栏最前面：
     * 点一下上屏，或者按空格上屏（用户明确要求的）。
     */
    private var clipboardText: String? = null

    /** 剪贴板这段是什么时候读到的（跟 [lastCommitStamp] 比新旧）。 */
    private var clipboardStamp: Long = 0L

    /** 剪贴板这段是不是已经被本输入法上屏过了（用过就不再重复给）。 */
    private var clipboardUsed: Boolean = false

    /**
     * **收起剪贴板候选**。
     *
     * 剪贴板那块是**一次性**的：上屏一次、退格一次，它就不再占着候选栏最前面。
     *
     * 为什么不能一直挂着：它一直在最前面，空格键就一直是「上屏剪贴板」而不是打空格；
     * 用户想正常打字的时候老是被它拦一下（用户反馈「上屏/按退格一次之后
     * 就不要再一直显示了」）。
     *
     * 收起之后，要等**剪贴板内容变了**（用户又复制了新的）才重新出现。
     */
    private fun dismissClipboard() {
        clipboardUsed = true
    }

    /** 最近一次上屏的时刻（剪贴板比它新才给）。 */
    private var lastCommitStamp: Long = 0L

    /**
     * 剪贴板变化监听：用户在别的应用里复制了东西，切回来就能看到。
     *
     * **任何一次「复制」都是一次新的提供**——哪怕复制的内容跟上次一模一样
     * （用户就是想再用一次），也重新摆出来。
     */
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        mainHandler.post {
            val text = readClipboard()
            if (text == null) {
                clipboardText = null
                renderCandidates()
                return@post
            }
            clipboardText = text
            clipboardUsed = false
            clipboardStamp = SystemClock.uptimeMillis()
            renderCandidates()
        }
    }

    /**
     * 上一次渲染时的拼音串。
     *
     * 用来判断「是不是换了一轮输入」——换了就要把候选栏翻回第一页。
     */
    private var lastCandidatePinyin: String? = null

    /**
     * **纠错候选要不要过端侧重排**：默认**关**。
     *
     * 实测（引擎层 `TyposRerankEvalTest`，真实词库 + 真实模型）：
     * - **主通道**（同一拼音的多个读法）：引擎首选 76.3% → 模型 81.0%，**+4.7pp**
     * - **纠错通道**（分属不同拼音的纠错候选）：词频第一 71.3% → 模型 46.8%，**-24.5pp**
     *   带上文也救不回来（47/55 → 42/55）
     *
     * 原因：重排模型是**字级 LM**，它算的是「这些字连起来的概率」。
     * 它擅长的是「同一个拼音的多个读法里选哪个」（主通道）；
     * 而纠错候选**分属不同的拼音**，它那个「字面概率」就成了一个
     * 比词频更差的相关性估计——「很经典」的字比「防静电」的字常见，
     * 空上文时它就按这个排，于是把对的弄坏了。
     *
     * 所以：**代码留着**（多通道控制器 + 位置保持的应用逻辑都测过），
     * 但**默认不启用**。哪天有了「用户真实错打 + 真实意图」的样本，
     * 拿那个评测集重测一遍再决定开不开。
     */
    private val correctionRerankEnabled = false

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

    /**
     * **长句纠错**的结果（后台算出来的，纠正后的整句）。
     *
     * 这个计算不便宜（要试几十种近音/错键变体），
     * **不能挡着打字**——所以放在后台线程跑，停了 250 ms 才启动。
     */
    private var asyncCorrections: List<String> = emptyList()

    /** 后台纠错线程。 */
    private val correctionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(
        java.util.concurrent.ThreadFactory { r ->
            Thread(r, "shuangpin-correction").apply { isDaemon = true }
        },
    )

    /** 输入停下来之后再去算长句纠错。 */
    private val correctionDebounce = Runnable { requestAsyncCorrection() }

    /**
     * **长句纠错**：后台找出一整句里的错别字。
     *
     * 「今天仙人抚我顶」把 ren 打成 ri，整串在词库里查无此词，
     * 老规则（只纠整串）给不出任何东西——长文档的错别字就是这么来的。
     *
     * 这里按**分词结果**找错的那一段（见引擎层 [Corrections]），
     * 算完把纠正后的整句放进候选栏最前面。
     *
     * **在后台跑**：这个计算要试几十种变体，挡着打字会顿。
     */
    private fun requestAsyncCorrection() {
        val syllables = session.pending.map { it.syllable ?: "" }
        if (syllables.size < 2) return
        val pinyin = session.pinyin
        correctionExecutor.execute {
            val fixed = try {
                Corrections.forSentence(engine, syllables)
            } catch (ignored: Throwable) {
                emptyList()
            }
            if (fixed.isEmpty()) return@execute
            mainHandler.post {
                // 期间用户又改了输入 → 这个结果过时了，丢掉
                if (session.pinyin != pinyin) return@post
                asyncCorrections = fixed
                renderCandidates()
            }
        }
    }

    /** 输入停下来一点点再算长句纠错（连着打字的时候不算）。 */
    private fun scheduleAsyncCorrection() {
        mainHandler.removeCallbacks(correctionDebounce)
        asyncCorrections = emptyList()
        if (session.pending.size < 2) return
        mainHandler.postDelayed(correctionDebounce, CORRECTION_DEBOUNCE_MS)
    }

    /** 模型给出的等长候选顺序（新的在前）。 */
    private var rerankedOrder: List<String> = emptyList()

    /** 这个顺序对应的是哪一轮输入的拼音串（对不上就作废）。 */
    private var rerankedPinyin: String? = null

    /** 上一次请求重排的键（拼音串 + 候选集合）。同一批候选不重复请求。 */
    private var lastRerankKey: String? = null

    /**
     * 模型给出的**纠错候选**顺序（新的在前）。
     *
     * 和 [rerankedOrder] 是**两条独立的通道**：
     * 主通道排的是「同一串拼音的候选」，纠错通道排的是「纠错出来的那些词」。
     * 两者互不覆盖——纠错通道换代号不会把主通道算好的结果作废，反过来也一样。
     */
    private var rerankedCorrectionOrder: List<String> = emptyList()

    /** 这个纠错顺序对应的是哪一轮输入的拼音串（对不上就作废）。 */
    private var rerankedCorrectionPinyin: String? = null

    /** 上一次请求纠错重排的键。同一批纠错候选不重复请求。 */
    private var lastCorrectionRerankKey: String? = null

    /**
     * 候选栏的第一个候选——**空格键会上屏的就是它**。
     *
     * 模型重排之后，第一个候选可能已经不是引擎的最优整句了，
     * 空格必须跟着变，否则用户看到的第一候选和空格上屏的会不一致。
     */
    private var topCandidateText: String? = null

    /**
     * 候选栏当前的可选项（跟 chip 一一对应，顺序一致）。
     *
     * 滑动选候选时靠它把「选中的第几个」翻译成「哪条候选」。
     */
    private var candidateItems: List<Candidate> = emptyList()

    /**
     * 选中的候选下标；**-1 = 没选**。
     *
     * 没选的时候空格就是空格（用户明确要求过空格能打出来）；
     * 选了之后，空格直接上屏选中的那条。
     */
    private var selectedIndex: Int = -1

    /**
     * 上一次**自己**挪拼音光标的时刻（`SystemClock.uptimeMillis()`）。
     *
     * 挪完要把宿主的光标摆过去（`setSelection`），宿主会把这个动作回报回来。
     * 有的宿主回报的位置不准（WebView 型输入框一律报末尾），
     * 照着它改会话里的光标就白挪了。
     * 这段时间内宿主的回报一律不信。
     */
    private var lastCaretSetAt: Long = 0L

    /** chip 的视图引用，画选中态用。 */
    private val chipHolders = mutableListOf<ChipHolder>()

    private var editorAction: Int = EditorInfo.IME_ACTION_NONE
    private var isPasswordField: Boolean = false

    /**
     * 当前宿主的**动作键语义**（发送 / 换行 / 前往 / 搜索 / 完成）。
     *
     * 同一颗键在不同 App 里该干的事完全不一样，所以每次换输入框都要重新判定。
     * 判定规则见引擎层的 [ActionKey]。
     */
    private var actionKind: ActionKind = ActionKind.DONE

    /**
     * **用户自学习词库**。
     *
     * 每台设备自己的：用户打过的词（词库里没有的也行，比如「炼气士」）
     * 记在这儿，下次打同样的拼音它就排第一。
     */
    private var userDict: FileUserDict? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dictionaryThread: Thread? = null

    /**
     * 上一次写进宿主输入框的**预编辑区内容**。
     *
     * 用来判断「预编辑区的内容变没变」——
     * 没变就**别再调 `setComposingText`**：
     * 一调宿主的光标就被顶到末尾，用户在拼音中间点出来的光标位置就没了
     * （「点拼音改错字」这个功能全靠这个光标位置）。
     */
    private var lastComposingText: String? = null

    /**
     * 当前是否在宿主应用里留着预编辑区。
     */
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

        // 剪贴板：用户在别的应用里复制了东西，切回来候选栏里就有。
        // （只监听变化，不主动去读——读的动作留给 onStartInput 和变化回调。）
        try {
            getSystemService(android.content.ClipboardManager::class.java)
                ?.addPrimaryClipChangedListener(clipListener)
        } catch (ignored: Throwable) {
            // 有些 ROM 不让读剪贴板（隐私限制），读不到就不给这个功能
        }

        // 小数据（键位表+音节表）只有几十 KB，同步加载，键盘立刻可用
        val core = DataLoader().loadCore()
        val scheme = core.schemes[SCHEME_ID] ?: error("缺少方案：$SCHEME_ID")
        engine = PinyinEngine(scheme, core.table)
        session = InputSession(engine)

        // 大数据（词库，上百万条）必须后台加载，否则输入法启动会卡住
        dictionaryThread = Thread {
            val dictionary = DataLoader().loadDictionary()
            engine.dictionary = dictionary
            // 用户自学习词库跟着一块儿加载：文件很小，
            // 但没必要为它挡一下输入法启动。
            val dict = FileUserDict(File(filesDir, ImeSettings.USER_DICT_FILE))
            userDict = dict
            engine.userDict = dict
            mainHandler.post { refresh() }
        }.apply {
            name = "shuangpin-dict"
            isDaemon = true
            start()
        }
    }

    override fun onDestroy() {
        dictionaryThread?.interrupt()
        try {
            getSystemService(android.content.ClipboardManager::class.java)
                ?.removePrimaryClipChangedListener(clipListener)
        } catch (ignored: Throwable) {
            // 同上
        }
        // 用户词库落盘：服务被杀之前把刚学的词写下去
        userDict?.flush()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 键盘形态（单手 / 全键盘）
    // ------------------------------------------------------------------

    /** 当前键盘形态。从设置读，改了设置下次弹键盘就生效。 */
    private fun keyLayout(): ShuangpinKeyboardView.Layout = when (settings?.keyLayout) {
        ImeSettings.KEY_LAYOUT_FULL -> ShuangpinKeyboardView.Layout.FULL
        ImeSettings.KEY_LAYOUT_LEFT -> ShuangpinKeyboardView.Layout.LEFT
        else -> ShuangpinKeyboardView.Layout.RIGHT
    }

    /** 韵母位排法。从设置读。 */
    private fun finalArrange(): ShuangpinKeyboardView.FinalArrange =
        if (settings?.finalArrange == ImeSettings.FINAL_ARRANGE_THUMB) {
            ShuangpinKeyboardView.FinalArrange.THUMB
        } else {
            ShuangpinKeyboardView.FinalArrange.FIXED
        }

    /**
     * 把键盘形态应用到键盘和候选栏。
     *
     * **候选栏必须跟着键盘收窄到同一侧**——
     * 键盘在右边、候选栏还横跨全屏的话，最顺手的那几个候选反而够不着，
     * 等于单手模式白做。
     */
    private fun applyKeyLayout() {
        val l = keyLayout()
        keyboard?.layout = l
        keyboard?.finalArrange = finalArrange()
        val row = candidateRow ?: return
        if (l == ShuangpinKeyboardView.Layout.FULL) {
            row.setPadding(0, 0, 0, 0)
            return
        }
        // 留白区那一侧加内边距，候选栏就缩到跟键盘同一侧了
        val dead = (resources.displayMetrics.widthPixels *
            (1f - CANDIDATE_WIDTH_RATIO)).toInt()
        if (l == ShuangpinKeyboardView.Layout.RIGHT) {
            row.setPadding(dead, 0, 0, 0)
        } else {
            row.setPadding(0, 0, dead, 0)
        }
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

        // 「光标在哪」的小标签：挪拼音光标的时候显示（光标在末尾就不显示）。
        // 不少宿主不认 IME 挪预编辑区的光标（实测 Via 的 WebView 搜索框），
        // 光标位置得由输入法自己画出来，用户才知道那一滑挪到哪儿了。
        val caretView = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF171B23.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
            // 淡蓝底：一眼看出「这是输入法自己画的光标位置」，
            // 跟候选（白底）区分开，免得跟宿主输入框里那个光标搞混。
            setBackgroundColor(0xFFE8F0FE.toInt())
            visibility = View.GONE
        }
        caretIndicator = caretView

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
        // 「光标在哪」的小标签夹在横滑区和页码之间（靠右 = 靠拇指）
        row.addView(
            caretView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        row.addView(
            indicator,
            LinearLayout.LayoutParams(dp(PAGE_INDICATOR_DP).toInt(), LinearLayout.LayoutParams.MATCH_PARENT),
        )

        // 候选栏右端的 **⌄** 箭头（参考手机自带输入法）：
        // 没有输入时收起键盘，有输入时展开选字面板。
        val arrow = TextView(this).apply {
            text = "⌄"
            textSize = 20f
            setTextColor(0xFF5B6472.toInt())
            gravity = android.view.Gravity.CENTER
            setOnClickListener { onCandidateArrow() }
            contentDescription = "收起键盘 / 选字"
        }
        candidateArrow = arrow
        row.addView(
            arrow,
            LinearLayout.LayoutParams(dp(ARROW_DP).toInt(), LinearLayout.LayoutParams.MATCH_PARENT),
        )

        // 候选栏里**不再放「设置」按钮**了。
        //
        // 以前放这儿是因为「系统设置 → 语言和输入法 → 键盘 → 设置」那条路太深，
        // 十个用户九个找不到。现在 App 有桌面图标了，设置就在图标里：
        // 点一下图标进设置页，要比在候选栏里挤一个 40dp 的按钮干净得多——
        // 候选栏右侧的页码也能离候选更近一点。

        // 单手模式：候选栏跟着键盘收窄到同一侧——
        // 键盘在右边、候选栏还横跨全屏的话，最顺手的那几个候选反而够不着。
        candidateRow = row
        applyKeyLayout()

        root.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(CANDIDATE_BAR_DP).toInt(),
            ),
        )

        val kb = ShuangpinKeyboardView(this)
        kb.bind(session, engine)
        // 键盘形态：单手右手 / 单手左手 / 全键盘。
        // 跟候选栏一起收窄到同一侧（applyKeyLayout 里一并做）。
        kb.layout = keyLayout()
        // 空格键的长按归谁：语音占着的时候不能拿去挪拼音光标。
        kb.spaceLongPressForVoice = canTalkBySpace()
        applyKeyLayout()
        kb.listener = object : ShuangpinKeyboardView.Listener {
            override fun onPinyinKey(key: String) {
                session.pressKey(key)
                // 新一轮输入：逐字修改的结果作废（候选全变了，改了也没意义）
                charEdits.clear()
                editingSyllable = null
                editBase = null
                // 候选要重排了，之前选中的那条也不作数
                clearCandidateSelection()
                // 新一轮输入开始了：上一轮的下一词建议跟这轮没关系，
                // 留着只会挤占候选栏，还会让人以为那些词是这一轮的候选。
                resetLexeme()
                // 长句纠错：停下来一会儿再算（后台线程，不挡打字）
                scheduleAsyncCorrection()
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

            override fun onAction() {
                onActionKeyPressed()
            }

            override fun onBackspace() {
                onBackspacePressed()
            }

            override fun onSwipeUpDelete() {
                swipeUpDelete()
            }

            override fun onSwipeUpDeleteAll() {
                swipeUpDeleteAll()
            }

            override fun onUndo() {
                undo()
            }

            override fun onLetterSwipe(steps: Int) {
                // 正在输入 → 横滑是「选候选」；
                // 没输入 → 这一滑是「挪宿主文本的光标」。
                if (session.pending.isNotEmpty() || composing.isNotEmpty()) {
                    if (steps != 0) moveCandidateSelection(steps)
                    return
                }
                moveHostCursor(steps)
            }

            override fun onPinyinCaretMove(steps: Int) {
                // 长按之后的滑动 → 挪**拼音里的光标**
                // （手指不用离开键盘就能改拼音）。
                movePinyinCaret(steps)
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

        // 选字面板：跟键盘**同一个位置、同一个高度**（一个显示一个隐藏）——
        // 窗口高度不变，展开/收起时键盘不会上下漂。
        val p = CandidatePanel(this)
        p.visibility = View.GONE
        p.listener = object : CandidatePanel.Listener {
            override fun onPickWhole(item: Candidate) {
                closePanel()
                commitChip(item)
            }

            override fun onPickChar(segmentIndex: Int, ch: String) {
                pickPanelChar(segmentIndex, ch)
            }

            override fun onSelectSegment(segmentIndex: Int) {
                panelSegmentIndex = segmentIndex.coerceIn(0, (panelSegments.size - 1).coerceAtLeast(0))
                refreshPanel()
            }

            override fun onBack() {
                panelBack()
            }

            override fun onCollapse() {
                closePanel()
            }
        }
        panel = p
        root.addView(
            p,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
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
        // 换了一个输入框：预编辑区是空的，lastComposingText 也要复位，
        // 否则新输入框里第一次 refresh 会以为「内容没变」而不写预编辑区。
        lastComposingText = null
        undoStack.clear()
        committedText.clear()
        committedUnits.clear()
        // 上下文（跨界组合 / 下一词）也复位：换输入框就是换上下文。
        // 剪贴板不算——它跟输入框无关（用户复制的东西切过来照样能用）。
        lastCommitPinyin = null
        lastCommitText = null
        localNextWords = emptyList()
        // 换了一个输入框：上一处的联想一个字都不该带过来
        resetLexeme()

        val inputType = info?.inputType ?: 0
        isPasswordField = (inputType and InputType.TYPE_MASK_VARIATION) ==
            InputType.TYPE_TEXT_VARIATION_PASSWORD

        // 读剪贴板（密码框里不给，见下）
        //
        // ⚠ **这里不再把它当成「一次新的粘贴机会」**：
        // 换个输入框就把剪贴板候选放回来，用户用过一次之后切到别的 App
        // 再切回来它还在（用户反馈「用过一次切换到别的App里候选区的内容还在」）。
        //
        // 现在它是**一次性**的：用过（上屏/退格）就收起，
        // 要等**剪贴板内容变了**（用户又复制了新的）才重新出现。
        readClipboardInto()
        // 密码框里**不给剪贴板候选**：密码框的内容不该被剪贴板里的东西污染，
        // 也不该把剪贴板暴露给录屏（FLAG_SECURE 之外再加一道）。
        if (isPasswordField) clipboardText = null

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
        updateActionKind(info)
        refresh()
    }

    /**
     * 判定当前宿主里动作键该是什么。
     *
     * 判据有三样：**宿主包名 + 编辑框声明的 action + 是不是多行**。
     *
     * 为什么不能只看 action：微信的聊天框**不声明 action**，
     * 而浏览器地址栏多半也不声明——只看 action 的话，
     * 微信会判成「换行」，用户永远发不出消息。
     *
     * 判定规则在引擎层 [ActionKey]（那边有单测）。
     */
    private fun updateActionKind(info: EditorInfo?) {
        val pkg = info?.packageName ?: ""
        val inputType = info?.inputType ?: 0
        actionKind = ActionKey.resolve(pkg, editorAction, inputType)
        keyboard?.actionLabel = ActionKey.label(actionKind)
        Log.i(
            TAG,
            "host=$pkg action=$editorAction inputType=$inputType -> $actionKind (${ActionKey.label(actionKind)})",
        )
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyWindowHeight()
        applySoftInputMode()
        // 设置里改了键盘形态的话，这次弹键盘就生效
        // （输入法服务是常驻的，不会因为改设置而重建）。
        applyKeyLayout()
        // 每次弹键盘都回到**中文输入**模式——
        // 用户反馈「弹出键盘要在中文输入法界面，不要在上次选择的地方」。
        // （上次停在数字/标点面板的话，再弹出来还是数字面板，
        //   看着像「键盘坏了」，其实是模式没复位。）
        //
        // ⚠ **但「重启输入」不算**：宿主重启输入（restartInput）时
        // **键盘形态要保住**。
        //
        // 电话号码输入框就是这样：很多拨号盘在收到第一个数字后会
        // 调 `restartInput()`（要重新按号码格式整理一遍），
        // 于是 `onStartInputView` 又来一次——老代码在这里把模式重置成中文，
        // 用户的数字键盘**打第一个数字就被弹回文字键盘**，
        // 必须重新点一次「123」才能接着输入（用户反馈）。
        //
        // 所以：**只在不是重启的时候复位模式**。
        if (!restarting && currentMode != ShuangpinKeyboardView.Mode.CHINESE) {
            currentMode = ShuangpinKeyboardView.Mode.CHINESE
            keyboard?.mode = ShuangpinKeyboardView.Mode.CHINESE
            // rawMode 是从 currentMode 派生的，不用单独改
        }
        // 动作键的文案跟着宿主走（发送 / 换行 / 前往 / 完成）
        keyboard?.actionLabel = ActionKey.label(actionKind)
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
        lastComposingText = null
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

        // 空格键的长按归谁：语音占着的时候不能拿去挪拼音光标（反之可以）。
        // 状态一变就更新（正在打拼音 → 语音用不上 → 空格键让给光标）。
        keyboard?.spaceLongPressForVoice = canTalkBySpace()

        // 预编辑区显示已输入内容，让用户看到自己打的是什么。
        // 没有预编辑内容时不要反复调 setComposingText("")——那会动宿主的光标，
        // 有些应用会因此出现光标跳动。
        val text = composing
        // **内容没变就别再 setComposingText**：一调宿主的光标就被顶到末尾，
        // 用户在拼音中间点出来的光标位置会丢（点拼音改错字全靠它）。
        if (text != lastComposingText) {
            if (text.isNotEmpty()) {
                // ⚠ 光标参数**只能传 1**。
                // 这个参数的本意是「光标停在预编辑区的第几个字符」，
                // 但实测不少宿主（Via 的搜索框就是）不认它——
                // 传别的值会把预编辑区**写坏**（文字被复制成两段）。
                // 光标的位置另外用 setSelection 摆（见 restoreCaret）。
                ic.setComposingText(text, 1)
                hasComposing = true
            } else if (hasComposing) {
                ic.setComposingText("", 1)
                hasComposing = false
            }
            lastComposingText = text
            // 少数 App 不认 setComposingText 的光标参数，兜底挪一下
            restoreCaret(ic, text)
        }
        renderCandidates()
        // 选字面板开着时，候选变了要跟着刷（「全部」页显示的就是这批候选）
        if (panelOpen) refreshPanel()
        // 「光标在哪」的小标签跟着预编辑区走
        updateCaretIndicator()
        // syncSession 不只是重画：韵母位在「拇指优先」模式下要**重排键位**，
        // 所以阶段变了必须重新 layout。
        keyboard?.syncSession()
    }

    /**
     * 把宿主输入框的光标放回会话里的位置。
     *
     * `setComposingText` 之后，宿主的光标多半停在预编辑区末尾——
     * 用户点了拼音中间之后，删个字再改，光标就跑到末尾去了，
     * 看着像「改的地方不对」。这里把它挪回来。
     *
     * 挪之前先确认「宿主的光标确实停在预编辑区末尾」：
     * 光标前面那段文字（`getTextBeforeCursor`）应当是
     * 「已上屏文字 + 整个预编辑区」，对得上才认——
     * 对不上说明这个宿主的口径不一样，别拿猜的位置去 `setSelection`
     * （那会把光标甩到别处，用户反馈「光标乱跳」）。
     */
    private fun restoreCaret(ic: android.view.inputmethod.InputConnection, text: String) {
        val caret = session.caret
        if (text.isEmpty()) return
        // 在末尾不用管（宿主放的就是那儿）
        if (caret >= text.length) return
        val before = ic.getTextBeforeCursor(CURSOR_QUERY_CHARS, 0)?.toString() ?: return
        val after = ic.getTextAfterCursor(CURSOR_QUERY_CHARS, 0)?.toString() ?: ""
        val start = when {
            before.endsWith(text) -> {
                // 光标前面那段 = 已上屏文字 + 整个预编辑区 → 起点就是它扣掉预编辑区
                (before.length - text.length).coerceAtLeast(0)
            }
            else -> {
                // ⚠ **光标落在预编辑区中间**时，「光标前面那段」只是预编辑区的前半截，
                // `before.endsWith(text)` 会对不上（宿主的光标把预编辑区切开了）。
                //
                // 这时用「全文长度 − 预编辑区长度」算起点：
                // before + after = 宿主文本全文，预编辑区在它**末尾**，
                // 所以起点 = 全文字数 − 预编辑区字数。
                val whole = before.length + after.length
                if (whole >= text.length) {
                    whole - text.length
                } else {
                    // 这个宿主不认 setComposingText 的光标参数（光标没落在预编辑区末尾），
                    // 问它要预编辑区真正的起点
                    hostComposingStart(text) ?: return
                }
            }
        }
        val pos = (start + caret).coerceAtLeast(0)
        ic.setSelection(pos, pos)
    }

    /**
     * 问宿主要预编辑区在它文本里的真实起点。
     *
     * `getExtractedText` 的 `partialStartOffset/partialEndOffset`
     * 就是预编辑区（composing span）的起止——宿主给得出就用它的，
     * 给不出（返回 null）就返回 null，调用方另有退路。
     */
    private fun hostComposingStart(text: String): Int? {
        val ic = currentInputConnection ?: return null
        val extracted = try {
            ic.getExtractedText(null, 0)
        } catch (ignored: Throwable) {
            null
        } ?: return null
        val start = extracted.partialStartOffset
        val end = extracted.partialEndOffset
        if (start < 0 || end < 0) return null
        // 长度必须对得上：对不上说明这个「partial」不是预编辑区
        // （比如宿主把选区也报成 partial），那就别用了
        if (end - start != text.length) return null
        return start
    }

    /**
     * 宿主的光标被用户动了（点了预编辑区里的拼音）→ 换算成预编辑区里的偏移。
     *
     * 这是「拼音打错了，点一下就能改」的入口：
     * 用户点一下拼音，光标就停在那一处；接着按退格，删的是**光标前面那个字**，
     * 再接着打字，插在光标处。
     *
     * @param action 宿主这次改选区的原因（系统传的，用不上，保留占位）
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        textLength: Int,
        action: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, textLength, action)
        val text = session.composingText
        if (text.isEmpty()) return
        // ⚠ **刚自己挪过光标**：宿主会把我们设的选区回报回来，
        // 有的宿主回报的位置**不准**（比如 WebView 型输入框总是报末尾）——
        // 照着它改，光标就被拽回末尾，表现为「只能挪一个字，然后就滑不动了」。
        // 这段时间内宿主的回报一律不信，只重画。
        if (SystemClock.uptimeMillis() - lastCaretSetAt < CARET_SET_GUARD_MS) {
            renderCandidates()
            keyboard?.syncSession()
            return
        }
        // 预编辑区在宿主文本里的起点。
        // ⚠ 宿主报过来的那个长度，**口径不统一**（见 composingStart），
        // 算错了光标就落在别的地方 → 退格删掉别的字（用户反馈「前面有文字时，
        // 光标删除拼音会乱跳」）。
        val start = composingStart(textLength, text, newSelStart) ?: return
        val offset = newSelStart - start
        if (offset == session.caret) return
        session.setCaret(offset)
        // 只重画，不动预编辑区的内容（内容没变，setComposingText 不用调）
        renderCandidates()
        keyboard?.syncSession()
    }

    /**
     * 预编辑区在宿主文本里的**起点**。
     *
     * 宿主在 `onUpdateSelection` 里报的那个长度，口径**不统一**（实测）：
     *
     * | 口径 | 那个长度是什么 | 预编辑区起点 |
     * |---|---|---|
     * | A | 预编辑区**之前**那段文字的长度（实测多数宿主这样） | 就是它 |
     * | B | 整段文本的长度（AOSP 文档的说法） | 它 - 预编辑区长度 |
     *
     * 两种都算成候选：**谁的范围装得下这次的光标位置，就是谁**；
     * 两个都装得下（只有光标正好在分界上才会这样），
     * 就用「光标前面的真实文字」再验一次——
     * 光标前面那段必然是「已上屏文字 + 预编辑区开头的一段」，对得上就认。
     */
    private fun composingStart(textLength: Int, text: String, selStart: Int): Int? {
        val a = textLength                      // 口径 A
        val b = textLength - text.length        // 口径 B
        val okA = selStart - a in 0..text.length
        val okB = selStart - b in 0..text.length
        if (okA && !okB) return a
        if (okB && !okA) return b
        if (!okA && !okB) return null
        // 两个都装得下 → 问宿主要「光标前面的文字」来分辨
        val before = try {
            currentInputConnection?.getTextBeforeCursor(CURSOR_QUERY_CHARS, 0)?.toString()
        } catch (ignored: Throwable) {
            null
        } ?: return a
        // 光标前面那段 = 已上屏文字 + 预编辑区开头的一段
        val headA = text.substring(0, (selStart - a).coerceIn(0, text.length))
        if (!before.endsWith(headA)) return b
        val headB = text.substring(0, (selStart - b).coerceIn(0, text.length))
        return if (before.endsWith(headB)) b else a
    }

    private fun renderCandidates() {
        val bar = candidateBar ?: return
        val scroll = candidateScroll ?: return

        // 选字面板开着时候选栏整条让位：
        // 面板的「全部」页展示的就是这批候选，在面板上方再摆一份，
        // 用户很容易点错——点错会直接上屏，面板却还开着，键盘就回不来了。
        if (panelOpen) {
            scroll.visibility = View.INVISIBLE
            candidateIndicator?.visibility = View.INVISIBLE
            return
        }

        // 非中文模式没有候选，候选栏整体隐藏但**保留占位**（INVISIBLE 而非 GONE），
        // 否则布局塌陷、键盘上移，键位就漂了。
        if (rawMode) {
            scroll.visibility = View.INVISIBLE
            candidateIndicator?.visibility = View.INVISIBLE
            lastCandidateSig = null
            lastCandidatePinyin = null
            topCandidateText = null
            resetCandidateSelection()
            scroll.scrollTo(0, 0)
            return
        }

        // 语音进行中：候选栏只显示状态，别让用户以为那些候选还能点
        if (voiceState != VoiceController.State.IDLE) {
            showVoiceStatus()
            return
        }

        // 「改这个字」模式：候选栏换成那个音节的候选字
        if (editingSyllable != null) {
            renderSyllableCandidates()
            return
        }

        val hasCandidates = session.pending.isNotEmpty()
        // 上屏之后没有预编辑内容了，但**下一词建议还得显示**——
        // 那正是「用户刚打完一个词，接下来可能想打什么」的用处所在。
        val hasNext = nextWords.isNotEmpty() || localNextWords.isNotEmpty()
        val hasCorrection = correctionFixed != null
        // 剪贴板：刚复制的那段文字摆在最前面
        val clip = clipboardCandidate()
        scroll.visibility = if (hasCandidates || hasNext || hasCorrection || clip != null) {
            View.VISIBLE
        } else {
            View.INVISIBLE
        }
        if (!hasCandidates && !hasNext && !hasCorrection && clip == null) {
            candidateIndicator?.visibility = View.INVISIBLE
            lastCandidateSig = null
            lastCandidatePinyin = null
            topCandidateText = null
            resetCandidateSelection()
            scroll.scrollTo(0, 0)
            return
        }

        // 候选内容没变就不重建 chip（见 lastCandidateSig 的注释）。
        // 候选的装配规则在引擎层（Candidates.build）——那里定义的才是
        // 「这个输入法给出什么候选」，端侧模型评估用的是同一份逻辑。
        // 自动纠错候选只在这里打开（评估端默认关，基线才可比）。
        //
        // **带上上文**（上一块已上屏的拼音 + 文字）：
        // 跨界组合靠它选词——「结」+「guo」该给「果」，不是「过」。
        val items = Candidates.build(
            engine = engine,
            session = session,
            includeCorrections = true,
            contextPinyin = lastCommitPinyin,
            contextText = lastCommitText,
        )
        val ordered = applyRerank(items)
        val boosted = applyCloudBoost(ordered)
        // 纠错候选之间的重排放在最后：它只动纠错候选彼此的次序，
        // 但要**盖过**前面的所有排序（云端提升也会重排 kept 那组）。
        val withCorrRerank = applyCorrectionRerank(boosted)
        val withNext = insertNextWords(withCorrRerank)
        val withCorrection = insertCorrection(withNext)
        // 后台算出来的长句纠错：纠正后的整句摆最前面
        val withAsyncCorrection = insertAsyncCorrections(withCorrection)
        // 逐字修改的结果最后套上——重排、联想、纠错都不该把它冲掉
        val finalItems = applyCharEdit(withAsyncCorrection)
        // 画不出来的字不进候选栏（扩展区的字手机字体多半没有字形 → 一片空白）。
        // 放在最后一步：重排/消歧看到的候选池不动，那份池子要和评估端一致。
        var visibleItems = finalItems.filter { isRenderableText(it.text) }
        // 剪贴板摆最前面：用户刚复制的东西，点一下（或按空格）就能上屏
        if (clip != null) visibleItems = listOf(clip) + visibleItems
        val sig = session.pinyin + "|" + visibleItems.joinToString("|") { it.text }
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

        // 候选变了：**选中的那条还在就跟着挪到新位置**，不在了才作废。
        // 云端结果晚到时会把整排候选换掉——用户滑好的选择不能被这么冲掉
        // （表现就是「滑了半天，一来云端结果又回到第一条」）。
        val selectedText = candidateItems.getOrNull(selectedIndex)?.text

        bar.removeAllViews()
        chipHolders.clear()
        candidateItems = visibleItems
        visibleItems.forEachIndexed { i, item -> addChip(item, isFirst = i == 0) }
        selectedIndex = if (selectedText == null) {
            -1
        } else {
            visibleItems.indexOfFirst { it.text == selectedText }
        }
        applySelectionStyle()
        // 第一候选 = 确认键会上屏的那个
        topCandidateText = visibleItems.firstOrNull()?.text
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
        //
        // **用户自学习词也不参与**：那是用户明确打出来过的东西，
        // 模型没见过它，让模型排就等于把它排到后面去——那还学什么。
        //
        // **整串精确命中也不参与**：模型只见过常用词，
        // "xianrenfuwoding" 这种生僻整词它会给打散成「现任服我定」——
        // 用户明明打对了，候选栏第一却是个胡拼的句子。
        //
        // **上下文选出来的也不参与**：那是词库给的硬证据
        // （"jie"+"guo" → 词库里的「结果」），模型没见过它。
        val protected = items.filter {
            isCorrectionKind(it.kind) || it.kind == CandidateKind.USER || it.exact || it.context
        }
        val rest = items.filter {
            !isCorrectionKind(it.kind) && it.kind != CandidateKind.USER && !it.exact && !it.context
        }
        val order = rerankedOrder
        if (order.isEmpty() || rerankedPinyin != session.pinyin) return protected + rest
        val rank = HashMap<String, Int>()
        order.forEachIndexed { i, text -> rank[text] = i }
        val equal = equalLengthCandidates(rest)
        val others = rest.filter { it.text.length != session.pending.size }
        // sortedBy 是稳定排序，没在模型结果里的排最后
        return protected + equal.sortedBy { rank[it.text] ?: Int.MAX_VALUE } + others
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
        val boosted = Candidates.promote(cloudBoost, items)
        // 云端把别的候选提上来时，三类「硬信号」仍然留在最前面——
        // 「我打过的词」「这是纠错出来的」「这一串本身就是个词」。
        // 云端的推测再准也不该压过它们。
        //
        // **次序固定为：用户词 → 纠错候选 → 精确命中。**
        // 纠错候选必须在精确命中之前：打错的那串拼音在词库里常常也有命中
        // （打错成另一个冷门词），那个命中恰恰是「错」的证据——
        // 压住纠错候选就等于白纠了（空格上屏的是第一条）。
        //
        // 放在 `kept` 里按固定次序排，而不是跟着 `boosted` 走：
        // 云端的 promote 会重排它点名的候选，不能让它把硬信号的次序打乱。
        val kept = boosted
            .filter {
                it.kind == CandidateKind.USER || it.kind == CandidateKind.CORRECTION ||
                    it.exact || it.context
            }
            .sortedBy { hardSignalRank(it) }
        val rest = boosted.filter {
            it.kind != CandidateKind.USER && it.kind != CandidateKind.CORRECTION &&
                !it.exact && !it.context
        }
        return kept + rest
    }

    /**
     * 硬信号在候选栏里的固定次序：用户词 → 纠错候选 → 上下文命中 → 精确命中。
     *
     * 数字越小越靠前。其它候选都是 4（跟着云端/重排的结果走）。
     */
    private fun hardSignalRank(item: Candidate): Int = when {
        item.kind == CandidateKind.USER -> 0
        item.kind == CandidateKind.CORRECTION -> 1
        item.context -> 2
        item.exact -> 3
        else -> 4
    }

    /**
     * 把模型重排的**纠错候选**顺序套上去。
     *
     * 只动**纠错候选之间**的次序——它们在候选栏里的位置（第几格）不变。
     * 置顶的纠错候选还是置顶，只是几个纠错候选之间换了顺序。
     *
     * 为什么值得排：纠错候选的初始顺序是**词频**
     * （「只会」79 分 >「智慧」76 分 >「指挥」73 分）。
     * 词频只能分高低，分不出「用户此刻想要哪个」——那要看上文。
     * 「班」后面接的是「指挥」，不是「只会」。
     */
    private fun applyCorrectionRerank(items: List<Candidate>): List<Candidate> {
        val order = rerankedCorrectionOrder
        if (order.isEmpty() || rerankedCorrectionPinyin != session.pinyin) return items
        val corr = items.filter { it.kind == CandidateKind.CORRECTION }
        if (corr.size < 2) return items
        val rank = HashMap<String, Int>()
        order.forEachIndexed { i, text -> rank[text] = i }
        // sortedBy 是稳定排序：模型没提到的纠错候选留在后面，彼此顺序不变
        val sorted = corr.sortedBy { rank[it.text] ?: Int.MAX_VALUE }
        // 原地替换：位置不变，只换内容
        val out = ArrayList<Candidate>(items.size)
        var i = 0
        for (item in items) {
            if (item.kind == CandidateKind.CORRECTION) out += sorted[i++] else out += item
        }
        return out
    }

    /**
     * 请求**纠错候选**的重排。
     *
     * 纠错候选之间是**等长**的（字数 == 音节数），正好满足重排模型「候选等长」的要求。
     * 模型看的是「哪个词更接上文」——这正是词频给不了的信息。
     *
     * **独立通道**：不跟主通道混在一起。
     * 纠错候选的拼音跟用户实际敲的键对不上，混进主池子等于让模型
     * 在两批语义不同的候选之间比，那是「苹果和梨」。
     */
    private fun requestCorrectionRerank(items: List<Candidate>) {
        if (!correctionRerankEnabled) return
        if (!reranker.ready) return
        val corr = items.filter { it.kind == CandidateKind.CORRECTION }
        if (corr.size < 2) return
        val ctx = rerankContext()
        // **没上文就别问模型**：它这时候只能按字面概率瞎排，比词频还差（实测 -24.5pp）
        if (ctx.isBlank()) return
        val key = session.pinyin + "|C|" + corr.joinToString("|") { it.text }
        if (key == lastCorrectionRerankKey) return
        lastCorrectionRerankKey = key

        val pinyin = session.pinyin
        val t = reranker.newToken(RerankController.CORRECTION_CHANNEL)
        reranker.request(RerankController.CORRECTION_CHANNEL, ctx, corr.map { it.text }, t) { ranked ->
            mainHandler.post {
                rerankedCorrectionOrder = ranked
                rerankedCorrectionPinyin = pinyin
                renderCandidates()
            }
        }
    }

    /**
     * 请求模型重排。
     *
     * 同一批候选不重复请求——否则「重排 → 刷新 → 又触发重排」会绕圈。
     */
    private fun requestRerank(items: List<Candidate>) {
        // 纠错候选走**自己的通道**（跟主通道互不干扰）。
        // 放在前面：主通道可能因为「候选不足两个」直接返回，
        // 那时纠错通道照样该跑——它们要的东西不一样。
        requestCorrectionRerank(items)

        // 纠错候选不进重排池（原因见 applyRerank）
        // 用户自学习词、整串精确命中也不进：模型没见过它们，排名没有意义
        val pool = items.filter { !isCorrectionKind(it.kind) && it.kind != CandidateKind.USER && !it.exact }
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

    /**
     * 正在逐字修改的音节序号（null = 不在改）。
     *
     * 用户点候选词里某个字上标的拼音，就进入「改这个字」模式：
     * 候选栏换成那个音节的候选字，选一个就把那个字换掉，其它字不动。
     */
    private var editingSyllable: Int? = null

    /**
     * 正在被改的那个候选（原文）+ 改的是第几个字。
     */
    private var editBase: String? = null
    private var editCharIndex = 0

    /**
     * 逐字修改的结果：原候选文本 → 改过的文本。
     *
     * 改的是**候选词**的字，不是引擎整句的字——
     * 「智慧」和「只会」是同一串拼音的两种切分，
     * 用户改的是他看到的那个候选。
     *
     * 做成映射而不是一次性替换，是因为**端侧重排是异步的**：
     * 改完紧接着还会再渲染一次，一次性替换会被冲掉。
     */
    private val charEdits = LinkedHashMap<String, String>()

    /**
     * 把逐字修改的结果套到候选列表上。
     *
     * 被改的那条候选**原地替换**——位置不变（用户是从第 3 个点进去的，
     * 改完它还是第 3 个）。
     */
    private fun applyCharEdit(items: List<Candidate>): List<Candidate> {
        if (charEdits.isEmpty()) return items
        return items.map { item ->
            val neu = charEdits[item.text]
            if (neu != null) item.copy(text = neu) else item
        }
    }

    /** 候选 chip 的视图引用 + 原始配色（画选中态、还原时都要用）。 */
    private class ChipHolder(
        val item: Candidate,
        /** 背景挂在这个 view 上（逐字版是那个横排的盒子）。 */
        val container: View,
        /** 候选字的 view（逐字版是一个字一个）。 */
        val texts: List<TextView>,
        /** 每个字上面的拼音角标（逐字版才有）。 */
        val pinyins: List<TextView>,
        val baseBg: Int,
        val baseTextColor: Int,
    )

    private fun addChip(item: Candidate, isFirst: Boolean) {
        // 剪贴板：单独画。它不是候选词，是个「刚复制的东西」——
        // 摆最前面，标成剪贴板的样子（用户一眼知道这不是候选）。
        if (item.kind == CandidateKind.CLIPBOARD) {
            val shown = stripUnrenderable(item.text)
            val label = if (shown.length > CLIPBOARD_DISPLAY_MAX) {
                shown.take(CLIPBOARD_DISPLAY_MAX) + "…"
            } else {
                shown
            }
            val tv = TextView(this).apply {
                text = "📋 $label"
                textSize = 16f
                setTextColor(COLOR_CLIPBOARD_TEXT)
                setPadding(dp(12f).toInt(), dp(4f).toInt(), dp(12f).toInt(), dp(4f).toInt())
                setBackgroundColor(COLOR_CLIPBOARD_BG)
                setOnClickListener { commitChip(item) }
            }
            candidateBar?.addView(tv)
            chipHolders += ChipHolder(item, tv, listOf(tv), emptyList(), COLOR_CLIPBOARD_BG, COLOR_CLIPBOARD_TEXT)
            return
        }

        val syllables = session.pending.map { it.syllable ?: "" }
        // 字数跟音节数一致，才能逐字对上拼音。
        // 长词扩展（「你好吗」对应 2 个音节）之类对不上，就走老样式。
        // 单字候选例外：它只属于**一个**音节，那个音节的拼音就标在它上面
        // （用户要能看出这个字是哪来的、是哪一音节的）。
        val isCharItem = item.kind == CandidateKind.CHAR && item.pinyin != null
        val perChar: List<String>? = when {
            syllables.isNotEmpty() && syllables.size == item.text.length -> syllables
            isCharItem -> listOf(item.pinyin!!)
            else -> null
        }

        val color = chipTextColor(item, isFirst)
        val bg = chipBackground(item, isFirst)
        val size = chipTextSize(item)

        if (perChar == null) {
            // 对不上拼音 → 老样子，一个 TextView
            val tv = TextView(this).apply {
                text = item.text
                textSize = size
                setTextColor(color)
                setPadding(dp(12f).toInt(), dp(4f).toInt(), dp(12f).toInt(), dp(4f).toInt())
                if (bg != 0) setBackgroundColor(bg)
                setOnClickListener { commitChip(item) }
            }
            candidateBar?.addView(tv)
            chipHolders += ChipHolder(item, tv, listOf(tv), emptyList(), bg, color)
            return
        }

        // 逐字版：每个字一格，字**上面**标这个字的拼音。
        // 点拼音 → 改这个字；点字 → 整条上屏。
        //
        // 拼音做成角标叠在上方（跟键面左上角的字母角标同一套视觉语言），
        // 候选栏高度不用变——一变键盘就上下漂，用户的肌肉记忆就废了。
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f).toInt(), 0, dp(6f).toInt(), 0)
            if (bg != 0) setBackgroundColor(bg)
            setOnClickListener { commitChip(item) }
        }
        val charViews = mutableListOf<TextView>()
        val pinyinViews = mutableListOf<TextView>()
        for ((i, syl) in perChar.withIndex()) {
            // 单元高度要**明确给**：角标和汉字都是 WRAP_CONTENT，
            // 高度一样的话 FrameLayout 里一个 TOP 一个 BOTTOM 会完全重叠——
            // 汉字后添加，压在角标上面，点角标就变成点上屏。
            val unit = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(42f).toInt(),
                )
            }
            val pinyinView = TextView(this).apply {
                text = syl
                textSize = 10f
                setTextColor(COLOR_PINYIN_BADGE)
                // 点拼音 = 只改这一个字。角标小，触摸区要留够，
                // 不然用户点不准（真机上点一次就上屏了）。
                setPadding(dp(3f).toInt(), dp(1f).toInt(), dp(3f).toInt(), dp(1f).toInt())
                setOnClickListener {
                    if (isCharItem) {
                        // 单字候选：打开**它那个音节**的选字面板，
                        // 在那儿把这个音节的字一个个挑出来。
                        val seg = session.pending.indexOfFirst { it.syllable == syl }
                        openPanelAt(seg.coerceAtLeast(0))
                    } else {
                        startEditingSyllable(i, item.text)
                    }
                }
            }
            pinyinViews += pinyinView
            unit.addView(
                pinyinView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL },
            )
            val charView = TextView(this).apply {
                text = item.text[i].toString()
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                setOnClickListener {
                    if (isCharItem) {
                        // 用这个字上屏整句（它属于哪个音节就替哪个音节）
                        val seg = session.pending.indexOfFirst { it.syllable == syl }
                        commitCharAt(item.text, seg.coerceAtLeast(0))
                    } else {
                        commitChip(item)
                    }
                }
            }
            unit.addView(
                charView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL },
            )
            box.addView(unit)
            charViews += charView
        }
        candidateBar?.addView(box)
        chipHolders += ChipHolder(item, box, charViews, pinyinViews, bg, color)
    }

    /** 候选文字的颜色。 */
    private fun chipTextColor(item: Candidate, isFirst: Boolean): Int = when {
        // 剪贴板：青色——它不是候选，是个「刚复制的东西」
        item.kind == CandidateKind.CLIPBOARD -> COLOR_CLIPBOARD_TEXT
        // 第一候选就是「确认键会上屏的那个」，用主候选的样式
        isFirst -> 0xFFA8620A.toInt()
        // 用户自学习词：橙色——「这是你自己打过的」
        item.kind == CandidateKind.USER -> 0xFFB4541A.toInt()
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

    /** 候选字的底色；0 = 不要底色。 */
    private fun chipBackground(item: Candidate, isFirst: Boolean): Int = when {
        isFirst -> 0xFFFFF8E8.toInt()
        item.kind == CandidateKind.USER -> 0xFFFDF0E3.toInt()
        item.kind == CandidateKind.CORRECTION -> 0xFFE4F4EA.toInt()
        item.kind == CandidateKind.CORRECTION_TEXT -> 0xFFF3E8F8.toInt()
        item.promoted -> 0xFFEDF3FE.toInt()
        else -> 0
    }

    /** 候选字的字号。 */
    private fun chipTextSize(item: Candidate): Float = when (item.kind) {
        CandidateKind.SENTENCE -> 20f
        // 纠错候选字号稍大一点：它是最需要被看见的那个
        CandidateKind.CORRECTION -> 19f
        // 用户自学习词：也放大一点——它排在最前面，得一眼看见
        CandidateKind.USER -> 19f
        CandidateKind.SENTENCE_ALT -> 18f
        CandidateKind.WORD, CandidateKind.CHAR -> 18f
        // 扩展候选、下一词建议：字号小一档，视觉上和主候选分开
        CandidateKind.EXTENSION, CandidateKind.NEXT_WORD -> 17f
        // 云端整句纠错：一整句话，字号小一档才放得下
        CandidateKind.CORRECTION_TEXT -> 16f
        // 剪贴板：内容可能很长，小一号才放得下
        CandidateKind.CLIPBOARD -> 16f
    }

    /** 点候选：按候选的类别决定怎么上屏。 */
    private fun commitChip(item: Candidate) {
        // 选字面板开着的时候点候选栏：先把面板收起来。
        // 不收的话——字上屏了、面板还占着键盘的位置，
        // 用户就回不到文字输入键盘（用户反馈「选好文字后不能回到键盘」）。
        closePanel()
        // 选中的那条已经上屏了，选择作废
        clearCandidateSelection()
        when (item.kind) {
            // 单字是「前面的音节 + 这个字」
            CandidateKind.CHAR -> commitChar(item.text)
            // 云端整句纠错：把已上屏的那段换掉
            CandidateKind.CORRECTION_TEXT -> applyCorrection()
            // 剪贴板：整段上屏
            CandidateKind.CLIPBOARD -> commitClipboard()
            // 词：可能是整串，也可能只是分词切出来的其中一段。
            // 后者要**保住后面没选的那半截拼音**（见 commitWordPrefix）。
            CandidateKind.WORD -> {
                if (!commitWordPrefix(item.text)) commitText(item.text)
            }
            // 其余是整条上屏
            else -> commitText(item.text)
        }
    }

    /**
     * 候选栏里点了一个**词**时：上屏这个词，**后面没选的那半截拼音留在会话里**。
     *
     * 「cangtianzhijian」→ 候选里有「苍天之间」（整句）、还有「苍天」「之间」。
     * 用户点**前面的**「苍天」：老逻辑整轮输入就结束了，后面那半截「zhijian」
     * 凭空消失（用户反馈「在候选区选择前面的文字，后面的文字就会消失」）。
     *
     * 正确做法：上屏「苍天」，**「zhijian」留在预编辑区里**，
     * 候选栏接着给「之间」——用户按空格就上屏，不用重新打字。
     *
     * 点**后面的**词（「之间」）时，前面没选的那部分不能丢：
     * 把它一起上屏（用户要的就是「苍天之间」）。
     *
     * @return true = 走这条路上屏了；false = 这个词覆盖了整串，按老路上屏
     */
    private fun commitWordPrefix(word: String): Boolean {
        val syllables = session.pending.map { it.syllable ?: "" }
        if (syllables.isEmpty()) return false
        // 用**带音节跨度**的分词结果：词库里有「西安」这种一个音节对应两个字的词条，
        // 按序号数会数错位（用户反馈「选前面的词，后面的字就没了」的根因之一）。
        val spans = engine.segmentWordSpans(syllables)
        if (spans.isEmpty()) return false
        var hit = -1
        for ((i, span) in spans.withIndex()) {
            if (span.word == word) {
                hit = i
                break
            }
        }
        if (hit < 0) return false
        val span = spans[hit]
        // 这一块覆盖到第几个音节（从 0 数）
        val consumed = span.start + span.count
        if (consumed <= 0) return false
        // 上屏的文字 = 它前面的那些词 + 它自己
        val text = spans.take(hit + 1).joinToString("") { it.word }
        if (text.isEmpty()) return false
        commitText(text, consumed = consumed)
        return true
    }

    // ------------------------------------------------------------------
    // 候选选择：在键盘输入区滑动选候选，空格上屏
    // ------------------------------------------------------------------

    /**
     * 键盘输入区横向滑动 → 移动候选选择。
     *
     * 方向跟选中的候选在候选栏里的移动方向一致：
     * **手指往右 = 往后翻（往右边的候选走）**，往左 = 往前翻。
     *
     * 滑多远 = 挪几个候选：一步（44 dp）挪一个。
     */
    private fun moveCandidateSelection(steps: Int) {
        if (steps == 0) return
        if (candidateItems.isEmpty()) return
        val last = candidateItems.size - 1
        var idx = selectedIndex
        if (idx < 0) {
            // 还没选中任何候选：第一次滑动从**第一个**候选开始。
            // idx = -1 → 滑一步正好落在第 0 个，不会「一滑就跳过第一个」
            // （那也是「不跟手」的一种：手指才动一点，选中就跳到第二个去了）。
            idx = -1
        }
        applyCandidateSelection((idx + steps).coerceIn(0, last))
    }

    /** 选中第 index 条候选：画出来，并且让候选栏滚到它那儿。 */
    private fun applyCandidateSelection(index: Int) {
        if (index < 0 || index >= candidateItems.size) return
        selectedIndex = index
        applySelectionStyle()
        scrollToSelected()
    }

    /**
     * 清掉选择。
     *
     * 候选变了 / 上屏了 / 退格了，选择都得作废——
     * 留着的话，空格会上一条用户已经看不见的候选。
     */
    private fun clearCandidateSelection() {
        if (selectedIndex < 0) return
        selectedIndex = -1
        applySelectionStyle()
    }

    /** 候选列表整体重置（换屏、换模式的时候用）。 */
    private fun resetCandidateSelection() {
        selectedIndex = -1
        candidateItems = emptyList()
        chipHolders.clear()
    }

    /**
     * 把选中态画到 chip 上（没选中的还原成原来的配色）。
     *
     * **选中 = 品牌蓝实底 + 白字**（用户反馈「滑动选词选中词后颜色太淡，
     * 用户不容易识别」）。
     * 以前是一块很淡的浅蓝底 + 深蓝字，混在一排浅色字里几乎看不出选中了谁；
     * 现在整条候选栏里最重的一块就是它，眼睛一扫就知道「空格会上的就是这条」。
     */
    private fun applySelectionStyle() {
        chipHolders.forEachIndexed { i, h ->
            val on = i == selectedIndex
            if (on) {
                h.container.setBackgroundResource(R.drawable.bg_candidate_selected)
            } else {
                // 还原成原来的底色（0 = 不要底色）
                h.container.setBackgroundColor(h.baseBg)
            }
            h.texts.forEach { it.setTextColor(if (on) COLOR_SELECTED_TEXT else h.baseTextColor) }
            // 拼音角标也跟着换色——灰字压在蓝底上看不清
            h.pinyins.forEach { it.setTextColor(if (on) COLOR_SELECTED_PINYIN else COLOR_PINYIN_BADGE) }
        }
    }

    /**
     * 让候选栏滚到选中的那条。
     *
     * 选中的候选在屏幕外的话，用户根本看不见它——那就等于没选。
     */
    private fun scrollToSelected() {
        val scroll = candidateScroll ?: return
        val holder = chipHolders.getOrNull(selectedIndex) ?: return
        val view = holder.container
        val pageW = scroll.width
        if (pageW <= 0) return
        val left = view.left
        val right = left + view.width
        val target = when {
            left < scroll.scrollX -> left
            right > scroll.scrollX + pageW -> right - pageW
            else -> scroll.scrollX
        }
        if (target != scroll.scrollX) scroll.scrollTo(target, 0)
    }

    /**
     * 上屏选中的候选。
     *
     * @return true = 上屏了（这一下不是空格）；false = 没选中的候选
     */
    private fun commitSelectedCandidate(): Boolean {
        val idx = selectedIndex
        if (idx < 0) return false
        val item = candidateItems.getOrNull(idx) ?: return false
        commitChip(item)
        return true
    }

    /**
     * **长按之后滑动** → 挪**拼音里的光标**。
     *
     * 直接滑动是「选候选」；手指先按住不动（长按），这一滑就变成挪光标——
     * 手指不用离开键盘，就能把光标挪到打错的那个音节上，
     * 接着按正确的键，新键就**插在光标处**（见 [InputSession.setCaret]）。
     *
     * 没在输入拼音的时候，退回「挪宿主文本的光标」——
     * 跟直接滑动一个样。
     *
     * @param steps 位移步数（正 = 往右）：拼音的**字**数
     */
    private fun movePinyinCaret(steps: Int) {
        if (steps == 0) return
        val text = session.composingText
        if (text.isEmpty()) {
            moveHostCursor(steps)
            return
        }
        val from = session.caret
        val target = CursorMove.horizontal(text, from, steps)
        if (target == from) return
        if (!session.setCaret(target)) return
        Log.d("ShuangpinIME", "拼音光标 $from → $target")
        // 预编辑区的**内容没变**，所以不用 setComposingText
        //（调了会把宿主的光标顶到末尾，用户点的位置就丢了；
        //  带光标位置重发更糟：WebView 型宿主会把预编辑区复制成两段，
        //  实测 Via 里「nihao」会变成「nihaoao」）。
        // 宿主的光标尽力摆一下；摆不动也不要紧——
        // **输入法自己会把光标位置画出来**（见 [caretIndicator]）。
        lastCaretSetAt = SystemClock.uptimeMillis()
        currentInputConnection?.let { restoreCaret(it, text) }
        updateCaretIndicator()
        renderCandidates()
        keyboard?.syncSession()
    }

    /**
     * **输入法自己显示的「光标在哪」**。
     *
     * 为什么要有这个：会话里的插入点挪了 ≠ 宿主输入框里那个**可见的光标**也挪了。
     * 不少宿主（实测 Via 的搜索框是 WebView）**不认 IME 挪预编辑区光标**——
     * 宿主把选区一律报成末尾，`setSelection` 也不生效。
     * 结果就是：功能上确实挪了（接着按的键会插在那一处），
     * 但用户看着光标还在末尾，**会以为这一滑没生效**
     * （用户反馈「长按滑动移动拼音光标还是移不动」）。
     *
     * 所以候选栏右边固定显示一小段「ni|hao」，光标位置一目了然。
     */
    private var caretIndicator: TextView? = null

    /** 更新「光标在哪」那个小标签。 */
    private fun updateCaretIndicator() {
        val view = caretIndicator ?: return
        val text = session.composingText
        if (text.isEmpty() || session.caret >= text.length) {
            // 光标在末尾 = 不用提示（宿主自己就显示在那儿）
            view.visibility = View.GONE
            view.text = ""
            return
        }
        val caret = session.caret.coerceIn(0, text.length)
        Log.d("ShuangpinIME", "指示器 text='$text' caret=$caret")
        // 光标画成一根竖线插在中间（就像文本框里的插入符），
        // 光标位置那个字也跟着标蓝，一眼能看出「要改的是哪个字」。
        val shown = text.substring(0, caret) + "|" + text.substring(caret)
        view.text = android.text.SpannableString(shown).apply {
            setSpan(
                android.text.style.ForegroundColorSpan(COLOR_ACCENT_TEXT),
                caret, caret + 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0, shown.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        view.visibility = View.VISIBLE
    }

    /**
     * 键盘输入区**没有输入时**的滑动 → 挪宿主文本里的光标。
     *
     * 「手往哪个方向滑，光标就往哪个方向走」：
     * 一个字一个字挪（代理对算一个字，emoji 不会被劈开）。
     *
     * 光标的计算在引擎层（[CursorMove]）：它是纯文本计算，可以单测。
     */
    private fun moveHostCursor(steps: Int) {        val ic = currentInputConnection ?: return
        if (steps == 0) return
        val extracted = try {
            ic.getExtractedText(null, 0)
        } catch (ignored: Throwable) {
            null
        }
        val text = extracted?.text?.toString()
        val pos = extracted?.selectionStart ?: -1
        if (text != null && pos >= 0) {
            val target = CursorMove.horizontal(text, pos, steps)
            Log.d("ShuangpinIME", "光标 $pos → $target（挪 $steps 个字）")
            if (target != pos) ic.setSelection(target, target)
            return
        }
        // 退路：宿主不给全文，就用光标前后的文字拼出来
        moveHostCursorFallback(ic, steps)
    }

    /**
     * 没有全文时的挪光标。
     *
     * 光标前面那段 + 后面那段 = 全文（光标正好在中间），
     * 光标的绝对位置 = 前面那段的长度。
     *
     * ⚠ 两段都可能被窗口截断——**前面那段被截断时绝对位置就不可信了**，
     * 这时候宁可不挪（跳到一个错的位置比不动更糟）。
     */
    private fun moveHostCursorFallback(
        ic: android.view.inputmethod.InputConnection,
        steps: Int,
    ) {
        if (steps == 0) return
        val before = ic.getTextBeforeCursor(CURSOR_QUERY_CHARS, 0)?.toString() ?: return
        val after = ic.getTextAfterCursor(CURSOR_QUERY_CHARS, 0)?.toString() ?: return
        if (before.length >= CURSOR_QUERY_CHARS) return   // 被截断了，位置不可信
        val text = before + after
        val pos = before.length
        val target = CursorMove.horizontal(text, pos, steps)
        if (target != pos) ic.setSelection(target, target)
    }

    // ------------------------------------------------------------------
    // 选字面板（候选栏右端的 ⌄ 展开的那一屏）
    // ------------------------------------------------------------------

    /**
     * 候选栏右端的 ⌄：
     *
     * - **没有输入** → **收起键盘**（跟系统输入法一样）
     * - **有输入** → 展开 / 收起**选字面板**
     */
    private fun onCandidateArrow() {
        if (session.pending.isEmpty() && composing.isEmpty()) {
            requestHideSelf(0)
            return
        }
        if (panelOpen) closePanel() else openPanel()
    }

    /** 展开选字面板（键盘暂时让位，两者同高，窗口高度不变）。 */
    private fun openPanel() {
        openPanelAt(0)
    }

    /** 展开选字面板，并且定位到第 [index] 个音节。 */
    private fun openPanelAt(index: Int) {
        val p = panel ?: return
        val segs = session.pending.map { it.syllable ?: "" }
        if (segs.isEmpty()) return
        panelSegments = segs
        panelPicked = MutableList(segs.size) { null }
        panelSegmentIndex = index.coerceIn(0, segs.size - 1)
        panelOpen = true
        p.visibility = View.VISIBLE
        keyboard?.visibility = View.GONE
        // 候选栏整条让位：面板的「全部」页展示的就是这批候选，
        // 在面板上方再摆一份，用户很容易点错——
        // 点错会直接上屏，面板却还开着，键盘就回不来了。
        candidateScroll?.visibility = View.INVISIBLE
        candidateIndicator?.visibility = View.INVISIBLE
        refreshPanel()
    }

    /**
     * 用第 [index] 个音节的这个字上屏整句（其它音节用默认首字）。
     *
     * 候选栏里现在**每个音节的字都有**，
     * 点哪个音节的字，就替哪个音节——不能一律替最后一个。
     */
    private fun commitCharAt(ch: String, index: Int) {
        val pending = session.pending
        if (index !in pending.indices) {
            commitChar(ch)
            return
        }
        // 改的是第 index 个音节，别的音节用默认首字（跟选字面板同一套规则）
        val picked = pending.indices.map { if (it == index) ch else null }
        val defaults = pending.map { hit ->
            engine.candidateCharList(hit.syllable ?: "").firstOrNull().orEmpty()
        }
        commitText(PanelPick.assemble(picked, defaults))
    }

    /** 收起选字面板，回到键盘。 */
    private fun closePanel() {
        if (!panelOpen) return
        panelOpen = false
        panel?.visibility = View.GONE
        keyboard?.visibility = View.VISIBLE
        panelPicked.clear()
        // 候选栏重新露出来（面板期间它一直让位）
        renderCandidates()
    }

    /** 面板数据刷新（当前音节变了、候选变了都要刷）。 */
    private fun refreshPanel() {
        val p = panel ?: return
        if (!panelOpen) return
        // 这一轮输入已经没了（被上屏了、或被宿主清掉了）：
        // 面板里没东西可显示，收起来。
        // 不收的话用户会困在一个空面板里——键盘回不来，
        // 面板上只有一句「这个音节没有候选字」。
        if (session.pending.isEmpty()) {
            closePanel()
            return
        }
        p.segments = panelSegments
        p.picked = panelPicked
        p.segmentIndex = panelSegmentIndex
        p.wholeItems = candidateItems
        p.charItems = panelSegmentChars(panelSegmentIndex)
        p.render()
    }

    /** 某个音节的候选字。 */
    private fun panelSegmentChars(index: Int): List<String> {
        val hit = session.pending.getOrNull(index) ?: return emptyList()
        val syl = hit.syllable ?: return emptyList()
        // 用 candidateCharList（一个汉字一个元素），不是把连写字符串按字符切：
        // 扩展区的字（𫘪 这种）在 UTF-16 里是两个码元，切开就是两个乱码。
        return engine.candidateCharList(syl)
            .filter { isRenderable(it) }
            .take(PANEL_CHAR_LIMIT)
    }

    /**
     * 画笔：只用来问字体「这个字你画得出来吗」。
     *
     * 不用 typeface，默认的就行——面板里的 TextView 用的也是默认字体。
     */
    private val glyphPaint = android.graphics.Paint()

    /** 字能不能显示出来的判定结果（同一个字只问一次）。 */
    private val glyphCache = HashMap<String, Boolean>()

    /**
     * 这个字当前字体画得出来吗？
     *
     * **扩展区的汉字（U+20000 以上）画不出来**：手机字体基本没有这些字形，
     * 画出来是空白（不是豆腐块，是什么都没有）。
     *
     * 后果很直观：字表里 30% 的条目是扩展区的字（扩展表扩进来之前），
     * 几乎每个音节的候选列表**尾部都是几行看不见的空行**——
     * 用户滑到底只看到一片空白，还以为列表坏了
     * （用户反馈「滑到最后一行还能继续下滑，导致空白」）。
     *
     * 看不见的字选了也白搭（打出来还是看不见），直接从候选里滤掉。
     * 这是按**当前设备的字体**判的：字体支持的设备上它们照常显示。
     */
    private fun isRenderable(ch: String): Boolean {
        if (ch.isEmpty()) return false
        glyphCache[ch]?.let { return it }
        val ok = try {
            glyphPaint.hasGlyph(ch)
        } catch (ignored: Throwable) {
            true    // 问不出来就当它能显示——宁可多显示一个字
        }
        if (glyphCache.size < 8192) glyphCache[ch] = ok
        return ok
    }

    /** 这段文字里有没有画不出来的字（按码点走，代理对算一个字）。 */
    private fun isRenderableText(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isRenderable(String(Character.toChars(cp)))) return false
            i += Character.charCount(cp)
        }
        return true
    }

    /**
     * 「单字」页选了一个字：记住它，然后**前进到下一个音节**。
     *
     * 最后一个音节也选完了 → 拼起来上屏。
     */
    private fun pickPanelChar(segmentIndex: Int, ch: String) {
        if (segmentIndex !in panelPicked.indices) return
        panelPicked[segmentIndex] = ch
        if (segmentIndex < panelSegments.size - 1) {
            panelSegmentIndex = segmentIndex + 1
            refreshPanel()
            return
        }
        // **没挑过的音节用默认首字补上**。
        //
        // 用户常常只想改最后一个字：点 ⌄ 打开面板 → 点最后一个音节 → 挑一个字。
        // 前面那些音节他没挑过，但它们**同样要上屏**——
        // 以前拼的是空串，结果上屏的只有最后那一个字，前面的内容全没了
        // （用户反馈「只修改最后一个字时，前面的字不会上屏」）。
        val text = PanelPick.assemble(panelPicked, panelDefaultChars())
        // 学习单位要在 closePanel 之前算——它会把 panelPicked 清空
        val units = panelLearningUnits()
        closePanel()
        if (text.isNotEmpty()) commitText(text, units = units)
    }

    /**
     * 每个音节的**默认首字**（用户没挑的那个音节就拿它顶上）。
     *
     * 跟候选栏里「点某个音节的字，别的音节用默认首字」是同一套规则。
     */
    private fun panelDefaultChars(): List<String> =
        session.pending.map { hit ->
            engine.candidateCharList(hit.syllable ?: "").firstOrNull().orEmpty()
        }

    /**
     * 面板开着的时候，退格键 = **退回上一个音节**。
     *
     * 在「选字界面」里按退格，期望是「上一个音节还没选好，回去改」，
     * 不是「删宿主里的一个字」。
     */
    private fun panelBack(): Boolean {
        if (!panelOpen) return false
        if (panelSegmentIndex > 0) {
            panelSegmentIndex--
            panelPicked[panelSegmentIndex] = null
        }
        refreshPanel()
        return true
    }

    /**
     * 进入「改这个字」模式。
     *
     * 候选栏换成那个音节的候选字，选一个就把那个字换掉。
     *
     * @param charIndex 改的是候选里的第几个字
     * @param baseText  被改的那个候选的原文
     */
    private fun startEditingSyllable(charIndex: Int, baseText: String) {
        if (charIndex !in session.pending.indices) return
        editingSyllable = charIndex
        editBase = baseText
        editCharIndex = charIndex
        lastCandidateSig = null      // 强制重建 chip
        refresh()
    }

    /**
     * 「改这个字」模式的候选栏：那个音节的候选字。
     */
    private fun renderSyllableCandidates() {
        val bar = candidateBar ?: return
        val scroll = candidateScroll ?: return
        val index = editingSyllable ?: return
        if (index !in session.pending.indices) {
            editingSyllable = null
            renderCandidates()
            return
        }
        val syllable = session.pending[index].syllable ?: ""
        // 一个汉字一个元素（扩展区的字是双码元，按字符切会裂成乱码）
        val raw = engine.candidateCharList(syllable)
        if (raw.isEmpty()) {
            editingSyllable = null
            renderCandidates()
            return
        }
        // 当前被改的那个候选里，这个位置上的字（比如「智慧」的「智」）置顶——
        // 用户改的就是它，得一眼能看见。
        val curChar = editBase?.getOrNull(editCharIndex)?.toString()
        var chars = raw.take(30)
        if (curChar != null && chars.contains(curChar)) {
            // 注意顺序：先摘出去，再**放到最前面**。
            // 反过来写（filterNot + curChar）会把它追加到末尾，等于没置顶。
            chars = listOf(curChar) + chars.filterNot { it == curChar }
        }
        scroll.visibility = View.VISIBLE
        candidateIndicator?.visibility = View.INVISIBLE
        bar.removeAllViews()
        // 这一屏的 chip 是直接加的（不是走 addChip），所以候选列表要清空——
        // 不然滑动选择的下标会跟视图对不上。
        chipHolders.clear()
        candidateItems = emptyList()
        resetCandidateSelection()

        // 标题：正在改哪个字（显示汉字 + 拼音）
        bar.addView(
            TextView(this).apply {
                text = "改「${curChar ?: syllable}」（$syllable）→"
                textSize = 12f
                setTextColor(0xFF6B7280.toInt())
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
                setOnClickListener {
                    // 点标题 = 放弃修改
                    editingSyllable = null
                    lastCandidateSig = null
                    refresh()
                }
            }
        )
        for (ch in chars) {
            val s = ch.toString()
            bar.addView(
                TextView(this).apply {
                    text = s
                    textSize = 20f
                    setTextColor(0xFF171B23.toInt())
                    gravity = Gravity.CENTER
                    setPadding(dp(12f).toInt(), dp(4f).toInt(), dp(12f).toInt(), dp(4f).toInt())
                    setOnClickListener {
                        // 把被改的那条候选**原地换掉**：
                        // 原文本 → 改过的文本。映射是持久的，
                        // 端侧重排回来再渲染一次也不会被冲掉。
                        val base = editBase
                        if (base != null && editCharIndex in base.indices) {
                            charEdits[base] = base.replaceRange(editCharIndex, editCharIndex + 1, s)
                        }
                        editingSyllable = null
                        editBase = null
                        lastCandidateSig = null
                        refresh()
                    }
                }
            )
        }
        bar.post { updatePageInfo() }
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
    /**
     * 上屏一段中文。
     *
     * @param followUp 上屏之后要不要接着问云端（下一词预测 + 整句纠错）。
     *   动作键触发的上屏**不要**：消息都要发出去了，再纠错就晚了。
     * @param pinyin 这一段文字对应的**拼音**（null = 没有，比如原始字符）。
     *   它是上下文选词的原料——见 [lastCommitPinyin]。
     */
    private fun commitToHost(
        text: String,
        followUp: Boolean = true,
        pinyin: String? = null,
    ) {
        if (text.isEmpty()) return
        val ic = currentInputConnection ?: return
        // 上屏 = 这一轮输入结束了，选字面板必须收起：
        // 面板和键盘占的是同一个位置，不收起来键盘就一直被挡住。
        closePanel()
        ic.commitText(text, 1)
        // commitText 会顶掉宿主里的预编辑区；顶不掉的（有些 App 自己实现了 InputConnection）
        // 这里再补一次清理，别让「已上屏」和「预编辑」两份内容并存。
        clearComposing()
        noteCommitted(text, pinyin)
        pushUndo(text)
        // 本地下一词：刚上屏这块后面最可能接什么（词库给的，1 ms 内就有）
        localNextWords = computeLocalNextWords(text, pinyin)
        // 上下文变了，上一轮的下一词建议作废
        nextWords = emptyList()
        // 上一轮的云端消歧同样作废（它对应的是刚才那串拼音）
        cloudBoost = emptyList()
        cloudBoostPinyin = null
        refresh()
        if (!followUp) return
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
        val s = settings ?: return
        // 发给模型的那段上文。回话时要用**同一段**上文来削重复——
        // 削的基准必须和模型看到的一样，削出来的才对得上。
        val sentCtx = ctx.takeLast(LEXEME_CONTEXT_CHARS)
        lc.request(
            LexemeRequest(
                text = sentCtx,
                // ⚠ 模型名和 key **必须带上**：直连厂商时 key 不带就是 401，
                //   模型名空着厂商也不认（自建服务模式则由服务端自己补默认值）。
                model = s.llmModel,
                apiKey = s.llmApiKey,
                temperature = s.llmTemperature,
            ),
            CHANNEL_NEXT,
        ) { words ->
            if (words.isEmpty()) return@request
            // 模型常把上文一起吐回来（上下文「火焰」→ 回「火焰山」）。
            // 候选栏里的词是**接着上文往上加**的：显示「火焰山」，
            // 用户点一下得到的是「火焰火焰山」——上文重复了一遍。
            // 削掉重复的部分，剩下的才是真正要接着打的那部分。
            val cleaned = NextWords.clean(words, sentCtx)
            if (cleaned.isEmpty()) return@request
            mainHandler.post {
                // 期间用户已经开始了新一轮输入 → 这个建议过时了，丢掉
                if (session.pending.isNotEmpty()) return@post
                nextWords = cleaned
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
        get() = nextWords.isNotEmpty() || localNextWords.isNotEmpty() ||
            cloudBoost.isNotEmpty() || correctionFixed != null

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
        // 本地下一词也是「上一轮」的，一样作废
        localNextWords = emptyList()
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
     *
     * **本地那一份排在云端前面**：本地是词库给的，1 ms 就到；
     * 云端要走一次大模型（1.4 s 起），慢一拍。
     */
    private fun insertNextWords(items: List<Candidate>): List<Candidate> {
        val words = (localNextWords + nextWords).distinct()
        if (words.isEmpty()) return items
        val at = items.indexOfFirst { it.kind == CandidateKind.CHAR }
            .let { if (it < 0) items.size else it }
        val next = words.map { Candidate(it, CandidateKind.NEXT_WORD) }
        return items.toMutableList().apply { addAll(at, next) }
    }

    /**
     * **本地下一词**：刚上屏这块后面最可能接什么。
     *
     * 全部来自词库：「结」（jie）+下一个音节在词库里查得到什么，
     * 那个词的尾巴就是下一词（「果」「婚」……）。
     *
     * **不用等云端**——云端那份要走一次大模型，1.4 s 起，
     * 用户看到的是「候选栏空着等了一会儿才冒出来」。
     */
    private fun computeLocalNextWords(text: String, pinyin: String?): List<String> {
        if (pinyin.isNullOrEmpty() || text.isEmpty()) return emptyList()
        return engine.nextWords(pinyin, text, LOCAL_NEXT_LIMIT)
    }

    /**
     * **剪贴板候选**（没有就 null）。
     *
     * 摆出来的条件：
     * - 设置里没关掉这个功能
     * - 剪贴板里有文字
     * - 这段还没被本输入法上屏过（用过就不再重复给）
     * - **比上次上屏新**：用户在上屏之后又复制了新的才给
     *   （不然「打完一段话按空格」会突然把剪贴板里的东西贴上去）
     * - 当前没有正在输入的拼音（有拼音时候选栏给的是候选，不是剪贴板）
     */
    private fun clipboardCandidate(): Candidate? {
        val s = settings ?: return null
        if (!s.clipboardEnabled) return null
        val text = clipboardText ?: return null
        if (clipboardUsed) return null
        if (clipboardStamp <= lastCommitStamp) return null
        if (session.pending.isNotEmpty()) return null
        if (stripUnrenderable(text).isEmpty()) return null
        return Candidate(text, CandidateKind.CLIPBOARD)
    }

    /** 读剪贴板里的文本（读不到就 null：有些 ROM/宿主不允许）。 */
    private fun readClipboard(): String? {
        return try {
            val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return null
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount <= 0) return null
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return null
            text.trim().takeIf { it.isNotEmpty() }
        } catch (ignored: Throwable) {
            null
        }
    }

    /**
     * 把剪贴板里的文字读进来。
     *
     * 读到的内容跟之前不一样才算「新的」——一样的话不更新时间戳，
     * 否则「剪贴板比上次上屏新」这个判据就失效了。
     */
    private fun readClipboardInto() {
        val text = readClipboard()
        if (text == null) {
            clipboardText = null
            return
        }
        if (text != clipboardText) {
            clipboardText = text
            clipboardUsed = false
            clipboardStamp = SystemClock.uptimeMillis()
        }
    }

    /** 把剪贴板内容上屏。 */
    private fun commitClipboard() {
        val text = clipboardText ?: return
        if (text.isEmpty()) return
        clipboardUsed = true
        val ic = currentInputConnection ?: return
        clearComposing()
        ic.commitText(text, 1)
        noteCommitted(text)
        pushUndo(text)
        refresh()
    }

    /** 这段文字里画不出来的字去掉（剪贴板用：整体不可显示就没法摆）。 */
    private fun stripUnrenderable(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            if (isRenderable(ch)) sb.append(ch)
            i += Character.charCount(cp)
        }
        return sb.toString()
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
     * 把**后台算出来的长句纠错**插到候选栏**最前面**。
     *
     * 跟别的候选不一样：它不是「猜的」，是查词库查出来的
     * （「今天仙人抚我顶」比「今天西安日服我顶」更说得通）。
     * 所以它排在最前面，而且不参与端侧重排（模型没见过这种整句）。
     */
    private fun insertAsyncCorrections(items: List<Candidate>): List<Candidate> {
        if (asyncCorrections.isEmpty()) return items
        val out = ArrayList<Candidate>(items.size + asyncCorrections.size)
        val existing = items.mapTo(HashSet()) { it.text }
        for (text in asyncCorrections) {
            if (text.isEmpty()) continue
            if (!existing.add(text)) continue
            out += Candidate(text, CandidateKind.CORRECTION)
        }
        out += items
        return out
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
        noteCommitted(text)
        // 原始字符把中文上下文打断了：
        // 「结」+「，」+「guo」不该再按「结果」来联想。
        lastCommitPinyin = null
        lastCommitText = null
        pushUndo(text)
        refresh()
    }

    /** 清掉宿主里的预编辑区，不留残影。 */
    private fun clearComposing() {
        if (!hasComposing) return
        currentInputConnection?.setComposingText("", 1)
        hasComposing = false
    }

    /**
     * 上屏一段中文（走会话，顺带把这一轮学进用户词库）。
     *
     * @param followUp 见 [commitToHost]
     * @param units    要记进词库的「拼音 → 词」；不给就按当前会话推算。
     * @param consumed 这一段覆盖了**前面几个音节**（>0 = 只提交前半截，
     *   后面的音节留在会话里继续输入，见 [InputSession.commitPrefix]）。
     */
    private fun commitText(
        text: String,
        followUp: Boolean = true,
        units: List<Pair<String, String>>? = null,
        consumed: Int = 0,
    ) {
        // 学习必须在会话重置**之前**做：拼音串（session.pinyin）
        // 这时候还在，重置后就没了。
        val allSyllables = session.pending.map { it.syllable ?: "" }
        val pinyin = if (consumed > 0) {
            allSyllables.take(consumed).joinToString("")
        } else {
            allSyllables.joinToString("")
        }
        val learnUnits = units ?: learningUnits(text, consumed)
        learnCommit(text, learnUnits)
        // **组合学习**：上一块 + 这一块 一起记一条（见 learnCombination）
        learnCombination(pinyin, text)
        if (consumed > 0) {
            session.commitPrefix(text, consumed)
        } else {
            session.commitText(text)
        }
        commitToHost(text, followUp, pinyin)
    }

    // ------------------------------------------------------------------
    // 用户自学习词库
    // ------------------------------------------------------------------

    /**
     * 把用户打出来的词记进自学习词库。
     *
     * 记的是「**拼音 → 文字**」：
     * - 整句记一条（"lianqishi" → "炼气士"）
     * - 再按分词结果拆成一个个词记（"woxiang" → "我想"、"chifan" → "吃饭"），
     *   这样以后只打「chifan」也能直接出「吃饭」
     *
     * 只记**两个及以上字**的词：单字字表里本来就有，记它没意义。
     */
    private fun learnCommit(text: String, units: List<Pair<String, String>>? = null) {
        val dict = userDict ?: return
        val list = units ?: learningUnits(text)
        if (list.isEmpty()) return
        for ((pinyin, word) in list) dict.learn(pinyin, word)
        scheduleUserDictFlush()
    }

    /**
     * 这一轮上屏的内容，能拆出哪些「拼音 → 词」。
     *
     * 按**字与音节一一对齐**来拆：一个音节一个字。
     * 对不齐（比如上屏的是词库里的长词，覆盖的音节数跟字数不一样）就不记——
     * 记错的比不记更糟。
     *
     * @param syllableCount 只算**前面几个音节**（提交前缀时用：后面的音节还在会话里）。
     */
    private fun learningUnits(text: String, syllableCount: Int = 0): List<Pair<String, String>> {
        val all = session.pending.map { it.syllable ?: "" }
        val syllables = if (syllableCount in 1..all.size) all.take(syllableCount) else all
        if (syllables.isEmpty() || syllables.size != text.length) return emptyList()
        if (text.length < 2) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        // 整句一条
        out += syllables.joinToString("") to text
        // 按分词拆开，一个词一条
        for ((pinyin, word) in engine.segmentUnits(syllables)) {
            if (word.length >= 2 && word != text) out += pinyin to word
        }
        return out
    }

    /**
     * **组合学习**：上一块 + 这一块，连起来也记一条。
     *
     * 用户先打「天道」，再打「符文」——以后打「tiandaofuwen」，
     * 候选栏第一个就该是「天道符文」。
     *
     * 只在**两块是连着的**时候才学（中间插了空格、标点就不学——
     * 那种情况下它们本来就是两个词）。
     *
     * 还有一条：词库里**已经有**的组合不学。
     * 「我想」词库里有，学不学都一样；学了反而把词频改了。
     * 真正要学的是词库里没有的组合——那是用户自己的用法。
     *
     * 规则本身在引擎层 [Learning.combination]（纯逻辑，可单测）。
     */
    private fun learnCombination(pinyin: String, text: String) {
        val dict = userDict ?: return
        val combo = Learning.combination(
            dictionary = engine.dictionary,
            prevPinyin = lastCommitPinyin,
            prevText = lastCommitText,
            pinyin = pinyin,
            text = text,
        ) ?: return
        dict.learn(combo.first, combo.second)
        scheduleUserDictFlush()
    }

    /**
     * 选字面板里挑出来的字，能记成哪些「拼音 → 词」。
     *
     * 用**面板自己的音节**（用户一个音节一个音节挑的），
     * 不用引擎的分词——引擎分的是「炼气 + 是」，用户挑的却是「炼气士」，
     * 照引擎的记法就把「是」也学进去了。
     */
    private fun panelLearningUnits(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < panelPicked.size) {
            if (panelPicked[i] == null) {
                i++
                continue
            }
            // 连续挑好的一段
            var j = i
            while (j + 1 < panelPicked.size && panelPicked[j + 1] != null) j++
            val chars = (i..j).mapNotNull { panelPicked[it] }.joinToString("")
            val pinyin = (i..j).mapNotNull { panelSegments.getOrNull(it) }.joinToString("")
            val count = j - i + 1
            if (chars.length >= 2 && chars.length == count) out += pinyin to chars
            i = j + 1
        }
        return out
    }

    /**
     * 词库落盘：延后一点批量做。
     *
     * 上屏一次写一次文件的话，打字会一顿一顿的。
     */
    private val userDictFlush = Runnable {
        val dict = userDict ?: return@Runnable
        Thread {
            dict.flush()
        }.apply {
            isDaemon = true
            name = "userdict-flush"
            start()
        }
    }

    private fun scheduleUserDictFlush() {
        mainHandler.removeCallbacks(userDictFlush)
        mainHandler.postDelayed(userDictFlush, USER_DICT_FLUSH_DELAY_MS)
    }

    private fun commitChar(ch: String) {
        // 同样统一走 session 里的实现，别在服务层再抄一遍
        val pinyin = session.pinyin
        val text = session.commitChar(ch)
        commitToHost(text, pinyin = pinyin)
    }

    private fun onSpacePressed() {
        // ① 滑选中了某条 → 空格上屏**选中的那条**
        if (commitSelectedCandidate()) return
        // ② 正在打拼音（有待上屏的候选）→ 空格上屏**第一候选**（双拼的老规矩）
        if (editingSyllable == null && session.pending.isNotEmpty()) {
            if (commitTopCandidate(includeNextWord = false)) return
        }
        // ③ 剪贴板：候选栏最前面那一块就是它 → 空格直接上屏
        //
        //    用户的要求：「复制的内容直接显示在候选区，点击空格键可以直接上屏」。
        //    剪贴板优先于下一词建议——它是用户**刚做的动作**，
        //    下一词只是猜测。
        if (editingSyllable == null && session.pending.isEmpty()) {
            if (clipboardCandidate() != null) {
                commitClipboard()
                return
            }
        }
        // ④ 没有待上屏的拼音，但候选栏挂着云端给的「下一词」建议
        //    → 空格上屏它（打完「智慧」再按一下空格 → 「智慧城市」）
        //
        //    这一条以前是「不」的（怕用户想打空格结果把建议词上屏了），
        //    但用户明确要求「联想词也上屏」——联想词就该用同一个键确认，
        //    否则候选栏里摆着一条却按空格没反应，比误上屏更别扭。
        //    （云端功能默认关着，不开的话这里永远走不到。）
        if (editingSyllable == null && session.pending.isEmpty() && nextWords.isNotEmpty()) {
            if (commitTopCandidate(includeNextWord = true)) return
        }
        // ⑤ 什么都没有 → 空格就是空格
        //
        // 以前空格只插空格、上屏全靠确认键，代价是「打完一段话要按两下才能上屏」。
        // 现在：有候选时空格就是上屏（滑选了哪条就上哪条，没滑就是第一候选）；
        // 没有候选时（比如刚上屏完、或者在打英文）空格依然是真正的空格。
        commitRaw(" ")
    }

    /**
     * 上屏当前的第一候选（空格和确认键共用）。
     *
     * @param includeNextWord 「下一词」建议要不要也上屏。
     *   确认键要（它是「打完就发」的主键）；空格不要——
     *   否则用户刚打完一个词、候选栏挂着下一词建议时按空格想打个空格，
     *   空格却把那个建议词上屏了。
     */
    private fun commitTopCandidate(includeNextWord: Boolean = true): Boolean {
        val top = topCandidateText
        if (top != null && session.pending.isNotEmpty()) {
            commitText(top)
            return true
        }
        if (includeNextWord && top != null && nextWords.isNotEmpty()) {
            commitNextWord(top)
            return true
        }
        val text = session.commitTop()
        if (text != null) {
            commitToHost(text)
            return true
        }
        return false
    }

    /**
     * **动作键**（右下角那颗蓝的）。
     *
     * 它**不做「选词上屏」**——上屏只有空格一个入口：
     * - 空格 → 上屏候选栏第一条（滑选了哪条就上哪条）
     * - 空格 → 上屏云端给的「下一词」建议
     *
     * 动作键做的是两件事，按顺序：
     *
     * 1. **收尾当前这轮输入**：如果预编辑区还有没定下来的拼音，
     *    按第一候选上屏。
     *
     *    为什么必须收尾：发送 / 搜索 / 换行都得作用在**真实文本**上。
     *    不收尾的话，微信发出去的是「ni hao」这串拼音，浏览器搜的是拼音。
     *
     *    注意它**不会**上屏滑选中的那条、也不会上屏云端联想词——
     *    那两样仍然只有空格能上屏（用户明确要求「只用空格键上屏」）。
     *
     * 2. **执行这个宿主该有的动作**：
     *
     *    | 宿主 | 键面 | 动作 |
     *    |---|---|---|
     *    | 微信等聊天工具 | 发送 | 把这条消息发出去 |
     *    | 多行文本框 | 换行 | 换到下一行 |
     *    | 浏览器地址栏 | 前往 | 搜索 / 打开网址 |
     *    | 普通单行输入框 | 完成 | 收尾这一轮输入 |
     */
    private fun onActionKeyPressed() {
        // ① 收尾当前这轮输入
        finishPendingInput()

        // ② 执行动作
        val ic = currentInputConnection ?: return
        when (actionKind) {
            ActionKind.SEND -> {
                if (!ic.performEditorAction(EditorInfo.IME_ACTION_SEND)) {
                    // 宿主不认 performEditorAction（不少聊天 App 是这样）：
                    // 退一步，发一个回车键事件过去——微信「按回车发送」就吃这一套。
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }
            }

            ActionKind.NEWLINE -> {
                // 多行文本框：回车是换行，不是发送
                ic.commitText("\n", 1)
            }

            ActionKind.GO -> {
                if (!ic.performEditorAction(EditorInfo.IME_ACTION_GO)) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }
            }

            ActionKind.SEARCH -> {
                if (!ic.performEditorAction(EditorInfo.IME_ACTION_SEARCH)) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }
            }

            ActionKind.DONE -> {
                ic.performEditorAction(EditorInfo.IME_ACTION_DONE)
            }

            ActionKind.NONE -> Unit
        }
    }

    /**
     * 收尾当前这轮输入：预编辑区还有拼音的话，按第一候选上屏。
     *
     * 动作键专用——**空格走的是另一条路**（空格会优先上屏滑选中的那条）。
     */
    private fun finishPendingInput() {
        // 「改这个字」模式不动：那是用户正在挑字，别插手
        if (editingSyllable != null) return
        if (session.pending.isEmpty()) return
        val top = topCandidateText
        if (top != null) {
            commitText(top, followUp = false)
            return
        }
        val pinyin = session.pinyin
        session.commitTop()?.let { commitToHost(it, followUp = false, pinyin = pinyin) }
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
        noteCommitted(text)
        // 下一词建议是模型给的，**没有拼音**——上下文接不上了，清掉。
        lastCommitPinyin = null
        lastCommitText = null
        localNextWords = emptyList()
        pushUndo(text)
        nextWords = emptyList()
        refresh()
    }



    private fun onBackspacePressed() {
        // 选字面板开着时，退格键 = **退回上一个音节**（不是删宿主里的字）
        if (panelBack()) return
        // 候选要变了，选中的那条也作废
        clearCandidateSelection()
        // 退格一次就把剪贴板候选收起来——
        // 它是一次性的，不能一直占着候选栏最前面（用户反馈）。
        dismissClipboard()
        if (rawMode) {
            // 原始字符是直接上屏的，退格直接删宿主里的一个字
            currentInputConnection?.deleteSurroundingText(1, 0)
            return
        }
        val ic = currentInputConnection ?: return
        // **光标在拼音中间** → 删光标前面那个字（用户点了拼音中间改错字）
        //
        // 光标在末尾时走下面的老路（按音节退）。
        if (session.caret > 0 && session.caret < session.composingText.length) {
            if (session.deleteBeforeCaret()) {
                refresh()
                return
            }
        }
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
        val before = hostTextBeforeCursor()
        if (before != null && before.isEmpty()) {
            // 宿主里光标前面确实没字了：什么也删不掉，
            // 也就别动记账（不然记账跟宿主越差越远）。
            //
            // 但**候选区里那块（剪贴板）要收掉**——
            // 用户就是按退格想把它删掉的（用户反馈「退格删不掉候选区的内容」）。
            // 以前这里直接 return，候选栏不重画，那块就一直挂在那儿。
            refresh()
            return
        }
        ic.deleteSurroundingText(1, 0)
        // 上下文也跟着变：不然下一轮联想会基于已经删掉的字。
        // 记账削掉一个字（trimCommitted 会同时修正 committedUnits）。
        trimCommitted(1)
        // 上文被删光了，联想也就没有依据了
        if (committedText.isEmpty() && hasLexeme) {
            resetLexeme()
        }
        // 重画候选栏：剪贴板候选刚被收起（dismissClipboard），要把它撤下去
        refresh()
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
        noteCommitted(t)
        // 语音没有拼音，上下文接不上
        lastCommitPinyin = null
        lastCommitText = null
        localNextWords = emptyList()
        pushUndo(t)
        refresh()
    }

    /**
     * 记一笔「上屏了这么多字」。
     *
     * 除了文本本身，还记下**这一笔的长度**——
     * 上滑退格要按词退（退一个上屏单元，而不是一个字一个字退），
     * 就靠这个长度表知道每个单元有多长。
     */
    private fun noteCommitted(text: String, pinyin: String? = null) {
        committedText.append(text)
        committedUnits.addLast(text)
        lastCommitStamp = SystemClock.uptimeMillis()
        // 上屏了就收起剪贴板候选：它是一次性的，不能一直占着候选栏最前面
        dismissClipboard()
        // 上下文：只有**中文**（有拼音）才接得上。
        // 原始字符（英文/数字/标点/空格）把上下文打断了——
        // 拿半个词去词库里查，出来的都是不相干的东西。
        if (pinyin != null && pinyin.isNotEmpty() && text.isNotEmpty()) {
            lastCommitPinyin = pinyin
            lastCommitText = text
        }
    }

    /**
     * 把「已上屏」的记账削掉最后 [n] 个字。
     *
     * 记账必须始终跟宿主一致——不然上滑退格会按错的长度去删，
     * 表现是「删了一半」或者「压根没删掉」。
     */
    private fun trimCommitted(n: Int) {
        if (n <= 0) return
        if (committedText.isNotEmpty()) {
            committedText.setLength((committedText.length - n).coerceAtLeast(0))
        }
        // 记账里的单元：整段削掉；最后一段可能只削掉一部分
        var left = n
        while (left > 0 && committedUnits.isNotEmpty()) {
            val last = committedUnits.last()
            if (last.length <= left) {
                committedUnits.removeLast()
                left -= last.length
            } else {
                committedUnits[committedUnits.lastIndex] = last.substring(0, last.length - left)
                left = 0
            }
        }
    }

    /**
     * 把最后一次上屏的那一块从 [old] 改成 [new]（云端纠错改写文字时用）。
     */
    private fun replaceLastCommittedUnit(old: String, new: String) {
        val last = committedUnits.lastOrNull() ?: return
        if (!last.endsWith(old)) return
        committedUnits[committedUnits.lastIndex] =
            last.substring(0, last.length - old.length) + new
    }

    /**
     * 宿主里光标前面的真实文字。
     *
     * 拿不到（有些 App 的 InputConnection 不支持）就返回 null——
     * 这时只能退回「按输入法自己的记账猜」。
     */
    private fun hostTextBeforeCursor(): String? =
        currentInputConnection?.getTextBeforeCursor(HOST_LOOKBACK_CHARS, 0)?.toString()

    /**
     * 从宿主的光标前面找出一个「词」的长度。
     *
     * 中文没有空格，所以按「连续的非空白、非标点字符」算一个词，
     * 上限 [MAX_UNIT_CHARS] 个字：退太多会吓到用户，退太少退不完。
     */
    private fun trailingWordLength(text: String): Int {
        var n = 0
        while (n < text.length && n < MAX_UNIT_CHARS) {
            val ch = text[text.length - 1 - n]
            if (ch.isWhitespace() || ch in WORD_BREAK_CHARS) break
            n++
        }
        return n.coerceAtLeast(1)
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
        // 改写了宿主里的文字 = 一次「上屏」，剪贴板候选收起来
        dismissClipboard()
        ic.deleteSurroundingText(target.length, 0)
        ic.commitText(fixed, 1)
        // 上下文也跟着改
        val cur = committedText.toString()
        if (cur.endsWith(target)) {
            committedText.setLength(cur.length - target.length)
            committedText.append(fixed)
            // 记账里的那一块也要换掉：纠错可能改动了长度，
            // 不换的话上滑退格会按旧长度去删（删一半 / 删不掉）。
            replaceLastCommittedUnit(target, fixed)
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

        /**
         * 撤销「上滑停顿删光」：把**整轮输入**放回去。
         *
         * 一次删掉的是整轮，不是一格 —— 恢复也得整个恢复。
         */
        data class RestoreInputState(val snapshot: InputSnapshot) : UndoOp
    }

    /**
     * 退格键上滑：**退一格**。
     *
     * - **正在输入拼音** → 退掉最后输入的那个**声母/韵母**
     *   （"xianren" → "xianr"：删掉 "ren" 的韵母，退回半截音节 "r"，
     *   用户按个韵母键就能改）
     * - **没在输入**（候选栏没内容） → 退掉已上屏的**一个单元**（一次上屏的那一整块）
     *
     * 为什么不一步删光：用户上滑往往只是想退回一两个音节重打，
     * 一步全删等于把已经打对的字也丢了。要一次删光就用「上滑停顿」。
     */
    private fun swipeUpDelete() {
        // ① 先退当前这一轮输入（一次退一个声母/韵母）
        val removal = session.swipeDelete()
        if (removal != null) {
            Log.d("ShuangpinIME", "上滑退一格：预编辑区 → '${session.composingText}'")
            undoStack.addLast(UndoOp.RestoreInput(removal))
            refresh()
            return
        }
        // ② 没有输入可退了：退掉已上屏的**一个词/句**
        Log.d("ShuangpinIME", "上滑退一格：退掉已上屏的一个单元")
        deleteLastCommittedUnit()
    }

    /**
     * 退格键**上滑后停顿**：把该删的一次删光。
     *
     * - **正在输入拼音** → 删掉**所有**已输入的拼音
     * - **没在输入**（候选栏没内容） → 删掉**光标以前的所有内容**
     *
     * 两种都记进撤销栈，下滑能原样恢复 ✓。
     */
    private fun swipeUpDeleteAll() {
        if (session.pending.isNotEmpty() || composing.isNotEmpty()) {
            // ① 正在输入拼音 → 整轮清空（不动已上屏的文字）
            val snapshot = session.snapshot()
            if (snapshot.pending.isEmpty() && snapshot.firstKey == null) return
            session.clearInput()
            Log.d("ShuangpinIME", "上滑停顿：删掉全部已输入的拼音")
            undoStack.addLast(UndoOp.RestoreInputState(snapshot))
            clearComposing()
            refresh()
            return
        }
        // ② 没在输入 → 光标以前的内容全删
        Log.d("ShuangpinIME", "上滑停顿：删掉光标以前的所有内容")
        deleteAllBeforeCursor()
    }

    /**
     * 删掉宿主里**光标以前的所有内容**（可撤销）。
     *
     * 以宿主里的真实文字为准：光标前面有什么就删什么，
     * 不管那些字是不是输入法自己上屏的。
     */
    private fun deleteAllBeforeCursor() {
        val ic = currentInputConnection ?: return
        val before = hostTextBeforeCursorAll() ?: return
        if (before.isEmpty()) return
        // 删了字 = 一次改动，剪贴板候选收起来
        dismissClipboard()
        ic.deleteSurroundingText(before.length, 0)
        // 记一笔「插入」，滑下来就能原样恢复
        undoStack.addLast(UndoOp.Insert(before))
        // 记账也跟着削
        trimCommitted(before.length)
        resetLexeme()
        refresh()
    }

    /**
     * 宿主里光标前面的**全部**文字（不是只有最近那几十个字）。
     *
     * `getTextBeforeCursor` 的窗口有限（HOST_LOOKBACK_CHARS 只有 64 个字），
     * 「全删」要拿的是完整的一段，所以优先用 `getExtractedText`。
     */
    private fun hostTextBeforeCursorAll(): String? {
        val ic = currentInputConnection ?: return null
        val extracted = try {
            ic.getExtractedText(null, 0)
        } catch (ignored: Throwable) {
            null
        }
        if (extracted != null) {
            val text = extracted.text?.toString() ?: return null
            val sel = extracted.selectionStart
            if (sel < 0 || sel > text.length) return null
            return text.substring(0, sel)
        }
        // 退路：退回来的窗口尽量大一些
        val before = ic.getTextBeforeCursor(CURSOR_QUERY_CHARS, 0)?.toString() ?: return null
        return before
    }

    /**
     * 退掉已上屏的**一个上屏单元**（一次上屏的那一整块，通常是一个词或一句）。
     *
     * 用户反馈「打一段话不能修改之前的文字，只能修改最后一个字」：
     * 一个字一个字退，想改中间那个错字得把后面的全删光重打。
     * 按单元退的话，退两次就到那儿了。
     */
    /**
     * 上滑退掉已上屏的一个单元（一次一格）。
     *
     * **以宿主里真实存在的文字为准**，而不是输入法自己的记账：
     * 记账（[committedText]）可能已经跟宿主脱节——
     * 宿主自己清过内容、用户手动改过字、云端纠错改写过长度……
     * 一旦脱节，按记账去删就会「删了个寂寞」，
     * 用户看到的就是**上滑偶尔对已上屏的文字没反应**。
     */
    private fun deleteLastCommittedUnit() {
        val ic = currentInputConnection ?: return
        // 宿主里光标前面的真实文字；拿不到就退回按记账退
        val before = hostTextBeforeCursor()
        val n: Int
        val removed: String
        if (before == null) {
            val cur = committedText.toString()
            if (cur.isEmpty()) return
            n = (committedUnits.lastOrNull()?.length ?: 1).coerceIn(1, cur.length)
            removed = cur.substring(cur.length - n)
        } else {
            if (before.isEmpty()) return      // 前面确实没字了，没什么可退
            // 退几格：记账还对得上就按记账（保住「按词退」的语义），
            // 对不上就按宿主里的实际文字猜一个「词」。
            val recorded = committedUnits.lastOrNull()
            n = if (recorded != null && recorded.isNotEmpty() && before.endsWith(recorded)) {
                recorded.length
            } else {
                trailingWordLength(before)
            }
            removed = before.substring(before.length - n)
        }
        ic.deleteSurroundingText(n, 0)
        // 记账跟着削（宿主里真被删掉的那几个字）
        trimCommitted(n)
        // 删了字 = 一次改动，剪贴板候选收起来
        dismissClipboard()
        // 记一笔「插入」，滑下来就能原样恢复
        undoStack.addLast(UndoOp.Insert(removed))
        // 上文变了，联想也就没有依据了
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
                // 记账也一起削（trimCommitted 会同时修正 committedUnits）
                trimCommitted(op.length)
            }

            is UndoOp.Insert -> {
                ic.commitText(op.text, 1)
                noteCommitted(op.text)
            }

            is UndoOp.RestoreInput -> {
                // 把上滑删掉的音节放回会话，预编辑区跟着恢复
                session.undoSwipe(op.removal)
            }

            is UndoOp.RestoreInputState -> {
                // 「上滑停顿」删掉的是整轮 → 整个放回去
                session.restore(op.snapshot)
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
            val pinyin = session.pinyin
            session.commitTop()?.let { commitToHost(it, pinyin = pinyin) }
            // 只按了声母、没定音节的半截拼音没法上屏，直接丢掉。
            // 别让它留在预编辑区里：下一个字符会把它顶掉，那半个拼音就凭空消失了。
            if (session.composingText.isNotEmpty()) {
                session.clear()
                clearComposing()
            }
        }
        currentMode = next
        keyboard?.mode = next
        clearCandidateSelection()
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
            // 回车 = 动作键：发送 / 换行 / 前往 / 完成，规则跟屏幕上那颗蓝键一样
            KeyEvent.KEYCODE_ENTER -> { onActionKeyPressed(); true }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ------------------------------------------------------------------

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        /** 日志标签。 */
        private const val TAG = "ShuangpinIME"

        /** 查「光标前面有多少字」时一次问这么多，够用了。 */
        private const val CURSOR_QUERY_CHARS = 4096

        /**
         * 自己挪完光标之后，**多久之内不信宿主回报的选区**（ms）。
         *
         * 250 ms：够覆盖宿主同步回报的来回，又能错开用户「刚挪完马上点一下」的操作。
         */
        private const val CARET_SET_GUARD_MS = 250L

        /**
         * **候选栏**在单手模式下的宽度比例。
         *
         * 键盘本体在单手模式下缩到 60%（每个键小 1/3，贴着拇指那一侧摆），
         * 但候选栏**不跟着缩**：候选栏缩了，一页能看见的候选太少，翻起来更累。
         *
         * 候选栏照样贴着键盘那一侧（右/左）——最顺手的候选还是够得着。
         */
        private const val CANDIDATE_WIDTH_RATIO = 0.86f

        /** 词库落盘的延迟（ms）：连着上屏好几次就攒一次，别每一下都写文件。 */
        private const val USER_DICT_FLUSH_DELAY_MS = 1500L

        /**
         * 键盘本体高度（dp），**固定不变**。
         * 键位坐标由它决定，所以它一变，用户的肌肉记忆就废。
         *
         * 候选栏加高时**这里等量减高**——总窗口高度不变，
         * 键位的绝对位置就一动不动，用户不用重新适应。
         */
        const val KEYBOARD_HEIGHT_DP = 216f

        /**
         * 候选栏高度（dp）。
         * 这一条**永远占位**，没候选时用 INVISIBLE 而不是 GONE——
         * GONE 会让布局塌陷、键盘跟着往上跳，用户的第二个键就按空了。
         *
         * 46 dp 是给「候选词的拼音角标」留的：角标(16dp) + 汉字(25dp) = 41dp，
         * 剩 5dp 余量。38 dp 塞不下，角标就会压在汉字上（真机截图抓过）。
         */
        const val CANDIDATE_BAR_DP = 46f

        /**
         * 输入法窗口高度（dp），**固定不变** = 候选栏 46 + 键盘 216 + 底部留白 56。
         */
        const val IME_WINDOW_HEIGHT_DP = 318f

        /**
         * 内容区高度（dp）= 候选栏 46 + 键盘 216。
         *
         * onComputeInsets 报的是**内容区**高度，不是整个窗口——
         * 报窗口高度的话，宿主应用会把输入框挤得更靠上，某些 App 里反而被遮住。
         */
        const val CONTENT_HEIGHT_DP = 254f

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

        /** 候选栏右端 ⌄ 箭头的宽度（dp）。 */
        const val ARROW_DP = 34f

        /**
         * 选字面板「单字」页最多显示多少个字。
         *
         * **不截断**：字表里有生僻字（蚖、圜、𫘪……），
         * 截了就等于打不出来——用户反馈「点下拉箭头文字选择不全，
         * 比如打 duan 里面没有蚖」。面板是竖着滑的，后面靠滑、不靠砍。
         *
         * 这个上限只是**保险丝**：字表万一出问题（某个音节塞进几千个字），
         * 面板不至于被撑爆。正常情况下一个音节的字远不到这个数
         * （字表本身每个音节最多 300 个字）。
         */
        const val PANEL_CHAR_LIMIT = 400

        /**
         * 往宿主里回看多少个字符（用来判断「光标前面到底还有什么字」）。
         *
         * 上滑退格要按**宿主里的真实文字**退，不能只信输入法自己的记账——
         * 记账跟宿主脱节时（宿主自己清过内容、纠错改写过长度），
         * 按记账删就会「删了个寂寞」，用户看到的就是上滑没反应。
         */
        private const val HOST_LOOKBACK_CHARS = 64

        /**
         * 一次上滑最多退多少个字（一个「词」的大致上限）。
         *
         * 退太多会吓到用户（一个字一个字全没了），退太少退不完。
         */
        private const val MAX_UNIT_CHARS = 8

        /** 算「一个词」的断点：这些字符后面的内容算另一个词。 */
        private val WORD_BREAK_CHARS =
            "，。！？、；：,.!?;:()（）【】《》〈〉“”\"'‘’…—-～~·　 \t\r\n"

        /**
         * 选中候选的**底色**：品牌蓝实底（见 res/drawable/bg_candidate_selected.xml）。
         *
         * 跟第一候选的暖黄底色明显不同——用户一眼能看出
         * 「现在空格会上的，是这条」。
         *
         * 以前这里是淡蓝（#DCE6FF），在一排浅色字里几乎看不出选中没选中
         * （用户反馈「滑动选词选中词后颜色太淡，用户不容易识别」），
         * 现在改成品牌蓝实底 + 白字，整条候选栏里最重的一块就是它。
         */

        /** 选中候选的字色：白字（压在品牌蓝底上）。 */
        private const val COLOR_SELECTED_TEXT = 0xFFFFFFFF.toInt()

        /** 选中候选里拼音角标的字色：浅蓝（压在品牌蓝底上，灰字看不清）。 */
        private const val COLOR_SELECTED_PINYIN = 0xFFD9E4FF.toInt()

        /** 「光标在哪」标签里那一竖线的颜色（品牌蓝）。 */
        private const val COLOR_ACCENT_TEXT = 0xFF2F6DF6.toInt()

        /** 剪贴板候选的字色（青——它不是候选，是个「刚复制的东西」）。 */
        private const val COLOR_CLIPBOARD_TEXT = 0xFF0E7C7B.toInt()

        /** 剪贴板候选的底色。 */
        private const val COLOR_CLIPBOARD_BG = 0xFFE2F4F3.toInt()

        /**
         * 剪贴板内容在候选栏里最多显示几个字（超了用「…」截断）。
         *
         * 上屏的仍然是**完整内容**——这里只是显示得太长会把候选栏撑爆。
         */
        const val CLIPBOARD_DISPLAY_MAX = 14

        /**
         * 本地下一词最多给几个。
         *
         * 5 个够挑了；再多候选栏就全是联想，正经候选反而看不见。
         */
        const val LOCAL_NEXT_LIMIT = 5

        /** 拼音角标的字色（没选中的时候）。 */
        private const val COLOR_PINYIN_BADGE = 0xFF8A94A6.toInt()

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

        /**
         * 停手多久之后才去算**长句纠错**（ms）。
         *
         * 250 ms：连着打字的时候不算（算了也白算，下一个键就变了），
         * 停下来才说明用户在看候选了。
         *
         * 这个计算在后台线程跑，不挡打字——但也别在每个键上都启动一次。
         */
        const val CORRECTION_DEBOUNCE_MS = 250L

        /** 当前使用的双拼方案。 */
        const val SCHEME_ID = "xiaohe"
    }
}
