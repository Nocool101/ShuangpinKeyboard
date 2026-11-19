package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音识别协议细节的测试（WAV 封装、返回解析）。
 *
 * 这两处是最容易悄悄出错的地方：
 * - WAV 头差一个字节，有些接口直接拒收；
 * - 返回结构多一种形态，解析就拿不到文字，用户看到的是「没听清」。
 */
class AsrProtocolTest {

    // ---------------------------------------------------------------
    // WAV 封装
    // ---------------------------------------------------------------

    @Test
    fun `WAV 头是标准 44 字节`() {
        val pcm = ByteArray(3200) { (it % 256).toByte() }
        val wav = AsrProtocol.wrapWav(pcm, 16000)
        assertEquals("44 字节头 + 数据", 44 + 3200, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.ISO_8859_1))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.ISO_8859_1))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.ISO_8859_1))
        assertEquals("data", String(wav, 36, 4, Charsets.ISO_8859_1))
    }

    @Test
    fun `WAV 头里的采样率与数据长度要对`() {
        val pcm = ByteArray(3200)
        val wav = AsrProtocol.wrapWav(pcm, 16000)
        // 采样率在偏移 24
        assertEquals(16000, leInt(wav, 24))
        // 声道数在偏移 22
        assertEquals(1, leShort(wav, 22))
        // 位深在偏移 34
        assertEquals(16, leShort(wav, 34))
        // data 块长度在偏移 40
        assertEquals(3200, leInt(wav, 40))
        // RIFF 长度 = 36 + 数据长度
        assertEquals(36 + 3200, leInt(wav, 4))
    }

    @Test
    fun `WAV 里的音频数据原样保留`() {
        val pcm = ByteArray(64) { (it - 32).toByte() }
        val wav = AsrProtocol.wrapWav(pcm, 16000)
        for (i in pcm.indices) {
            assertEquals("第 $i 个字节要原样保留", pcm[i], wav[44 + i])
        }
    }

    // ---------------------------------------------------------------
    // 返回解析
    // ---------------------------------------------------------------

    @Test
    fun `解析常见的几种返回结构`() {
        assertEquals("你好", AsrProtocol.parseText("""{"text":"你好"}"""))
        assertEquals("你好", AsrProtocol.parseText("""{"result":"你好"}"""))
        assertEquals("你好", AsrProtocol.parseText("""{"transcript":"你好"}"""))
        assertEquals("你好", AsrProtocol.parseText("""{"data":{"text":"你好"}}"""))
        assertEquals("你好", AsrProtocol.parseText("""{"result":{"text":"你好"}}"""))
    }

    @Test
    fun `解析分句返回`() {
        val json = """{"sentences":[{"text":"你好"},{"text":"世界"}]}"""
        assertEquals("你好世界", AsrProtocol.parseText(json))
    }

    @Test
    fun `纯文本返回直接用`() {
        assertEquals("你好世界", AsrProtocol.parseText("你好世界"))
        assertEquals("你好世界", AsrProtocol.parseText("  你好世界  "))
    }

    @Test
    fun `空返回和看不懂的返回都是 null`() {
        assertNull(AsrProtocol.parseText(null))
        assertNull(AsrProtocol.parseText(""))
        assertNull(AsrProtocol.parseText("   "))
        assertNull(AsrProtocol.parseText("""{"foo":"bar"}"""))
        assertNull(AsrProtocol.parseText("""{"text":""}"""))
    }

    // ---------------------------------------------------------------
    // 上传方式的选择
    // ---------------------------------------------------------------

    @Test
    fun `显式协议说了算`() {
        assertEquals(
            AsrProtocol.Upload.MULTIPART,
            AsrProtocol.uploadFor(AsrProtocol.PROTOCOL_MULTIPART, "随便什么地址"),
        )
        assertEquals(
            AsrProtocol.Upload.JSON,
            AsrProtocol.uploadFor(AsrProtocol.PROTOCOL_JSON, "https://x/v1/audio/transcriptions"),
        )
    }

    @Test
    fun `auto 模式按地址判断`() {
        assertEquals(
            AsrProtocol.Upload.MULTIPART,
            AsrProtocol.uploadFor(AsrProtocol.PROTOCOL_AUTO, "https://api.openai.com/v1/audio/transcriptions"),
        )
        assertEquals(
            AsrProtocol.Upload.JSON,
            AsrProtocol.uploadFor(AsrProtocol.PROTOCOL_AUTO, "https://example.com/asr"),
        )
    }

    @Test
    fun `MiniMax 必须走 multipart`() {
        // 踩过的坑：MiniMax 的形状跟 OpenAI 一样（multipart），只是路径是
        // /v1/speech_to_text。靠地址猜协议会把它判成 JSON → 对方回 400。
        assertEquals(
            AsrProtocol.Upload.MULTIPART,
            AsrProtocol.uploadFor(
                AsrProtocol.PROTOCOL_MULTIPART,
                "https://api.minimax.cn/v1/speech_to_text",
            ),
        )
        // auto 模式下它确实会被猜错——这正是要显式指定服务商的理由
        assertEquals(
            AsrProtocol.Upload.JSON,
            AsrProtocol.uploadFor(
                AsrProtocol.PROTOCOL_AUTO,
                "https://api.minimax.cn/v1/speech_to_text",
            ),
        )
    }

    @Test
    fun `静音 wav 能用来做连通性自检`() {
        val wav = AsrProtocol.silenceWav(0.5, 16000)
        // 0.5 秒 × 16000 采样 × 2 字节
        assertEquals(44 + 16000, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.ISO_8859_1))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.ISO_8859_1))
    }

    // ---------------------------------------------------------------

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
