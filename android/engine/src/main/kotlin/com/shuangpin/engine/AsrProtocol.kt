package com.shuangpin.engine

/**
 * 语音识别的协议细节（WAV 封装、响应解析）。
 *
 * 放在引擎层而不是 App 层，是为了**能单测**：
 * 这两处是最容易悄悄出错的地方——WAV 头少一个字节，
 * 有些接口会直接拒绝；返回结构多一种形态，解析就拿不到文字。
 *
 * 引擎模块是纯 JVM，这些函数不碰任何 Android API。
 */
object AsrProtocol {

    /**
     * 给 PCM 套一个标准 WAV 头。
     *
     * 绝大多数识别接口都吃标准 WAV（RIFF/WAVE/fmt/data），
     * 直接把裸 PCM 发过去多半会报「格式不对」。
     */
    fun wrapWav(pcm: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bits: Int = 16): ByteArray {
        val dataLen = pcm.size
        val out = ByteArray(44 + dataLen)
        val byteRate = sampleRate * channels * bits / 8
        writeAscii(out, 0, "RIFF")
        writeInt(out, 4, 36 + dataLen)
        writeAscii(out, 8, "WAVE")
        writeAscii(out, 12, "fmt ")
        writeInt(out, 16, 16)                       // fmt 块长度
        writeShort(out, 20, 1)                      // PCM
        writeShort(out, 22, channels)
        writeInt(out, 24, sampleRate)
        writeInt(out, 28, byteRate)
        writeShort(out, 32, channels * bits / 8)    // 块对齐
        writeShort(out, 34, bits)
        writeAscii(out, 36, "data")
        writeInt(out, 40, dataLen)
        System.arraycopy(pcm, 0, out, 44, dataLen)
        return out
    }

    /**
     * 从接口返回里抠出识别出来的文字。
     *
     * 兼容常见结构（`text` / `result` / `transcript` / `data.text` / `sentences[]`），
     * 也兼容直接返回纯文本。
     *
     * @return 文本；解析不出来就是 null（调用方按「没听清」处理）
     */
    fun parseText(body: String?): String? {
        if (body == null) return null
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return trimmed
        return try {
            val obj = MiniJson.parseObject(trimmed)
            for (key in listOf("text", "result", "transcript", "transcription")) {
                val v = obj.string(key)
                if (!v.isNullOrBlank()) return v
            }
            obj.obj("data")?.string("text")?.let { return it }
            obj.obj("result")?.string("text")?.let { return it }
            val sentences = obj.objList("sentences")
            if (sentences.isNotEmpty()) {
                return sentences.mapNotNull { it.string("text") }.joinToString("")
            }
            null
        } catch (t: Throwable) {
            null
        }
    }

    /** base64 编码（不带换行）。Android 8.0 以后 java.util.Base64 就能用。 */
    fun base64(data: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(data)

    private fun writeAscii(b: ByteArray, off: Int, s: String) {
        for (i in s.indices) b[off + i] = s[i].code.toByte()
    }

    private fun writeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun writeShort(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }
}
