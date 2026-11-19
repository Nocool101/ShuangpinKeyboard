package com.shuangpin.engine

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * 云端联想的请求参数。
 *
 * 三个字段对应三种「问法」，服务端按「有没有候选池」决定怎么用：
 *
 * | 字段 | 含义 |
 * |---|---|
 * | [text] | 已上屏的中文上文——「用户刚才打了什么」 |
 * | [pinyin] | 用户当前正在输入的拼音——「用户正在打什么」 |
 * | [candidates] | 引擎这一轮给出的候选池——「机器觉得可能是这些」 |
 *
 * - 给了候选池 → **消歧模式**：让云端按上文给候选池重新排序。
 *   本地分词分不出「智慧」还是「指挥」，但有了上文就能判断。
 * - 只给拼音 → **补全模式**：这个拼音最可能是什么词。
 * - 两个都不给 → **下一词模式**：接下来该打什么词。
 *
 * 另外三个字段是「可配置大模型」带来的：
 *
 * | 字段 | 含义 |
 * |---|---|
 * | [input] | 刚上屏、拿来纠错的文本（**纠错模式**） |
 * | [model] | 模型名。不填就用服务端自己配的那个 |
 * | [apiKey] | 直连厂商时用的 key（走自建服务时不用填） |
 */
data class LexemeRequest(
    /** 已上屏的中文上文。 */
    val text: String = "",
    /** 用户当前正在输入的拼音。 */
    val pinyin: String = "",
    /** 引擎这一轮的候选池（消歧模式用；空 = 不是消歧模式）。 */
    val candidates: List<String> = emptyList(),
    /** 刚上屏、待纠错的文本（纠错模式用）。 */
    val input: String = "",
    /** 模型名；空 = 用服务端默认的。 */
    val model: String = "",
    /** 直连厂商时用的 key；空 = 走自建服务（key 在服务端）。 */
    val apiKey: String = "",
    /** 采样温度。 */
    val temperature: Float = 0.3f,
) {
    /** 什么也没问——不发请求。 */
    val isEmpty: Boolean
        get() = text.isBlank() && pinyin.isBlank() && candidates.isEmpty() && input.isBlank()
}

/**
 * 云端「下一词预测」客户端。
 *
 * 只跟**自建服务**说话，不直接调模型 API：
 * API key 留在服务端，输入法里只有一个端点地址。
 *
 * 协议（`tools/lexeme_server.mjs`）：
 * ```
 * POST {endpoint}/lexeme
 *   {"text":"智慧","pinyin":"zhihui","candidates":["智慧","指挥"],"schema":2}
 * →  200
 *   {"next":["智慧"]}
 * ```
 *
 * 也支持**直连厂商**（OpenAI 兼容）：
 * ```
 * POST {endpoint}/chat/completions
 *   {"model":"xxx","messages":[{"role":"user","content":"..."}]}
 * →  200
 *   {"choices":[{"message":{"content":"{\"next\":[\"智慧\"]}"}}]}
 * ```
 *
 * **失败一律当没有**：网络不通、服务端 500、返回看不懂——都只是没有联想建议，
 * 输入法该怎样还怎样（联想是加分项，不是依赖项）。
 */
class LexemeClient(
    private val endpoint: String,
    /**
     * 协议模式：
     * - [PROTOCOL_SERVICE]：自建服务（默认），走 `/lexeme`
     * - [PROTOCOL_OPENAI]：直连厂商（OpenAI 兼容），走 `/chat/completions`
     */
    private val protocol: String = PROTOCOL_SERVICE,
    /** 出错时把原因报出去（logcat 在有些 ROM 上被过滤，得落到文件里）。 */
    private val onError: (String) -> Unit = {},
) {

    private val executor = Executors.newSingleThreadExecutor(
        ThreadFactory { r -> Thread(r, "shuangpin-lexeme").apply { isDaemon = true } },
    )

    private val lock = Any()

    /** 工作线程是否已经起来了（起来之后就不该再起第二个）。 */
    private var running = false

    /**
     * 正在发的那个请求。
     *
     * **它绝不能被后来的请求顶掉**——已经在飞的东西就是发出去了，
     * 顶掉等于让服务端白收一次请求、客户端白等一次结果。
     */
    private var inFlight: Pair<LexemeRequest, (List<String>?) -> Unit>? = null

    /**
     * 排队中的请求。
     *
     * **只留最新的一次**：旧的已经被用户的新输入取代了，问它纯属浪费。
     *
     * 为什么要有排队而不是「在飞就丢掉」：
     * 上屏后发的「下一词」请求还在飞，用户紧接着敲下一个字——
     * 这时发的「消歧」请求如果直接丢掉，功能就等于没有。
     */
    private var queued: Pair<LexemeRequest, (List<String>?) -> Unit>? = null

    /**
     * 请求联想建议。
     *
     * @param req      请求参数（上下文 / 拼音 / 候选池 / 模型 / key）
     * @param callback 成功时给建议列表，失败时给 null
     */
    fun request(req: LexemeRequest, callback: (List<String>?) -> Unit) {
        if (endpoint.isBlank() || req.isEmpty) {
            callback(null)
            return
        }
        var dropped: ((List<String>?) -> Unit)? = null
        val needWorker = synchronized(lock) {
            if (inFlight != null) {
                // 已经有一个在飞：新的排队，排队的那个旧的作废
                dropped = queued?.second
                queued = req to callback
                false
            } else {
                inFlight = req to callback
                running = true
                true
            }
        }
        // 被顶掉的那次，回调也要走一遍——不能留个悬着的回调。
        dropped?.invoke(null)
        if (needWorker) executor.execute { loop() }
    }

    /**
     * 依次把请求跑完。
     *
     * 写成循环而不是递归——递归的话请求一多就爆栈。
     */
    private fun loop() {
        while (true) {
            val job = synchronized(lock) {
                val j = inFlight ?: queued
                inFlight = null
                queued = null
                if (j == null) {
                    // 原子地告诉别人「我没活了」——
                    // 这时候来的请求会自己起一个工作线程
                    running = false
                }
                j
            } ?: return

            val (req, callback) = job
            var result: List<String>? = null
            var failure: Throwable? = null
            try {
                result = doRequest(req)
            } catch (t: Throwable) {
                failure = t
                result = null
            }
            failure?.let {
                // 引擎模块是纯 JVM，不能用 android.util.Log；
                // 错误通过 onError 回调交给上层（它会落到文件里）
                onError("${it.javaClass.simpleName}: ${it.message}")
            }
            callback(result)
        }
    }

    private fun doRequest(req: LexemeRequest): List<String>? =
        if (protocol == PROTOCOL_OPENAI) doOpenAi(req) else doService(req)

    /** 自建服务：`POST {endpoint}/lexeme`。 */
    private fun doService(req: LexemeRequest): List<String>? {
        val conn = open(endpoint.trimEnd('/') + "/lexeme")
        try {
            val body = buildServiceBody(req)
            writeBody(conn, body)
            val code = conn.responseCode
            if (code != 200) {
                onError("HTTP $code")
                return null
            }
            val resp = readBody(conn)
            val next = MiniJson.parseObject(resp).stringList("next")
            return next.filter { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 直连厂商（OpenAI 兼容）：`POST {endpoint}/chat/completions`。
     *
     * 模型的回复是一段文本，里面嵌着 `{"next":[...]}`——
     * 要先把它抠出来再解析（模型偶尔会带解释或代码块）。
     */
    private fun doOpenAi(req: LexemeRequest): List<String>? {
        val conn = open(endpoint.trimEnd('/') + "/chat/completions")
        try {
            val body = buildOpenAiBody(req)
            writeBody(conn, body)
            val code = conn.responseCode
            if (code != 200) {
                onError("HTTP $code")
                return null
            }
            val resp = readBody(conn)
            val obj = MiniJson.parseObject(resp)
            val content = obj.objList("choices").firstOrNull()
                ?.obj("message")?.string("content") ?: return null
            return parseNextFromText(content)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        return conn
    }

    private fun writeBody(conn: HttpURLConnection, body: String) {
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }

    private fun readBody(conn: HttpURLConnection): String =
        conn.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)

    /** 拼请求体。JSON 手拼，避免引擎模块引入依赖。 */
    private fun buildServiceBody(req: LexemeRequest): String {
        val sb = StringBuilder()
        sb.append("{\"text\":").append(jsonString(req.text))
        sb.append(",\"pinyin\":").append(jsonString(req.pinyin))
        sb.append(",\"input\":").append(jsonString(req.input))
        sb.append(",\"candidates\":[")
        req.candidates.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append(jsonString(c))
        }
        sb.append("]")
        if (req.model.isNotBlank()) sb.append(",\"model\":").append(jsonString(req.model))
        sb.append(",\"temperature\":").append(req.temperature)
        sb.append(",\"schema\":2}")
        return sb.toString()
    }

    /** 拼 OpenAI 兼容的请求体。 */
    private fun buildOpenAiBody(req: LexemeRequest): String {
        val sb = StringBuilder()
        sb.append("{\"model\":").append(jsonString(req.model))
        sb.append(",\"temperature\":").append(req.temperature)
        sb.append(",\"max_tokens\":512")
        sb.append(",\"messages\":[")
        sb.append("{\"role\":\"system\",\"content\":").append(jsonString(LexemePrompts.systemFor(req)))
        sb.append("},{\"role\":\"user\",\"content\":").append(jsonString(LexemePrompts.userFor(req)))
        sb.append("}]}")
        return sb.toString()
    }

    /** 从模型的文字回复里抠出 next 数组。 */
    private fun parseNextFromText(text: String): List<String> {
        var s = text.trim()
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(s)
        if (fence != null) s = fence.groupValues[1].trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        return try {
            MiniJson.parseObject(s.substring(start, end + 1)).stringList("next")
                .filter { it.isNotBlank() }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** 极简 JSON 字符串转义。 */
    private fun jsonString(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return "\"$sb\""
    }

    companion object {
        /** 自建服务协议。 */
        const val PROTOCOL_SERVICE = "service"

        /** 直连厂商（OpenAI 兼容）协议。 */
        const val PROTOCOL_OPENAI = "openai"

        private const val CONNECT_TIMEOUT_MS = 2000

        /**
         * 读超时给得宽：模型本身要 1~2.5 秒出结果，慢的时候能到 5 秒。
         * 这是在后台线程跑的，超时再宽也不影响打字——
         * 超时只是「这次没等到」，不是「输入不能用」。
         */
        private const val READ_TIMEOUT_MS = 10_000
    }
}

/**
 * 提示词。
 *
 * 放在引擎层是因为**直连厂商时由客户端自己拼提示词**，
 * 而走自建服务时服务端也用同一套说法——两边口径必须一致，
 * 否则「自建服务能消歧、直连不能」这种事最难查。
 */
object LexemePrompts {

    /** 按请求内容选提示词。 */
    fun systemFor(req: LexemeRequest): String = when {
        req.input.isNotBlank() -> SYSTEM_CORRECT
        req.candidates.isNotEmpty() -> SYSTEM_DISAMBIGUATE
        req.pinyin.isNotBlank() -> SYSTEM_COMPLETE
        else -> SYSTEM_NEXT
    }

    /** 把请求拼成给模型看的一段话。 */
    fun userFor(req: LexemeRequest): String {
        val lines = ArrayList<String>(4)
        if (req.text.isNotBlank()) lines += "上文：${req.text}"
        if (req.pinyin.isNotBlank()) lines += "正在输入：${req.pinyin}"
        if (req.candidates.isNotEmpty()) lines += "候选：${req.candidates.joinToString("、")}"
        if (req.input.isNotBlank()) lines += "待纠错：${req.input}"
        return lines.joinToString("\n")
    }

    /** 消歧：给了候选池，让模型按上文重新排序。 */
    const val SYSTEM_DISAMBIGUATE = """
        你是一个中文输入法的候选消歧引擎。
        用户已经输入了一段上文，现在正在输入一个拼音串，候选栏给出了若干个候选。
        请根据**上文**判断用户最可能想输入的是哪个候选。
        规则：
        1. **只能从给定的候选里挑**，绝对不要发明新词、不要改字；
        2. 给出最多 3 个候选，按可能性从高到低；
        3. 看不出哪个更可能就返回空数组 []；
        4. 只输出 JSON，形如 {"next":["词1","词2"]}，不要任何多余文字。
    """

    /** 补全：给了拼音，让模型猜这个词。 */
    const val SYSTEM_COMPLETE = """
        你是一个中文输入法的词预测引擎。
        用户已经输入了一段上文，现在正在输入一个拼音串。
        请根据上文和这个拼音串，预测他最可能想输入的词。
        规则：
        1. 给最多 3 个候选，按可能性从高到低；
        2. 每个候选必须是 1~4 个汉字组成的**词**，不要标点、不要单字、不要解释；
        3. 候选必须和这个拼音对得上（zhihui → 智慧/指挥/知会 这种）；
        4. 结合上文判断哪个更可能；
        5. 只输出 JSON，形如 {"next":["词1","词2"]}，不要任何多余文字。
    """

    /** 下一词：只给了上文，预测接下来打什么。 */
    const val SYSTEM_NEXT = """
        你是一个中文输入法的「下一词预测」引擎。
        根据用户已经输入的中文上文，预测他接下来最可能继续输入的词。
        规则：
        1. 给 3 个候选，按可能性从高到低；
        2. 每个候选必须是 1~3 个汉字组成的**词**，不要标点、不要单字、不要解释；
        3. 候选要能直接接在上文后面，读起来自然；
        4. 不要重复上文里已经出现的词；
        5. 只输出 JSON，形如 {"next":["词1","词2","词3"]}，不要任何多余文字。
    """

    /**
     * 纠错：把刚上屏的文本里的错别字改对。
     *
     * 只在**明显是错别字**的时候才动：
     * - 专有名词、缩写、拼音串一律不改（输入法最怕的就是把用户的词改掉）
     * - 不改用户的表达方式，只改字
     */
    const val SYSTEM_CORRECT = """
        你是一个中文输入法的错别字校对器。
        用户刚输入了一段中文，里面可能有选错的字（同音字、形近字）。
        请找出**明显的错别字**并改正。
        规则：
        1. 只改**确实是错别字**的地方，比如同音字选错（"我系广东人"→"我是广东人"）；
        2. 专有名词、缩写、拼音串、英文、数字**一律不要动**；
        3. 不要改写用户的表达方式，不要加词删词，只换字；
        4. 拿不准就原样返回；
        5. 只输出 JSON，形如 {"next":["改正后的整段文字"]}；没有错别字就输出 {"next":[]}。
    """
}
