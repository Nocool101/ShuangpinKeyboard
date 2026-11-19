package com.shuangpin.ime.voice

import com.shuangpin.engine.AsrProtocol

/**
 * 语音识别结果。
 *
 * @param text 识别出来的文字
 * @param error 失败原因（成功时为 null）
 */
data class AsrResult(val text: String? = null, val error: String? = null) {
    val ok: Boolean get() = text != null && text.isNotBlank()
}

/**
 * 语音识别引擎接口。
 *
 * 上层不关心底层是「一次性上传」还是「流式」——本项目只实现了前者。
 */
interface AsrEngine {

    /**
     * 喂入 16 kHz、单声道、16 bit 小端 PCM。
     */
    fun feed(pcm: ByteArray, length: Int)

    /** 用户松手，结束录音并等待识别结果。 */
    fun finish()

    /** 放弃这次识别。 */
    fun cancel()
}

/**
 * HTTP 一次性上传的识别引擎。
 *
 * 移植自 `minimal-voice-ime-main` 的 `asr/HttpAsrEngine.java`，
 * 改动：OkHttp → `HttpURLConnection`、Gson → 引擎层的 `MiniJson`
 * （本项目的引擎模块刻意零依赖）。
 *
 * 兼容两种接口风格：
 * - **OpenAI 风格**（端点含 `audio/transcriptions`）：multipart 上传 wav
 * - **通用 JSON**：base64 音频 + `sample_rate`
 */
class HttpAsrEngine(
    private val config: AsrConfig,
    private val onResult: (AsrResult) -> Unit,
) : AsrEngine {

    private val buffer = java.io.ByteArrayOutputStream()

    @Volatile
    private var cancelled = false

    override fun feed(pcm: ByteArray, length: Int) {
        if (cancelled) return
        synchronized(buffer) {
            buffer.write(pcm, 0, length)
        }
    }

    override fun finish() {
        if (cancelled) return
        val audio = synchronized(buffer) { buffer.toByteArray() }
        if (audio.size < MIN_AUDIO_BYTES) {
            onResult(AsrResult(error = "说得太短了，按住多说一会儿"))
            return
        }
        Thread({
            try {
                val text = if (isOpenAiStyle(config.endpoint)) uploadMultipart(audio) else uploadJson(audio)
                if (cancelled) return@Thread
                if (text.isNullOrBlank()) {
                    onResult(AsrResult(error = "接口没有返回文字"))
                } else {
                    onResult(AsrResult(text = text))
                }
            } catch (t: Throwable) {
                if (!cancelled) {
                    onResult(AsrResult(error = "识别失败：${t.message}"))
                }
            }
        }, "shuangpin-asr").apply { isDaemon = true }.start()
    }

    override fun cancel() {
        cancelled = true
        synchronized(buffer) { buffer.reset() }
    }

    private fun isOpenAiStyle(path: String): Boolean =
        path.contains("audio/transcriptions")

    // ------------------------------------------------------------------
    // 两种上传方式
    // ------------------------------------------------------------------

    private fun uploadMultipart(audio: ByteArray): String? {
        val boundary = "----ShuangpinIme${System.currentTimeMillis()}"
        val conn = open(config.endpoint)
        try {
            conn.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )
            conn.doOutput = true
            conn.outputStream.use { out ->
                out.write("--$boundary\r\n".toByteArray())
                out.write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                        .toByteArray()
                )
                out.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
                out.write(wrapWav(audio))
                out.write("\r\n".toByteArray())
                if (config.model.isNotBlank()) {
                    out.write("--$boundary\r\n".toByteArray())
                    out.write(
                        "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                            .toByteArray()
                    )
                    out.write(config.model.toByteArray())
                    out.write("\r\n".toByteArray())
                }
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"language\"\r\n\r\n".toByteArray())
                out.write(config.language.toByteArray())
                out.write("\r\n".toByteArray())
                out.write("--$boundary--\r\n".toByteArray())
            }
            return readText(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun uploadJson(audio: ByteArray): String? {
        val conn = open(config.endpoint)
        try {
            val base64 = AsrProtocol.base64(audio)
            val sb = StringBuilder()
            sb.append("{\"audio\":\"").append(base64).append("\"")
            sb.append(",\"format\":\"pcm\",\"sample_rate\":").append(AudioRecorder.SAMPLE_RATE)
            if (config.model.isNotBlank()) {
                sb.append(",\"model\":\"").append(config.model).append("\"")
            }
            sb.append(",\"language\":\"").append(config.language).append("\"}")
            conn.outputStream.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
            return readText(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): java.net.HttpURLConnection {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (config.apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + config.apiKey)
        }
        return conn
    }

    private fun readText(conn: java.net.HttpURLConnection): String? {
        val code = conn.responseCode
        val body = try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        } catch (t: Throwable) {
            ""
        }
        if (code !in 200..299) {
            throw IllegalStateException("接口返回 $code：${body.take(120)}")
        }
        return parseText(body)
    }

    /**
     * 从返回里抠出文本。
     *
     * 真正的解析在引擎层（`AsrProtocol.parseText`）——那边能单测。
     */
    fun parseText(body: String?): String? = AsrProtocol.parseText(body)

    /** 给 PCM 套一个标准 WAV 头，绝大多数接口能直接吃。 */
    fun wrapWav(pcm: ByteArray): ByteArray = AsrProtocol.wrapWav(pcm, AudioRecorder.SAMPLE_RATE)

    companion object {
        /** 少于这个字节数就当「没说话」（约 0.1 秒）。 */
        private const val MIN_AUDIO_BYTES = 3200
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}

/**
 * 语音识别的配置（端点、模型、key、语言）。
 *
 * 用户在设置页里填；这里只做搬运。
 */
data class AsrConfig(
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val language: String,
) {
    /** 能不能用。 */
    val isUsable: Boolean get() = endpoint.isNotBlank()
}
