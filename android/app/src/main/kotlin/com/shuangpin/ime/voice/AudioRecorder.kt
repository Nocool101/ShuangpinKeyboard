package com.shuangpin.ime.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 16 kHz 单声道 PCM16 采集。
 *
 * 移植自 `minimal-voice-ime-main` 的 `audio/AudioRecorder.java`，
 * 改动只有两处：
 * 1. 去掉蓝牙 SCO 那套（那项目自己后来也删了，理由是「SCO 没建好会录到一片静音」）；
 * 2. 蓝牙麦如果确实要用，走系统默认的 `VOICE_RECOGNITION` 源，由系统自己选设备。
 */
class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16000
    }

    interface Listener {
        /** 一块 PCM 数据。 */
        fun onPcm(data: ByteArray, length: Int)

        /** 出错了。 */
        fun onError(message: String)
    }

    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false
    private var listener: Listener? = null

    private var audioManager: AudioManager? = null
    private var mutedMusic = false
    private var mutedSystem = false

    /**
     * 开始录音。
     *
     * @return false 表示起不来（权限没有 / 麦克风被占用）
     */
    @SuppressLint("MissingPermission")
    fun start(context: Context, listener: Listener): Boolean {
        this.listener = listener
        this.audioManager = context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager?

        // VOICE_RECOGNITION：系统会优先用蓝牙耳机的麦克风，音质也比 MIC 好
        val source = MediaRecorder.AudioSource.VOICE_RECOGNITION

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (if (minBuf <= 0) SAMPLE_RATE * 2 else minBuf) * 2

        val rec = try {
            AudioRecord(
                source, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
        } catch (e: Exception) {
            listener.onError("无法创建录音器：${e.message}")
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            listener.onError("录音器初始化失败")
            return false
        }

        record = rec
        running = true
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            record = null
            running = false
            listener.onError("录音启动失败：${e.message}")
            return false
        }
        muteOutput()

        worker = Thread({
            val chunk = ByteArray(4096)
            while (running) {
                val rec2 = record ?: break
                val read = try {
                    rec2.read(chunk, 0, chunk.size)
                } catch (e: Exception) {
                    -1
                }
                if (read > 0) {
                    listener?.onPcm(chunk, read)
                } else if (read < 0) {
                    listener?.onError("录音读取失败：$read")
                    break
                }
            }
        }, "shuangpin-voice-capture").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * 录音期间把外放和按键音静掉，
     * 不然喇叭里的声音会被麦收进去变成回声。
     */
    private fun muteOutput() {
        val am = audioManager ?: return
        try {
            if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                am.setStreamMute(AudioManager.STREAM_MUSIC, true)
                mutedMusic = true
            }
            if (!am.isStreamMute(AudioManager.STREAM_SYSTEM)) {
                am.setStreamMute(AudioManager.STREAM_SYSTEM, true)
                mutedSystem = true
            }
        } catch (ignored: Throwable) {
            // 静音失败不致命
        }
    }

    /** 录完把之前自己静掉的还原，本来就是静音的就不去动它。 */
    private fun unmuteOutput() {
        val am = audioManager ?: return
        try {
            if (mutedMusic) am.setStreamMute(AudioManager.STREAM_MUSIC, false)
            if (mutedSystem) am.setStreamMute(AudioManager.STREAM_SYSTEM, false)
        } catch (ignored: Throwable) {
        }
        mutedMusic = false
        mutedSystem = false
    }

    fun stop() {
        running = false
        unmuteOutput()
        worker?.let {
            try {
                it.join(300)
            } catch (ignored: InterruptedException) {
            }
        }
        worker = null
        record?.let {
            try {
                it.stop()
            } catch (ignored: Exception) {
            }
            it.release()
        }
        record = null
    }

    val isRunning: Boolean get() = running
}
