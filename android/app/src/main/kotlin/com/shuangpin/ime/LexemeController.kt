package com.shuangpin.ime

import android.content.Context
import com.shuangpin.engine.LexemeRequest

/**
 * 云端功能的开关、节流与竞态控制。
 *
 * **默认关闭。** 开了它，用户输入的内容会发到端点指向的服务——
 * 这是隐私相关的决定，不能替用户做。
 *
 * 端点有两种模式（见 [ImeSettings]）：
 * - `service`：自建服务（`tools/lexeme_server.mjs`），**key 留在服务端**
 * - `openai`：直连厂商（OpenAI 兼容），key 存在本机
 */
class LexemeController(private val context: Context) {

    /**
     * 偏好存储。
     *
     * 用 `by lazy`：构造期 Context 还没 attachBaseContext，
     * 那时候碰 SharedPreferences 会抛（输入法直接起不来）。
     */
    private val settings: ImeSettings by lazy { ImeSettings(context) }

    private var client: LexemeClient? = null
    private var clientEndpoint: String = ""
    private var clientProtocol: String = ""

    /** 开关。默认关。 */
    val enabled: Boolean get() = settings.llmEnabled

    /** 联想服务地址，例如 `http://192.168.x.x:8787`。 */
    val endpoint: String get() = settings.llmEndpoint

    /**
     * 请求序号：**每个通道一份**。
     *
     * 「下一词」和「纠错」是两条互不相干的线——
     * 上一轮下一词的请求还在飞，不该把这一轮的纠错请求一起作废。
     */
    private val tokens = HashMap<String, Int>()

    /** 每个通道上一次请求的键——同样的请求不重复问。 */
    private val keys = HashMap<String, String>()

    /**
     * 请求联想建议。
     *
     * @param req      请求参数（上下文 / 拼音 / 候选池 / 模型）
     * @param channel  通道名（同一通道的旧请求会被新请求作废，不同通道互不影响）
     * @param onResult 建议列表；失败或被更新的请求取代时给空列表
     */
    fun request(req: LexemeRequest, channel: String, onResult: (List<String>) -> Unit) {
        if (!enabled) return
        if (req.isEmpty) return
        // 同样的问题不问第二遍
        val key = req.text + "|" + req.pinyin + "|" + req.input + "|" +
            req.candidates.joinToString(",") + "|" + req.model
        if (key == keys[channel]) return
        keys[channel] = key

        val c = clientFor()
        val myToken = (tokens[channel] ?: 0) + 1
        tokens[channel] = myToken
        diag(
            "请求[$channel] ctx=[${req.text}] pin=[${req.pinyin}] in=[${req.input}] " +
                "候选=${req.candidates.size} model=[${req.model}] endpoint=$endpoint"
        )
        c.request(req) { result ->
            // 过期结果丢弃
            if (myToken != tokens[channel]) {
                diag("丢弃过期结果[$channel]")
                onResult(emptyList())
                return@request
            }
            diag("返回 ${result?.size ?: 0} 条[$channel]: ${result?.joinToString("/")}")
            onResult(result ?: emptyList())
        }
    }

    /**
     * 停掉某个通道的在途请求。
     *
     * 用户按退格清掉候选栏里的联想时调用——
     * 在途的请求回来以后也不该再把联想塞回候选栏。
     */
    fun cancel(channel: String) {
        tokens[channel] = (tokens[channel] ?: 0) + 1
        keys.remove(channel)
        diag("已取消在途请求[$channel]")
    }

    /** 停掉所有通道。 */
    fun cancelAll() {
        for (ch in tokens.keys.toList()) cancel(ch)
    }

    /**
     * 诊断记录写到 App 私有目录。
     *
     * 有些 ROM 会过滤第三方进程的 logcat（「联想没生效」会变成无头案），
     * 所以关键信息要落到文件里——`adb shell run-as` 就能读。
     */
    private fun diag(msg: String) {
        try {
            java.io.File(context.filesDir, "rerank.log")
                .appendText("${System.currentTimeMillis()} [lexeme] $msg\n")
        } catch (ignored: Throwable) {
            // 诊断本身失败就算了
        }
    }

    private fun clientFor(): LexemeClient {
        val ep = endpoint
        val proto = settings.llmProtocol
        if (client == null || clientEndpoint != ep || clientProtocol != proto) {
            client = LexemeClient(
                endpoint = ep,
                onError = { msg -> diag("请求失败 $msg") },
                protocol = proto,
            )
            clientEndpoint = ep
            clientProtocol = proto
        }
        return client!!
    }
}
