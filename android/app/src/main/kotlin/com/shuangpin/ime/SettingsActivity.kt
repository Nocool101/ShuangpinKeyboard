package com.shuangpin.ime

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.shuangpin.engine.LexemeClient
import com.shuangpin.engine.LexemeRequest

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
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(root)

        root.addView(
            TextView(this).apply {
                text = "双拼输入法 · 设置"
                textSize = 20f
                setTextColor(Color.parseColor("#171B23"))
                setPadding(0, 0, 0, dp(16))
            }
        )

        buildCloudSection(root)
        root.addView(divider())
        buildRerankSection(root)
        root.addView(divider())
        buildVoiceSection(root)
        root.addView(divider())
        buildCorrectSection(root)
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
        return scroll
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
        group.addView(serviceRadio)
        group.addView(openAiRadio)
        group.setOnCheckedChangeListener { _, id ->
            settings.llmProtocol = if (id == openAiRadio.id) {
                ImeSettings.PROTOCOL_OPENAI
            } else {
                ImeSettings.PROTOCOL_SERVICE
            }
        }
        root.addView(group)

        root.addView(
            hint(
                "自建服务：填 tools/lexeme_server.mjs 的地址，模型和 key 都在服务端，APP 里不存 key。\n" +
                    "直连厂商：填 API 地址（OpenAI 兼容），模型名和 key 填下面两栏，key 只存在这台手机上。"
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
        root.addView(
            hint(
                "接口兼容 OpenAI 的 /v1/audio/transcriptions（multipart 上传 wav），\n" +
                    "也兼容通用 JSON（base64 音频 + sample_rate）。"
            )
        )
        val endpoint = textRow(root, "识别接口地址", settings.asrEndpoint, "https://…/v1/audio/transcriptions")
            .also { asrEndpoint = it }
        val model = textRow(root, "识别模型名", settings.asrModel, "例如 whisper-1 / paraformer")
            .also { asrModel = it }
        val apiKey = textRow(root, "API Key", settings.asrApiKey, "没有就留空", password = true)
            .also { asrApiKey = it }
        val language = textRow(root, "语言", settings.asrLanguage, "zh")
            .also { asrLanguage = it }

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
                error != null -> "连不上：$error"
                result.isNullOrEmpty() -> "连上了，但没返回内容"
                else -> "连上了：${result!!.joinToString(" / ")}"
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }, "settings-test").apply { isDaemon = true }.start()
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
            setText(value)
            this.hint = placeholder
            textSize = 15f
            setTextColor(Color.parseColor("#171B23"))
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            setSingleLine()
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
    }
}
