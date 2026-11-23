package com.shuangpin.ime.voice

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 语音输入的流程控制。
 *
 * 移植自「极简语音输入法 Minimal Voice IME」（@科技小辛，MIT）的 `MinimalVoiceIME`
 * 里的录音/识别那一段，
 * 砍掉了：本地模型、流式中间结果、失败重试、蓝牙 SCO、剪贴板历史、
 * 口语整理（那部分跟本项目的「大模型可配置」重复了）。
 *
 * 流程：
 * ```
 * start()  → 录音中（用户按住空格）
 * stop()   → 松手 → 上传识别 → 回调文本
 * ```
 */
class VoiceController(
    private val context: Context,
    /** 状态变化（用来在候选栏上显示「录音中…」）。 */
    private val onState: (State) -> Unit,
    /** 识别结果。 */
    private val onText: (String) -> Unit,
    /** 出错。 */
    private val onError: (String) -> Unit,
) {

    /** 语音的状态。 */
    enum class State {
        /** 空闲。 */
        IDLE,

        /** 正在录音。 */
        RECORDING,

        /** 录音结束，正在识别。 */
        RECOGNIZING,
    }

    private val main = Handler(Looper.getMainLooper())
    private val recorder = AudioRecorder()

    @Volatile
    private var engine: HttpAsrEngine? = null

    @Volatile
    var state: State = State.IDLE
        private set

    val isRecording: Boolean get() = state == State.RECORDING

    val isBusy: Boolean get() = state != State.IDLE

    /**
     * 开始录音。
     *
     * @param config 识别配置
     * @return false 表示起不来（配置不全 / 麦克风被占用 / 权限没给）
     */
    fun start(config: AsrConfig): Boolean {
        if (isBusy) return false
        if (!config.isUsable) {
            onError("没填语音识别接口地址，去设置里配一下")
            return false
        }
        val e = HttpAsrEngine(config) { result ->
            main.post {
                state = State.IDLE
                // **必须通知出去**：不通知的话界面会一直停在「识别中…」
                onState(State.IDLE)
                if (result.ok) {
                    onText(result.text!!)
                } else {
                    onError(result.error ?: "识别失败")
                }
            }
        }
        engine = e

        val ok = recorder.start(context, object : AudioRecorder.Listener {
            override fun onPcm(data: ByteArray, length: Int) {
                e.feed(data, length)
            }

            override fun onError(message: String) {
                main.post {
                    state = State.IDLE
                    onError(message)
                }
            }
        })
        if (!ok) {
            e.cancel()
            engine = null
            state = State.IDLE
            return false
        }
        state = State.RECORDING
        onState(State.RECORDING)
        return true
    }

    /** 松手：结束录音，去识别。 */
    fun stop() {
        if (state != State.RECORDING) return
        recorder.stop()
        state = State.RECOGNIZING
        onState(State.RECOGNIZING)
        engine?.finish()
    }

    /** 放弃这次语音（比如手势被打断、用户按了别的键）。 */
    fun cancel() {
        if (state == State.IDLE) return
        recorder.stop()
        engine?.cancel()
        engine = null
        state = State.IDLE
        onState(State.IDLE)
    }
}
