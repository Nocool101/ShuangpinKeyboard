package com.shuangpin.ime

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.shuangpin.engine.AsrProtocol
import com.shuangpin.engine.FileUserDict
import com.shuangpin.engine.LexemeClient
import com.shuangpin.engine.LexemeRequest
import com.shuangpin.ime.voice.AsrConfig
import com.shuangpin.ime.voice.HttpAsrEngine
import java.io.File

/**
 * 输入法设置。
 *
 * 入口有两条：
 * - **系统的输入法设置**（长按地球键 → 设置）——声明在 manifest 的
 *   `android:settingsActivity` 里，这是 Android 的标准做法；
 * - 开发时也能用广播打开：`am broadcast -a com.shuangpin.ime.OPEN_SETTINGS`。
 *
 * 界面是代码搭的，不用 layout xml——
 * 跟键盘那边保持一致（键盘也是自绘的），少一层资源依赖。
 */
class SettingsActivity : Activity() {

    private lateinit var settings: ImeSettings

    /** 输入框要留到最后统一保存，不能改一个丢一个。 */
    private var cloudEndpoint: EditText? = null
    private var cloudModel: EditText? = null
    private var cloudApiKey: EditText? = null
    private var cloudTemperature: EditText? = null
    private var asrEndpoint: EditText? = null
    private var asrModel: EditText? = null
    private var asrApiKey: EditText? = null
    private var asrLanguage: EditText? = null

    /** 录音权限状态那行文本的 id（运行时生成，不能写死）。 */
    private val permStateViewId = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ImeSettings(this)
        setContentView(buildUi())
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private fun buildUi(): View {
        // 外层：蓝色头部 + 可滚动的设置内容
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
        }

        val header = buildHeader()
        outer.addView(header)

        val scroll = ScrollView(this).apply {
            // 挂载的瞬间，系统会把焦点给第一个可聚焦的子 View（第一个 EditText），
            // ScrollView 随即滚到那儿去。
            // FOCUS_BEFORE_DESCENDANTS 让焦点先落在 ScrollView 自己身上，
            // 输入框要用户点了才获得焦点。
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            isFocusableInTouchMode = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
            isFocusableInTouchMode = true
        }
        scroll.addView(root)
        scroll.post { scroll.requestFocus() }

        // ---- 设置内容（在可滚动区里）----
        root.addView(
            TextView(this).apply {
                text = "启用方法：系统设置 → 语言和输入法 → 虚拟键盘 → 启用并切换到「双拼输入法」。" +
                    "调不出键盘时，长按地球键也能进这一屏。"
                textSize = 13f
                setTextColor(Color.parseColor("#5B6472"))
            }
        )
        root.addView(divider())

        buildLayoutSection(root)
        root.addView(divider())
        buildUserDictSection(root)
        root.addView(divider())
        buildCloudSection(root)
        root.addView(divider())
        buildRerankSection(root)
        root.addView(divider())
        buildVoiceSection(root)
        root.addView(divider())
        buildCorrectSection(root)
        root.addView(divider())
        buildClipboardSection(root)
        root.addView(divider())

        root.addView(
            Button(this).apply {
                text = "保存"
                setOnClickListener {
                    save()
                    Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        )

        outer.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ),
        )

        applyInsets(outer, header)
        return outer
    }

    /**
     * 处理状态栏内边距。
     *
     * targetSdk 36 在 Android 15+ 上**强制 edge-to-edge**：窗口铺满全屏，
     * 内容不再自动避开状态栏。所以自己补这段留白。
     *
     * Android 10 及以下没有强制 edge-to-edge，窗口本来就避开状态栏，
     * 那时候不能再加一遍（否则头部会多出一截空白）。
     */
    private fun applyInsets(outer: View, header: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        outer.setOnApplyWindowInsetsListener { _, insets ->
            val top = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            header.setPadding(
                header.paddingLeft,
                top + dp(16),
                header.paddingRight,
                dp(16),
            )
            insets
        }
        outer.requestApplyInsets()
    }

    /**
     * 蓝色头部：图标 + 名字 + 一句话介绍。
     *
     * 这一屏现在是**桌面图标点进来的唯一落点**（候选栏里已经没有「设置」按钮了），
     * 所以得把「这个 App 是什么」讲清楚。
     */
    private fun buildHeader(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_app_header)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).also {
                it.rightMargin = dp(12)
            }
        }
        row.addView(icon)

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titles.addView(
            TextView(this).apply {
                text = "双拼输入法"
                textSize = 20f
                setTextColor(Color.WHITE)
            }
        )
        titles.addView(
            TextView(this).apply {
                text = "双层标签 · 歧义预消解"
                textSize = 13f
                setTextColor(0xCCFFFFFF.toInt())
            }
        )
        row.addView(titles)
        box.addView(row)

        box.addView(
            TextView(this).apply {
                text = "26 键常驻，声母位/韵母位只换标签不换布局；" +
                    "声母一定，合法韵母就唯一，9 个天生歧义的键不用盲赌。"
                textSize = 13f
                setTextColor(0xE6FFFFFF.toInt())
                setPadding(0, dp(12), 0, 0)
            }
        )
        return box
    }

    /**
     * 键盘布局：单手右手 / 单手左手 / 全键盘。
     *
     * 单手模式把字母区收窄到拇指那一侧，**字母的相对位置不变**——
     * 双拼的位置记忆（小鹤 zh→v、ch→i、sh→u）绑定在字母键位上，打乱就废。
     * 候选栏也跟着收窄到同一侧，否则最顺手的候选反而够不着。
     *
     * 改完下次弹键盘就生效（输入法服务是常驻的，不会因为改设置而重建）。
     */
    private fun buildLayoutSection(root: LinearLayout) {
        root.addView(sectionTitle("键盘布局"))
        root.addView(
            hint(
                "单手模式把整块键盘收窄到拇指那一侧（每个键比全键盘小 1/3），" +
                    "候选栏也挪到同一侧。\n" +
                    "字母的相对位置不变——双拼的位置记忆不受影响。改完下次弹键盘生效。"
            )
        )
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val options = listOf(
            "单手·右手（推荐）" to ImeSettings.KEY_LAYOUT_RIGHT,
            "单手·左手" to ImeSettings.KEY_LAYOUT_LEFT,
            "全键盘" to ImeSettings.KEY_LAYOUT_FULL,
        )
        val current = settings.keyLayout
        for ((label, value) in options) {
            val rb = RadioButton(this).apply { text = label }
            group.addView(rb)
            // 先定选中状态再挂监听——反过来的话初始化就会误触发一次
            rb.isChecked = value == current
            rb.setOnCheckedChangeListener { _, checked ->
                if (checked) settings.keyLayout = value
            }
        }
        root.addView(group)

        root.addView(sectionTitle("韵母位排法"))
        root.addView(
            hint(
                "「固定小鹤」= 每个韵母永远在它那个键上（ao 在 c、ian 在 m、uang 在 l），\n" +
                    "换声母也不用重新认位置。\n" +
                    "「拇指优先」= 单手时把韵母聚到拇指那边，够得着，\n" +
                    "但每换一个声母键位就换一套。"
            )
        )
        val finalGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val finalOptions = listOf(
            "固定小鹤（推荐）" to ImeSettings.FINAL_ARRANGE_FIXED,
            "拇指优先" to ImeSettings.FINAL_ARRANGE_THUMB,
        )
        val currentFinal = settings.finalArrange
        for ((label, value) in finalOptions) {
            val rb = RadioButton(this).apply { text = label }
            finalGroup.addView(rb)
            // 先定选中状态再挂监听——反过来的话初始化就会误触发一次
            rb.isChecked = value == currentFinal
            rb.setOnCheckedChangeListener { _, checked ->
                if (checked) settings.finalArrange = value
            }
        }
        root.addView(finalGroup)
    }

    /**
     * 自学习词库：看看学了多少，想的话可以清空。
     *
     * 词条**只存在本机**（应用私有目录），不上传、不联网。
     */
    private fun buildUserDictSection(root: LinearLayout) {
        root.addView(sectionTitle("自学习词库"))
        root.addView(
            hint(
                "你打过的词会记在这台设备上——词库里根本没有的也行（比如「炼气士」），\n" +
                    "下次打同样的拼音，它就排在候选栏第一位。"
            )
        )
        val count = userDictEntryCount()
        root.addView(
            TextView(this).apply {
                text = if (count > 0) "当前已学 $count 个词" else "还没有学到词"
                textSize = 14f
                setTextColor(Color.parseColor("#5B6472"))
                setPadding(0, dp(6), 0, dp(4))
            }
        )
        root.addView(
            Button(this).apply {
                text = "清空已学词条"
                isEnabled = count > 0
                setOnClickListener {
                    try {
                        userDictFile().delete()
                    } catch (ignored: Throwable) {
                        // 删不掉就算了，下一次启动时它会被空库覆盖
                    }
                    Toast.makeText(this@SettingsActivity, "已清空", Toast.LENGTH_SHORT).show()
                    // 重画这一屏，条数立刻归零
                    setContentView(buildUi())
                }
            }
        )
    }

    /** 用户词库文件。跟输入法服务用的是同一个路径。 */
    private fun userDictFile(): File = File(filesDir, ImeSettings.USER_DICT_FILE)

    /** 已学的词条数。文件很小，读一下不费劲。 */
    private fun userDictEntryCount(): Int = try {
        FileUserDict(userDictFile()).size
    } catch (ignored: Throwable) {
        0
    }

    /** 云端大模型：联想 / 消歧 / 纠错都用它。 */
    private fun buildCloudSection(root: LinearLayout) {
        root.addView(sectionTitle("云端大模型"))

        root.addView(
            switchRow(
                "启用云端功能",
                "关掉之后联想、纠错都不联网（隐私最稳）",
                settings.llmEnabled,
            ) { settings.llmEnabled = it }
        )

        root.addView(
            TextView(this).apply {
                text = "连接方式"
                textSize = 14f
                setTextColor(Color.parseColor("#5B6472"))
                setPadding(0, dp(8), 0, 0)
            }
        )
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val serviceRadio = RadioButton(this).apply {
            text = "自建服务"
            isChecked = settings.llmProtocol == ImeSettings.PROTOCOL_SERVICE
        }
        val openAiRadio = RadioButton(this).apply {
            text = "直连厂商"
            isChecked = settings.llmProtocol == ImeSettings.PROTOCOL_OPENAI
        }
        val anthropicRadio = RadioButton(this).apply {
            text = "直连(Anthropic)"
            isChecked = settings.llmProtocol == ImeSettings.PROTOCOL_ANTHROPIC
        }
        group.addView(serviceRadio)
        group.addView(openAiRadio)
        group.addView(anthropicRadio)
        group.setOnCheckedChangeListener { _, id ->
            settings.llmProtocol = when (id) {
                openAiRadio.id -> ImeSettings.PROTOCOL_OPENAI
                anthropicRadio.id -> ImeSettings.PROTOCOL_ANTHROPIC
                else -> ImeSettings.PROTOCOL_SERVICE
            }
        }
        root.addView(group)

        root.addView(
            hint(
                "自建服务：填 tools/lexeme_server.mjs 的地址（如 http://192.168.x.x:8787），" +
                    "模型和 key 都在服务端，APP 里不存 key。\n" +
                    "直连厂商（OpenAI 兼容）：地址填到 /v1 为止，" +
                    "如 https://api.minimax.cn/v1（会请求 /v1/chat/completions）。\n" +
                    "直连厂商（Anthropic 兼容）：地址填到 /anthropic 为止，" +
                    "如 https://api.minimax.cn/anthropic（会请求 /anthropic/v1/messages）。\n" +
                    "模型名和 key 填下面两栏，key 只存在这台手机上。"
            )
        )

        val endpoint = textRow(
            root,
            "服务地址",
            settings.llmEndpoint,
            "自建服务：http://192.168.x.x:8787 ｜ 直连：https://api.x.com/v1",
        ).also { cloudEndpoint = it }
        val model = textRow(root, "模型名", settings.llmModel, "留空 = 用服务端自己配的那个")
            .also { cloudModel = it }
        val apiKey = textRow(
            root,
            "API Key",
            settings.llmApiKey,
            "只有「直连厂商」才需要",
            password = true,
        ).also { cloudApiKey = it }
        val temperature = textRow(root, "温度", settings.llmTemperature.toString(), "0.3")
            .also { cloudTemperature = it }

        root.addView(
            Button(this).apply {
                text = "测试连接"
                setOnClickListener {
                    saveCloud(endpoint, model, apiKey, temperature)
                    testCloud()
                }
            }
        )
    }

    /** 端侧重排（本地 ONNX 模型）。 */
    private fun buildRerankSection(root: LinearLayout) {
        root.addView(sectionTitle("端侧重排模型"))
        root.addView(
            switchRow(
                "启用端侧重排",
                "本地 8M 模型，不联网。关掉的话候选按词频排",
                settings.rerankEnabled,
            ) { settings.rerankEnabled = it }
        )
    }

    /** 语音输入。 */
    private fun buildVoiceSection(root: LinearLayout) {
        root.addView(sectionTitle("语音输入（按住空格说话）"))
        root.addView(
            switchRow(
                "启用语音输入",
                "没有输入的时候按住空格说话，松手结束",
                settings.voiceEnabled,
            ) { settings.voiceEnabled = it }
        )

        // 老配置是按地址猜上传方式的。地址是 MiniMax 的 speech_to_text 时，
        // 说明它本来就该走 multipart —— 直接归位，省得用户再填一遍。
        if (settings.asrProtocol == AsrProtocol.PROTOCOL_AUTO &&
            settings.asrEndpoint.contains("speech_to_text")
        ) {
            settings.asrProtocol = AsrProtocol.PROTOCOL_MULTIPART
        }

        root.addView(
            TextView(this).apply {
                text = "识别服务商"
                textSize = 14f
                setTextColor(Color.parseColor("#5B6472"))
                setPadding(0, dp(10), 0, 0)
            }
        )
        root.addView(hint(ASR_PRESET_HINT))

        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val radios = ASR_PRESETS.map { preset ->
            RadioButton(this).apply {
                text = preset.label
                isChecked = preset.protocol == settings.asrProtocol
                tag = preset
            }.also { group.addView(it) }
        }
        root.addView(group)

        val endpoint = textRow(
            root,
            "识别接口地址",
            settings.asrEndpoint,
            "https://…/v1/audio/transcriptions",
        ).also { asrEndpoint = it }
        val model = textRow(root, "识别模型名", settings.asrModel, "例如 whisper-1 / paraformer")
            .also { asrModel = it }
        val apiKey = textRow(root, "API Key", settings.asrApiKey, "没有就留空", password = true)
            .also { asrApiKey = it }
        val language = textRow(root, "语言", settings.asrLanguage, "zh")
            .also { asrLanguage = it }

        // 换服务商：地址和模型名跟着换成对应的默认值。
        // 只在「当前值还是空白或某个预设的默认值」时才改——
        // 用户自己填的地址不能被覆盖掉。
        group.setOnCheckedChangeListener { _, id ->
            val preset = radios.firstOrNull { it.id == id }?.tag as? AsrPreset ?: return@setOnCheckedChangeListener
            settings.asrProtocol = preset.protocol
            val cur = settings.asrEndpoint
            if (cur.isBlank() || ASR_PRESETS.any { it.endpoint == cur }) {
                settings.asrEndpoint = preset.endpoint
                settings.asrModel = preset.model
                endpoint.setText(preset.endpoint)
                model.setText(preset.model)
            }
        }

        root.addView(
            Button(this).apply {
                text = "测试语音识别接口"
                setOnClickListener {
                    save()
                    testAsr()
                }
            }
        )
        root.addView(
            Button(this).apply {
                text = "保存语音配置并申请录音权限"
                setOnClickListener {
                    save()
                    requestMic()
                }
            }
        )
        root.addView(
            TextView(this).apply {
                id = permStateViewId
                text = ""
                textSize = 13f
                setTextColor(Color.parseColor("#5B6472"))
                setPadding(0, dp(8), 0, 0)
            }
        )
        updatePermState()
    }

    /**
     * 连通性自检：发一段 0.5 秒静音过去。
     *
     * 对方回 200 就说明地址、key、模型名都对——只是没说话所以没有文字。
     * 不用真的录一段音来测。
     */
    private fun testAsr() {
        val cfg = AsrConfig(
            endpoint = settings.asrEndpoint,
            model = settings.asrModel,
            apiKey = settings.asrApiKey,
            language = settings.asrLanguage,
            protocol = settings.asrProtocol,
        )
        if (!cfg.isUsable) {
            Toast.makeText(this, "先填识别接口地址", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在测试…", Toast.LENGTH_SHORT).show()
        HttpAsrEngine(cfg) { }.testConnection { error ->
            runOnUiThread {
                if (error == null) {
                    Toast.makeText(this, "连接正常：地址、key、模型名都对", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "连不上：$error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 自动纠错。 */
    private fun buildCorrectSection(root: LinearLayout) {
        root.addView(sectionTitle("自动纠错"))
        root.addView(
            switchRow(
                "启用自动纠错",
                "上屏后交给大模型挑错别字（比如「我系广东人」→「我是广东人」）",
                settings.correctEnabled,
            ) { settings.correctEnabled = it }
        )
        root.addView(
            switchRow(
                "直接改正",
                "关掉：只在候选栏给一个「纠错建议」，点了才改（稳）\n" +
                    "打开：识别出明显错别字就直接改掉（快，但有误改的风险）",
                settings.correctAutoApply,
            ) { settings.correctAutoApply = it }
        )
        root.addView(
            hint(
                "纠错走的是上面「云端大模型」那套配置：端点、模型名、key 都跟它共用。\n" +
                    "没启用云端功能的话，纠错不会生效。"
            )
        )
    }

    /**
     * 剪贴板：把刚复制的内容摆进候选栏。
     *
     * 关掉之后**完全不读剪贴板**（隐私最稳）。
     */
    private fun buildClipboardSection(root: LinearLayout) {
        root.addView(sectionTitle("剪贴板"))
        root.addView(
            switchRow(
                "候选栏显示剪贴板",
                "复制一段东西，切回来它就摆在候选栏最前面：\n" +
                    "点一下上屏，或者按空格上屏",
                settings.clipboardEnabled,
            ) { settings.clipboardEnabled = it }
        )
        root.addView(
            hint(
                "关掉之后输入法不再读取剪贴板。\n" +
                    "密码输入框里本来就不给剪贴板候选。"
            )
        )
    }

    // ------------------------------------------------------------------
    // 保存与测试
    // ------------------------------------------------------------------
    private fun save() {
        // 开关类的设置在切换时就已经写进 ImeSettings 了；
        // 输入框里的内容统一在这里落盘。
        cloudEndpoint?.let { settings.llmEndpoint = it.text.toString() }
        cloudModel?.let { settings.llmModel = it.text.toString() }
        cloudApiKey?.let { settings.llmApiKey = it.text.toString() }
        cloudTemperature?.let { settings.llmTemperature = it.text.toString().toFloatOrNull() ?: 0.3f }
        asrEndpoint?.let { settings.asrEndpoint = it.text.toString() }
        asrModel?.let { settings.asrModel = it.text.toString() }
        asrApiKey?.let { settings.asrApiKey = it.text.toString() }
        asrLanguage?.let { settings.asrLanguage = it.text.toString() }
    }

    private fun saveCloud(
        endpoint: EditText,
        model: EditText,
        apiKey: EditText,
        temperature: EditText,
    ) {
        settings.llmEndpoint = endpoint.text.toString()
        settings.llmModel = model.text.toString()
        settings.llmApiKey = apiKey.text.toString()
        settings.llmTemperature = temperature.text.toString().toFloatOrNull() ?: 0.3f
    }

    /** 真打一次接口：能通就把结果报出来，通不了把原因报出来。 */
    private fun testCloud() {
        if (!settings.llmEnabled) {
            Toast.makeText(this, "先打开「启用云端功能」", Toast.LENGTH_SHORT).show()
            return
        }
        if (settings.llmEndpoint.isBlank()) {
            Toast.makeText(this, "先填服务地址", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在测试…", Toast.LENGTH_SHORT).show()
        Thread({
            var error: String? = null
            var result: List<String>? = null
            try {
                val client = LexemeClient(
                    endpoint = settings.llmEndpoint,
                    onError = { msg -> error = msg },
                    protocol = settings.llmProtocol,
                )
                val latch = java.util.concurrent.CountDownLatch(1)
                client.request(
                    LexemeRequest(
                        text = "测试",
                        model = settings.llmModel,
                        apiKey = settings.llmApiKey,
                        temperature = settings.llmTemperature,
                    )
                ) { r ->
                    result = r
                    latch.countDown()
                }
                latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
            val msg = when {
                error != null -> "连不上：$error" + hintForError(error)
                result.isNullOrEmpty() -> "连上了，但没返回内容"
                else -> "连上了：${result!!.joinToString(" / ")}"
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }, "settings-test").apply { isDaemon = true }.start()
    }

    /**
     * 把常见错误翻译成人话。
     *
     * 光甩一个「HTTP 401」用户根本不知道该改哪儿——
     * 这几个码对应的都是「设置填错了」，直接告诉他怎么填。
     */
    private fun hintForError(error: String): String = when {
        error.contains("401") || error.contains("403") ->
            "\n（key 没填、填错了，或者没权限。直连厂商**必须**填 key）"
        error.contains("404") ->
            "\n（地址路径不对：OpenAI 兼容填到 /v1（如 https://api.minimax.cn/v1），" +
                "Anthropic 兼容填到 /anthropic（如 https://api.minimax.cn/anthropic））"
        error.contains("timeout") || error.contains("timed out") || error.contains("UnknownHost") ->
            "\n（地址或网络不通：检查地址、Wi-Fi；自建服务要确认那台电脑上的 node 还在跑）"
        error.contains("429") ->
            "\n（模型额度用完了：等恢复、充值，或换后端）"
        else -> ""
    }

    private fun requestMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "录音权限已授予", Toast.LENGTH_SHORT).show()
            updatePermState()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (ok) "录音权限已授予" else "没有录音权限，语音输入用不了",
                Toast.LENGTH_LONG,
            ).show()
            updatePermState()
        }
    }

    private fun updatePermState() {
        val view = findViewById<TextView>(permStateViewId) ?: return
        val ok = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        view.text = if (ok) "✓ 录音权限已授予" else "✗ 还没有录音权限，语音输入用不了"
    }

    // ------------------------------------------------------------------
    // 小控件
    // ------------------------------------------------------------------

    private fun sectionTitle(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.parseColor("#171B23"))
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun hint(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#7A8494"))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#E5E8EE"))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).also { it.topMargin = dp(12); it.bottomMargin = dp(4) }
    }

    private fun switchRow(
        title: String,
        desc: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        texts.addView(
            TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(Color.parseColor("#171B23"))
            }
        )
        if (desc.isNotBlank()) {
            texts.addView(
                TextView(this).apply {
                    text = desc
                    textSize = 12f
                    setTextColor(Color.parseColor("#7A8494"))
                }
            )
        }
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(sw)
        box.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(4) }
        return box
    }

    /** 一行「标题 + 输入框」。 */
    private fun textRow(
        parent: LinearLayout,
        title: String,
        value: String,
        placeholder: String,
        password: Boolean = false,
    ): EditText {
        parent.addView(
            TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(Color.parseColor("#5B6472"))
                setPadding(0, dp(10), 0, 0)
            }
        )
        val et = EditText(this).apply {
            // 顺序有讲究，三步都不能换：
            //   ① setSingleLine() —— 它内部会把输入类型重写成 SINGLE_LINE
            //   ② inputType      —— 放在 setSingleLine 之后，否则密码变「可见密码」
            //   ③ setText()      —— 放在 inputType 之后，否则掩码套不到已有文本上
            // 曾经把 setText 放在最前面，结果 API Key 以明文显示在屏幕上（真机截图抓到过）。
            setSingleLine()
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            setText(value)
            this.hint = placeholder
            textSize = 15f
            setTextColor(Color.parseColor("#171B23"))
        }
        parent.addView(
            et,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return et
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_MIC = 1001

        /**
         * 语音识别的服务商预设。
         *
         * @param protocol 上传方式（`multipart` / `json` / `auto`）
         * @param endpoint 选它时自动填的地址；空 = 让用户自己填
         * @param model 选它时自动填的模型名
         */
        data class AsrPreset(
            val label: String,
            val protocol: String,
            val endpoint: String,
            val model: String,
        )

        /**
         * 三个预设。
         *
         * **MiniMax 必须单独列出来**：它的形状跟 OpenAI 一样（multipart），
         * 只是路径是 `/v1/speech_to_text` 而不是 `/v1/audio/transcriptions`。
         * 靠地址猜协议会把 MiniMax 判成 JSON 模式，对方直接回 400。
         */
        private val ASR_PRESETS = listOf(
            AsrPreset("OpenAI 兼容（…/v1/audio/transcriptions）", AsrProtocol.PROTOCOL_AUTO, "", ""),
            AsrPreset("MiniMax（api.minimax.cn/v1/speech_to_text）", AsrProtocol.PROTOCOL_MULTIPART,
                "https://api.minimax.cn/v1/speech_to_text", "asr-1.0"),
            AsrPreset("通用 JSON（base64 + sample_rate）", AsrProtocol.PROTOCOL_JSON, "", ""),
        )

        private const val ASR_PRESET_HINT =
            "语音识别用的是**另一个接口**，不是上面那个大模型地址。填错地址对方会回 404：\n" +
                "· OpenAI 兼容：https://…/v1/audio/transcriptions\n" +
                "· MiniMax：https://api.minimax.cn/v1/speech_to_text（模型 asr-1.0）"
    }
}
