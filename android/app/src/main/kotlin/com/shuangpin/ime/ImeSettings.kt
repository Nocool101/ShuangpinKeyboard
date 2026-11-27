package com.shuangpin.ime

import android.content.Context
import android.content.SharedPreferences
import com.shuangpin.engine.AsrProtocol

/**
 * 输入法的全部设置。
 *
 * 集中在一处的原因：**设置界面、输入法服务、语音控制器读的是同一份**。
 * 散在各处的 prefs 迟早会写出「设置页改了没生效」这种 bug。
 *
 * 配置分四组：
 * - **云端大模型**（联想 / 消歧 / 纠错都用它）：开关、协议、端点、模型名、key
 * - **端侧重排**：开关
 * - **语音输入**：开关、ASR 端点、模型名、key、语言
 * - **自动纠错**：开关、提示还是直接改
 */
class ImeSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 搬家的时候要读老配置，得留个 Context。 */
    private val appContext: Context = context.applicationContext

    init {
        migrateLegacy()
    }

    // ------------------------------------------------------------------
    // 云端大模型
    // ------------------------------------------------------------------

    /** 云端功能总开关（联想 / 消歧 / 纠错都归它管）。 */
    var llmEnabled: Boolean
        get() = prefs.getBoolean(KEY_LLM_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_LLM_ENABLED, v).apply()

    /**
     * 协议：
     * - `service`：自建服务（`tools/lexeme_server.mjs`），**key 留在服务端**
     * - `openai`：直连厂商（OpenAI 兼容），key 存在本机
     * - `anthropic`：直连厂商（Anthropic 兼容），key 存在本机
     */
    var llmProtocol: String
        get() = prefs.getString(KEY_LLM_PROTOCOL, PROTOCOL_SERVICE) ?: PROTOCOL_SERVICE
        set(v) = prefs.edit().putString(KEY_LLM_PROTOCOL, v).apply()

    /** 服务地址。自建服务填 `http://192.168.x.x:8787`；直连填 API base（如 `https://api.x.com/v1`）。 */
    var llmEndpoint: String
        get() = prefs.getString(KEY_LLM_ENDPOINT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LLM_ENDPOINT, v.trim()).apply()

    /** 模型名。空 = 用服务端自己配的那个。 */
    var llmModel: String
        get() = prefs.getString(KEY_LLM_MODEL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LLM_MODEL, v.trim()).apply()

    /** API Key。**只用于直连厂商**；走自建服务时留在服务端，这里不用填。 */
    var llmApiKey: String
        get() = prefs.getString(KEY_LLM_API_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LLM_API_KEY, v.trim()).apply()

    /** 采样温度。 */
    var llmTemperature: Float
        get() = prefs.getString(KEY_LLM_TEMPERATURE, "0.3")?.toFloatOrNull() ?: 0.3f
        set(v) = prefs.edit().putString(KEY_LLM_TEMPERATURE, v.toString()).apply()

    /**
     * 键盘形态：
     * - `full`：全键盘，横跨屏宽
     * - `right`：单手·右手，字母区收窄到右侧
     * - `left`：单手·左手，字母区收窄到左侧
     *
     * **默认单手右手**——单手模式是这套键盘的推荐用法。
     * 字母的相对位置三种形态都一样（双拼的位置记忆不能动）。
     */
    var keyLayout: String
        get() = prefs.getString(KEY_KEY_LAYOUT, KEY_LAYOUT_RIGHT) ?: KEY_LAYOUT_RIGHT
        set(v) = prefs.edit().putString(KEY_KEY_LAYOUT, v).apply()

    /**
     * 韵母位的排法：
     * - `fixed`（默认）：**小鹤双拼标准布局**——每个韵母永远在它那个键上
     * - `thumb`：单手模式下把韵母聚到拇指那一侧（按常用度重排）
     *
     * 默认选 `fixed`：用户反馈「目前的布局不容易记忆」。
     * 重排过的那套每换一个声母就要重新认一遍键位，
     * 固定之后肌肉记忆才立得住（ao 永远在 c、ian 永远在 m……）。
     */
    var finalArrange: String
        get() = prefs.getString(KEY_FINAL_ARRANGE, FINAL_ARRANGE_FIXED) ?: FINAL_ARRANGE_FIXED
        set(v) = prefs.edit().putString(KEY_FINAL_ARRANGE, v).apply()

    /**
     * **键盘高度**的倍数（1.0 = 标准 216 dp）。
     *
     * 在键盘上的「设置」面板里调，调完立刻生效。
     *
     * 为什么要能调：有人手机小、标准高度按着费劲；
     * 也有人屏幕大、觉得键太小不够用。
     * 键位坐标随高度重排，肌肉记忆那一次得重新适应——所以默认还是 1.0。
     */
    var kbHeightScale: Float
        get() = prefs.getFloat(KEY_KB_HEIGHT_SCALE, 1f)
        set(v) = prefs.edit().putFloat(KEY_KB_HEIGHT_SCALE, v.coerceIn(MIN_KB_HEIGHT_SCALE, MAX_KB_HEIGHT_SCALE)).apply()

    /**
     * **单手模式下键盘本体占屏宽的比例**（0.80 = 标准）。
     *
     * 只在单手模式（全键盘 / 单手右手 / 单手左手）有意义：
     * 全键盘模式下键盘横跨整个屏宽，这个值不参与计算。
     */
    var kbWidthRatio: Float
        get() = prefs.getFloat(KEY_KB_WIDTH_RATIO, DEFAULT_KB_WIDTH_RATIO)
        set(v) = prefs.edit().putFloat(KEY_KB_WIDTH_RATIO, v.coerceIn(MIN_KB_WIDTH_RATIO, MAX_KB_WIDTH_RATIO)).apply()

    // ------------------------------------------------------------------
    // 端侧重排
    // ------------------------------------------------------------------

    /** 要不要用端侧 ONNX 重排模型。 */
    var rerankEnabled: Boolean
        get() = prefs.getBoolean(KEY_RERANK_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_RERANK_ENABLED, v).apply()

    // ------------------------------------------------------------------
    // 语音输入
    // ------------------------------------------------------------------

    /** 要不要启用「按住空格说话」。 */
    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_ASR_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ASR_ENABLED, v).apply()

    /**
     * 语音识别接口地址。
     *
     * 要填**语音识别**接口，不是大模型对话接口——填错的话对方回 404。
     *
     * - OpenAI 兼容：`.../v1/audio/transcriptions`
     * - MiniMax：`https://api.minimax.cn/v1/speech_to_text`
     */
    var asrEndpoint: String
        get() = prefs.getString(KEY_ASR_ENDPOINT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ASR_ENDPOINT, v.trim()).apply()

    /**
     * 语音识别的接口形态。
     *
     * - `multipart`：OpenAI 兼容与 MiniMax 都是这个（字段 `model` + `file`）
     * - `json`：通用 JSON，base64 音频 + `sample_rate`
     * - `auto`：按地址自动判断（老配置用的默认，够用但不精确）
     *
     * 为什么要有它：**MiniMax 的形状跟 OpenAI 一模一样，只是路径不同**
     * （`/v1/speech_to_text` 而不是 `/v1/audio/transcriptions`）。
     * 靠地址猜会把 MiniMax 误判成 JSON 模式，发过去对方直接 400。
     */
    var asrProtocol: String
        get() = prefs.getString(KEY_ASR_PROTOCOL, AsrProtocol.PROTOCOL_AUTO) ?: AsrProtocol.PROTOCOL_AUTO
        set(v) = prefs.edit().putString(KEY_ASR_PROTOCOL, v).apply()

    /** ASR 模型名。 */
    var asrModel: String
        get() = prefs.getString(KEY_ASR_MODEL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ASR_MODEL, v.trim()).apply()

    /** ASR 的 key。 */
    var asrApiKey: String
        get() = prefs.getString(KEY_ASR_API_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ASR_API_KEY, v.trim()).apply()

    /** 识别语言。 */
    var asrLanguage: String
        get() = prefs.getString(KEY_ASR_LANGUAGE, "zh") ?: "zh"
        set(v) = prefs.edit().putString(KEY_ASR_LANGUAGE, v.trim()).apply()

    // ------------------------------------------------------------------
    // 自动纠错
    // ------------------------------------------------------------------

    /** 要不要做自动纠错。 */
    var correctEnabled: Boolean
        get() = prefs.getBoolean(KEY_CORRECT_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_CORRECT_ENABLED, v).apply()

    /**
     * 纠错结果怎么落地：
     * - `false`：只在候选栏给一个「纠错建议」，用户点了才改（安全）
     * - `true`：识别出明显错别字就直接改掉（快，但有误改风险）
     */
    var correctAutoApply: Boolean
        get() = prefs.getBoolean(KEY_CORRECT_AUTO, false)
        set(v) = prefs.edit().putBoolean(KEY_CORRECT_AUTO, v).apply()

    // ------------------------------------------------------------------
    // 剪贴板
    // ------------------------------------------------------------------

    /**
     * 要不要把**剪贴板里的内容**摆进候选栏。
     *
     * 默认开：用户复制一段东西，切回来就能直接上屏（点一下或按空格）。
     *
     * 关掉之后完全不读剪贴板——介意的话可以在设置里关掉。
     */
    var clipboardEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_CLIPBOARD_ENABLED, v).apply()

    // ------------------------------------------------------------------

    /**
     * 把老版本的配置搬过来。
     *
     * 以前没有设置界面，云端联想是用 adb 往 `lexeme.xml` 里写的
     * （文件 `lexeme`，键 `enabled` / `endpoint`）。
     * 现在换到统一的 `shuangpin_settings`，第一次进来时搬一次，
     * 别让用户重新配一遍。
     */
    private fun migrateLegacy() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        try {
            val legacy = appContext.getSharedPreferences("lexeme", Context.MODE_PRIVATE)
            val endpoint = legacy.getString("endpoint", "") ?: ""
            if (endpoint.isNotBlank()) {
                prefs.edit()
                    .putString(KEY_LLM_ENDPOINT, endpoint)
                    .putBoolean(KEY_LLM_ENABLED, legacy.getBoolean("enabled", false))
                    .apply()
            }
        } catch (ignored: Throwable) {
            // 搬不过去就算了，用户在设置页重填一遍而已
        }
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    companion object {
        private const val PREFS = "shuangpin_settings"
        private const val KEY_MIGRATED = "migrated.legacy"

        const val KEY_LLM_ENABLED = "llm.enabled"
        const val KEY_LLM_PROTOCOL = "llm.protocol"
        const val KEY_LLM_ENDPOINT = "llm.endpoint"
        const val KEY_LLM_MODEL = "llm.model"
        const val KEY_LLM_API_KEY = "llm.apiKey"
        const val KEY_LLM_TEMPERATURE = "llm.temperature"

        const val KEY_RERANK_ENABLED = "rerank.enabled"

        const val KEY_KEY_LAYOUT = "key.layout"

        /** 全键盘。 */
        const val KEY_LAYOUT_FULL = "full"

        /** 单手·右手。 */
        const val KEY_LAYOUT_RIGHT = "right"

        /** 单手·左手。 */
        const val KEY_LAYOUT_LEFT = "left"

        const val KEY_FINAL_ARRANGE = "key.finalArrange"

        /** 键盘高度倍数。 */
        const val KEY_KB_HEIGHT_SCALE = "key.heightScale"

        /** 单手模式键盘宽度比例。 */
        const val KEY_KB_WIDTH_RATIO = "key.widthRatio"

        /** 键盘高度倍数的默认值（1.0 = 标准高度）。 */
        const val DEFAULT_KB_HEIGHT_SCALE = 1f

        /** 键盘高度倍数的下限（0.7 = 矮一点，拇指不用挪那么远）。 */
        const val MIN_KB_HEIGHT_SCALE = 0.7f

        /** 键盘高度倍数的上限（1.4 = 高一点，键大）。 */
        const val MAX_KB_HEIGHT_SCALE = 1.4f

        /** 单手模式宽度比例的默认值（跟键盘里的标准值一致）。 */
        const val DEFAULT_KB_WIDTH_RATIO = 0.80f

        /** 单手模式宽度比例的下限。 */
        const val MIN_KB_WIDTH_RATIO = 0.50f

        /** 单手模式宽度比例的上限。 */
        const val MAX_KB_WIDTH_RATIO = 1.0f

        /** 韵母位固定在小鹤标准键位（默认）。 */
        const val FINAL_ARRANGE_FIXED = "fixed"

        /** 韵母位按拇指侧重排。 */
        const val FINAL_ARRANGE_THUMB = "thumb"

        /**
         * 用户自学习词库的文件名（放在应用私有目录里）。
         *
         * 输入法服务和设置页都要用它——设置页的「清空已学词条」得删同一个文件。
         */
        const val USER_DICT_FILE = "user_dict.tsv"

        const val KEY_ASR_ENABLED = "asr.enabled"
        const val KEY_ASR_ENDPOINT = "asr.endpoint"
        const val KEY_ASR_PROTOCOL = "asr.protocol"
        const val KEY_ASR_MODEL = "asr.model"
        const val KEY_ASR_API_KEY = "asr.apiKey"
        const val KEY_ASR_LANGUAGE = "asr.language"

        const val KEY_CORRECT_ENABLED = "correct.enabled"
        const val KEY_CORRECT_AUTO = "correct.autoApply"

        const val KEY_CLIPBOARD_ENABLED = "clipboard.enabled"

        /** 自建服务协议。 */
        const val PROTOCOL_SERVICE = "service"

        /** 直连厂商（OpenAI 兼容）协议。 */
        const val PROTOCOL_OPENAI = "openai"

        /** 直连厂商（Anthropic 兼容）协议。 */
        const val PROTOCOL_ANTHROPIC = "anthropic"
    }
}
