package com.shuangpin.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * 端侧重排模型（chinese-ime-lm 的按键重排模型）。
 *
 * 它**不做拼音解码**：喂拼音得不到中文。它在**引擎拼好的候选之间**排序，
 * 判断上文支持哪一个。分数是「每字平均的对数概率」。
 *
 * ## 为什么只在「等长候选」里用
 *
 * 模型是按「所有候选都覆盖整个输入」的池训练和测量的。
 * 把长短不一的候选混在一起喂它，它会偏向短候选（单字只有一项，
 * 且常用字概率高）——实测那样会把整句准确率从 77% 砸到 32%。
 * 所以调用方要保证传进来的候选**等长**。
 *
 * ## 权重
 *
 * ONNX 图（`lm_step.ort`）+ `model.json` + `vocab.json`，
 * Apache-2.0，可闭源商用；语料署名见随包的 NOTICE。
 *
 * 实现是 [chinese-ime-lm](https://github.com/metasequoiaime/chinese-ime-lm)
 * 的 `neural/choice/score_ort.py` 口径的 Kotlin 移植（喂数逻辑逐行对照
 * `neural/choice/model.py` 的 `context_inputs` / `step_inputs`）。
 */
class Reranker private constructor(
    private val session: OrtSession,
    private val cfg: Config,
    private val charIds: Map<Char, Long>,
) {

    /** 模型结构参数（来自 model.json）。 */
    data class Config(
        val layers: Int,
        val heads: Int,
        val headDim: Int,
        val width: Int,
        val contextChars: Int,
        val bos: Long,
        val unk: Long,
    )

    /**
     * 给候选打分并重排。
     *
     * @param context 光标之前的已上屏文本（模型的上文）
     * @param candidates 候选，**必须等长**
     * @return 按分数降序的候选（分数相同时保持原顺序）
     */
    fun rank(context: String, candidates: List<String>): List<String> {
        if (candidates.size < 2) return candidates
        val bodies = candidates.map { encode(it) }
        if (bodies.any { it.isEmpty() }) return candidates

        // 上文按 MSIME 交给重排器的方式截短：给最长的候选留出位置
        val longest = candidates.maxOf { it.length }
        val text = rerankContext(context, cfg.contextChars + CANDIDATE_ROOM, longest)
            .takeLast(cfg.contextChars)
        val ctx = longArrayOf(cfg.bos) + encode(text)

        // ① 上文跑进一个空缓存
        val first = session.run(emptyCache() + contextFeed(ctx))
        val cache = cacheFrom(first)
        first.close()

        // ② 只跑候选，读分数
        val plan = planKeystroke(ctx.size, bodies)
        // 所有候选都只有一个字时，没有前缀要新算，图的 visible 会变成 0 行——
        // Java 的 ORT API 不接受零维张量。这种输入不重排：
        // 候选都是单字，模型的收益本来就小。
        if (plan.fresh.isEmpty()) return candidates
        val feed = keystrokeFeed(ctx.size, bodies, plan)
        val second = session.run(cache + feed)
        val scores = floatData(second[0])
        second.close()

        // 分数降序，同分保持原顺序（Python 参考实现取的是「第一个最大值」）
        return candidates.indices
            .sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
            .map { candidates[it] }
    }

    // ------------------------------------------------------------------
    // 喂数：与 neural/choice/model.py 逐行对应
    // ------------------------------------------------------------------

    /** `context_inputs`：上文是一串节点，每个能看到它之前的那些。 */
    private fun contextFeed(context: LongArray): Map<String, OnnxTensor> {
        val n = context.size
        val visible = BooleanArray(n * n) { i -> (i % n) <= (i / n) }
        return mapOf(
            "nodes" to tensor(context),
            "positions" to tensor(LongArray(n) { it.toLong() }),
            "visible" to boolTensor2d(visible, n, n),
            // 一个占位候选，它的分数被忽略
            "candidates" to tensor(longArrayOf(context[0]), longArrayOf(1L, 1L)),
            "steps" to tensor(longArrayOf(0L), longArrayOf(1L, 1L)),
            "lengths" to tensor(longArrayOf(1L), longArrayOf(1L)),
        )
    }

    /**
     * `step_inputs`：一次按键里，候选还没进缓存的前缀。
     *
     * 共同前缀只跑一次；候选的最后一个字从不跑（上文最后一个节点预测它的首字）。
     */
    /**
     * 一次按键的**规划**：哪些候选前缀还没进缓存、要新算哪些节点。
     *
     * 先规划再建张量，是因为有个边界情况：所有候选都只有一个字时，
     * 没有前缀要算，图的 `visible` 会变成 0 行——Java 的 ORT API 不接受零维张量
     * （`OrtException: Supplied array has a zero dimension at 0`）。
     * 先规划就能在**建张量之前**判断出来，直接不重排。
     */
    private class StepPlan(
        /** 前缀 -> 节点编号 */
        val index: Map<List<Long>, Int>,
        /** 这次要新算的前缀 */
        val fresh: List<List<Long>>,
        /** 缓存里的全部前缀（顺序即节点顺序） */
        val every: List<List<Long>>,
    )

    private fun planKeystroke(
        nContext: Int,
        bodies: List<LongArray>,
        cached: List<List<Long>> = emptyList(),
    ): StepPlan {
        val index = HashMap<List<Long>, Int>()
        val fresh = ArrayList<List<Long>>()
        for (body in bodies) {
            val list = body.toList()
            for (t in 1 until body.size) {
                val prefix = list.subList(0, t)
                if (!index.containsKey(prefix)) {
                    index[prefix] = nContext + cached.size + fresh.size
                    fresh.add(prefix)
                }
            }
        }
        return StepPlan(index, fresh, cached + fresh)
    }

    private fun keystrokeFeed(
        nContext: Int,
        bodies: List<LongArray>,
        plan: StepPlan,
    ): Map<String, OnnxTensor> {
        val index = plan.index
        val fresh = plan.fresh
        val every = plan.every
        val cols = nContext + every.size

        val nodes = LongArray(fresh.size) { fresh[it].last() }
        val positions = LongArray(fresh.size) { (nContext + fresh[it].size - 1).toLong() }

        val visible = BooleanArray(fresh.size * cols)
        for ((i, p) in fresh.withIndex()) {
            for (j in 0 until nContext) visible[i * cols + j] = true
            for ((j, q) in every.withIndex()) {
                visible[i * cols + nContext + j] = q.size <= p.size && p.subList(0, q.size) == q
            }
        }

        val width = bodies.maxOf { it.size }
        val steps = LongArray(bodies.size * width)
        val padded = LongArray(bodies.size * width)
        val lengths = LongArray(bodies.size)
        for ((b, body) in bodies.withIndex()) {
            val list = body.toList()
            lengths[b] = body.size.toLong()
            System.arraycopy(body, 0, padded, b * width, body.size)
            for (t in 0 until width) {
                steps[b * width + t] = when {
                    t >= body.size -> 0L
                    // 候选的首字由上文的最后一个节点预测
                    t == 0 -> (nContext - 1).toLong()
                    else -> index[list.subList(0, t)]!!.toLong()
                }
            }
        }

        return mapOf(
            "nodes" to tensor(nodes),
            "positions" to tensor(positions),
            "visible" to boolTensor2d(visible, fresh.size, cols),
            "candidates" to tensor(padded, longArrayOf(bodies.size.toLong(), width.toLong())),
            "steps" to tensor(steps, longArrayOf(bodies.size.toLong(), width.toLong())),
            "lengths" to tensor(lengths),
        )
    }

    private fun emptyCache(): Map<String, OnnxTensor> = mapOf(
        "cache_k" to tensor(FloatArray(0), longArrayOf(cfg.layers.toLong(), cfg.heads.toLong(), 0L, cfg.headDim.toLong())),
        "cache_v" to tensor(FloatArray(0), longArrayOf(cfg.layers.toLong(), cfg.heads.toLong(), 0L, cfg.headDim.toLong())),
        "cache_h" to tensor(FloatArray(0), longArrayOf(0L, cfg.width.toLong())),
        "cache_z" to tensor(FloatArray(0), longArrayOf(0L)),
    )

    /** 把上文那次运行的输出接成下一次按键的缓存。 */
    private fun cacheFrom(result: OrtSession.Result): Map<String, OnnxTensor> {
        val k = floatData(result[1])
        val v = floatData(result[2])
        val h = floatData(result[3])
        val z = floatData(result[4])
        val m = z.size.toLong()
        return mapOf(
            "cache_k" to tensor(k, longArrayOf(cfg.layers.toLong(), cfg.heads.toLong(), m, cfg.headDim.toLong())),
            "cache_v" to tensor(v, longArrayOf(cfg.layers.toLong(), cfg.heads.toLong(), m, cfg.headDim.toLong())),
            "cache_h" to tensor(h, longArrayOf(m, cfg.width.toLong())),
            "cache_z" to tensor(z, longArrayOf(m)),
        )
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun encode(text: String): LongArray {
        val out = LongArray(text.length)
        for ((i, c) in text.withIndex()) out[i] = charIds[c] ?: cfg.unk
        return out
    }

    private fun tensor(data: LongArray, shape: LongArray = longArrayOf(data.size.toLong())): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    private fun tensor(data: FloatArray, shape: LongArray = longArrayOf(data.size.toLong())): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    /** 二维 bool 张量（visible）。ORT 没有 BooleanBuffer 重载，用嵌套数组的 Object 版。 */
    private fun boolTensor2d(data: BooleanArray, rows: Int, cols: Int): OnnxTensor {
        val arr = Array(rows) { data.copyOfRange(it * cols, (it + 1) * cols) }
        return OnnxTensor.createTensor(env, arr)
    }

    private fun floatData(value: OnnxValue): FloatArray {
        val buf = (value as OnnxTensor).getFloatBuffer()
        val out = FloatArray(buf.remaining())
        buf.get(out)
        return out
    }

    companion object {
        private const val CANDIDATE_ROOM = 16
        private const val CONTEXT_STEP = 16

        private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

        /**
         * MSIME 的 `rerank_context`：重排器在候选旁边能读到的上文有多长。
         */
        fun rerankContext(context: String, window: Int, longest: Int): String {
            val room = maxOf(0, window - (longest + 1))
            val keep = room / CONTEXT_STEP * CONTEXT_STEP
            if (context.length <= room) return context
            return context.substring(context.length - keep)
        }

        /**
         * 加载模型。
         *
         * @param modelBytes `lm_step.ort` 的字节
         * @param configJson `model.json` 的内容
         * @param vocabJson `vocab.json` 的内容
         */
        fun load(
            modelBytes: ByteArray,
            configJson: String,
            vocabJson: String,
            threads: Int = 1,
        ): Reranker {
            val cfgObj = MiniJson.parseObject(configJson)
            val cfg = Config(
                layers = cfgObj.int("layers"),
                heads = cfgObj.int("heads"),
                headDim = cfgObj.int("head_dim"),
                width = cfgObj.int("width"),
                contextChars = cfgObj.int("context_chars"),
                bos = cfgObj.long("bos"),
                unk = cfgObj.long("unk"),
            )

            val vocab = MiniJson.parseObject(vocabJson)
            val tokens = vocab.stringList("tokens")
            // 建字符 -> id 的映射：只有长度为 1 的项是字符，
            // 保留 token（<pad> 等）靠常量寻址，不靠文本匹配
            val charIds = HashMap<Char, Long>()
            for ((i, t) in tokens.withIndex()) {
                if (t.length == 1) charIds[t[0]] = i.toLong()
            }

            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
            val session = env.createSession(modelBytes, options)
            return Reranker(session, cfg, charIds)
        }
    }
}

/**
 * 极简 JSON 读取器。
 *
 * 只为读模型的两个配置文件与云端联想的响应而存在，所以不引第三方库——
 * 引擎模块刻意保持零依赖（它要能复用到桌面端）。
 * 只支持对象、数组、字符串、数字、布尔、null。
 */
object MiniJson {

    fun parseObject(text: String): JsonObject {
        val p = Parser(text)
        p.skipWs()
        return p.readObject()
    }

    class JsonObject(private val map: Map<String, Any?>) {
        fun int(key: String): Int = (map[key] as Number).toInt()
        fun long(key: String): Long = (map[key] as Number).toLong()
        fun stringList(key: String): List<String> = (map[key] as List<*>).map { it as String }

        /** 取字符串；没有或类型不对就是 null。 */
        fun string(key: String): String? = map[key] as? String

        /** 取子对象；没有或类型不对就是 null。 */
        fun obj(key: String): JsonObject? = map[key] as? JsonObject

        /** 取对象数组；没有或类型不对就是空列表。 */
        fun objList(key: String): List<JsonObject> =
            (map[key] as? List<*>)?.mapNotNull { it as? JsonObject } ?: emptyList()

        /** 取浮点数。 */
        fun double(key: String): Double? = (map[key] as? Number)?.toDouble()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun readObject(): JsonObject {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                i++
                return JsonObject(map)
            }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                skipWs()
                map[key] = readValue()
                skipWs()
                val c = s[i++]
                if (c == '}') break
                if (c != ',') error("JSON 期望 , 或 }，实际 '$c'")
            }
            return JsonObject(map)
        }

        private fun readValue(): Any? {
            skipWs()
            return when (peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                i++
                return out
            }
            while (true) {
                out.add(readValue())
                skipWs()
                val c = s[i++]
                if (c == ']') break
                if (c != ',') error("JSON 期望 , 或 ]，实际 '$c'")
            }
            return out
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when {
                    c == '"' -> break
                    c == '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                val hex = s.substring(i, i + 4)
                                i += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun readNumber(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            val raw = s.substring(start, i)
            return if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
                raw.toDouble()
            } else {
                raw.toLong()
            }
        }

        private fun <T> readLiteral(text: String, value: T): T {
            if (!s.startsWith(text, i)) error("JSON 期望 $text")
            i += text.length
            return value
        }

        private fun peek(): Char = s[i]

        private fun expect(c: Char) {
            skipWs()
            if (s[i] != c) error("JSON 期望 '$c'，实际 '${s[i]}'")
            i++
        }
    }
}
