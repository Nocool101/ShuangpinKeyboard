package com.shuangpin.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开发用的配置入口。
 *
 * 有设置界面之后它就不是唯一入口了，但留着 handy——
 * 改配置不用点手机，也方便脚本化：
 *
 * ```
 * adb shell am broadcast -n com.shuangpin.ime/.ConfigureReceiver \
 *   -a com.shuangpin.ime.CONFIGURE \
 *   --es endpoint http://192.168.x.x:8787 --ez enable true
 * ```
 *
 * 支持的 extra：
 * - `endpoint`：云端服务地址
 * - `enable`：云端功能开关
 * - `model`：模型名
 * - `protocol`：`service`（自建服务）/ `openai`（直连厂商）
 *
 * **不导出**（`android:exported="false"`）：别的 App 不能偷偷打开云端联想。
 */
class ConfigureReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val settings = ImeSettings(context)
        intent.getStringExtra("endpoint")?.let { settings.llmEndpoint = it }
        intent.getStringExtra("model")?.let { settings.llmModel = it }
        intent.getStringExtra("protocol")?.let { settings.llmProtocol = it }
        intent.getStringExtra("asr_endpoint")?.let { settings.asrEndpoint = it }
        intent.getStringExtra("asr_model")?.let { settings.asrModel = it }
        if (intent.hasExtra("enable")) {
            settings.llmEnabled = intent.getBooleanExtra("enable", false)
        }
        if (intent.hasExtra("asr_enable")) {
            settings.voiceEnabled = intent.getBooleanExtra("asr_enable", false)
        }
        if (intent.hasExtra("correct_enable")) {
            settings.correctEnabled = intent.getBooleanExtra("correct_enable", false)
        }
        android.util.Log.i(
            "ShuangpinLexeme",
            "已配置：enabled=${settings.llmEnabled} endpoint=${settings.llmEndpoint} " +
                "model=${settings.llmModel} protocol=${settings.llmProtocol} " +
                "asr=${settings.asrEndpoint} voice=${settings.voiceEnabled} " +
                "correct=${settings.correctEnabled}",
        )
    }
}
