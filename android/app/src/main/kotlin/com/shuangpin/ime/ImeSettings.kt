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

        const val KEY_ASR_ENABLED = "asr.enabled"
        const val KEY_ASR_ENDPOINT = "asr.endpoint"
        const val KEY_ASR_PROTOCOL = "asr.protocol"
        const val KEY_ASR_MODEL = "asr.model"
        const val KEY_ASR_API_KEY = "asr.apiKey"
        const val KEY_ASR_LANGUAGE = "asr.language"

        const val KEY_CORRECT_ENABLED = "correct.enabled"
        const val KEY_CORRECT_AUTO = "correct.autoApply"

        /** 自建服务协议。 */
        const val PROTOCOL_SERVICE = "service"

        /** 直连厂商（OpenAI 兼容）协议。 */
        const val PROTOCOL_OPENAI = "openai"
    }
}
