package com.shuangpin.engine.eval

import com.shuangpin.engine.Candidates
import com.shuangpin.engine.DataLoader
import com.shuangpin.engine.InputSession
import com.shuangpin.engine.engine
import java.io.File

/**
 * 导出「本引擎的候选池」，供端侧模型评估使用。
 *
 * ## 为什么要用真引擎生成
 *
 * 端侧模型（chinese-ime-lm 的按键重排模型）是在**别人的候选池**上训练和测量的。
 * 想知道它对**我们**有没有用，必须把候选池换成我们自己的——
 * 而且必须是 App 里那一份，否则测出来的数没有意义。
 *
 * 所以候选的装配规则下沉到了引擎层（`Candidates.build`），这里直接调它。
 *
 * ## 用法
 *
 * ```
 * gradlew :engine:exportEvalPool -PevalPool=<评测集.jsonl> -PevalOut=<输出.jsonl>
 * ```
 *
 * 评测集每行至少要有 `id` / `input`（全拼，无分隔符）/ `gold` / `context` 四个字段。
 */
object ExportEvalPool {

    @JvmStatic
    fun main(args: Array<String>) {
        val inPath = args.getOrNull(0) ?: "D:/tmp/ime-analysis/chinese-ime-lm-main/eval/sentences-ctx-v1/pool.jsonl"
        val outPath = args.getOrNull(1) ?: "D:/tmp/ime-analysis/our-pool.jsonl"

        println("载入引擎数据……")
        val data = DataLoader().load()
        val engine = data.engine("xiaohe")
        val syllables = data.table.syllables

        // 按首字母给音节建索引：切分时只试首字母相同的那些
        val byFirst = HashMap<Char, MutableList<String>>()
        for (s in syllables) {
            if (s.isEmpty()) continue
            byFirst.getOrPut(s[0]) { mutableListOf() }.add(s)
        }
        println("音节 ${syllables.size} 个，载入完成")

        val out = File(outPath)
        out.parentFile?.mkdirs()
        var total = 0
        var skipped = 0
        var empty = 0
        val poolSizes = ArrayList<Int>()

        out.bufferedWriter(Charsets.UTF_8).use { writer ->
            File(inPath).forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val case = parseJson(line) ?: return@forEachLine
                val id = case["id"] ?: return@forEachLine
                val pinyin = case["input"] ?: return@forEachLine
                val gold = case["gold"] ?: return@forEachLine
                val context = case["context"] ?: ""
                total++

                // 全拼串切音节：用例带了音节数，按它切；切不出来就按最少的音节数切
                val want = case["syllables"]?.toIntOrNull()
                val parts = segment(pinyin, want, byFirst)
                if (parts == null) {
                    skipped++
                    return@forEachLine
                }

                // 还原成一次真实输入：每个音节编码成双拼键位，逐键按进会话
                val session = InputSession(engine)
                var ok = true
                for (syl in parts) {
                    val code = engine.encode(syl)
                    if (code.length != 2) {
                        ok = false
                        break
                    }
                    for (ch in code) {
                        if (!session.pressKey(ch.toString())) {
                            ok = false
                            break
                        }
                    }
                    if (!ok) break
                }
                if (!ok || session.pending.isEmpty()) {
                    skipped++
                    return@forEachLine
                }

                // 候选装配：与 App 里显示的是同一份逻辑
                val cands = Candidates.build(engine, session)
                if (cands.isEmpty()) {
                    empty++
                    return@forEachLine
                }
                poolSizes += cands.size

                val sb = StringBuilder()
                sb.append("{\"id\":\"").append(esc(id)).append("\"")
                sb.append(",\"input\":\"").append(esc(pinyin)).append("\"")
                sb.append(",\"gold\":\"").append(esc(gold)).append("\"")
                sb.append(",\"context\":\"").append(esc(context)).append("\"")
                sb.append(",\"syllables\":").append(parts.size)
                sb.append(",\"candidates\":[")
                cands.forEachIndexed { i, c ->
                    if (i > 0) sb.append(',')
                    sb.append('"').append(esc(c.text)).append('"')
                }
                sb.append("]")
                sb.append(",\"kinds\":[")
                cands.forEachIndexed { i, c ->
                    if (i > 0) sb.append(',')
                    sb.append('"').append(c.kind.name).append('"')
                }
                sb.append("]}")
                writer.write(sb.toString())
                writer.newLine()
            }
        }

        println("用例 $total 条，导出 ${poolSizes.size} 条，跳过 $skipped 条（切不出音节或输入不成形），空候选 $empty 条")
        if (poolSizes.isNotEmpty()) {
            val sorted = poolSizes.sorted()
            println(
                "候选池大小：中位 ${sorted[sorted.size / 2]}  平均 ${"%.1f".format(poolSizes.average())}  " +
                    "最大 ${sorted.last()}  最小 ${sorted.first()}",
            )
        }
    }

    /**
     * 把全拼串切成音节。
     *
     * @param want 期望的音节数；给了就按它切（用例里带着这个数）
     * @return 音节列表；切不出来返回 null
     */
    private fun segment(
        pinyin: String,
        want: Int?,
        byFirst: Map<Char, List<String>>,
    ): List<String>? {
        if (pinyin.isEmpty()) return null
        if (want != null && want > 0) {
            segmentExact(pinyin, want, byFirst)?.let { return it }
        }
        // 退路：不限定音节数，取最少的切法
        for (n in 1..pinyin.length) {
            segmentExact(pinyin, n, byFirst)?.let { return it }
        }
        return null
    }

    /** 恰好切成 count 个音节。记忆化搜索。 */
    private fun segmentExact(
        pinyin: String,
        count: Int,
        byFirst: Map<Char, List<String>>,
    ): List<String>? {
        val memo = HashMap<Int, List<String>?>()
        fun go(pos: Int, left: Int): List<String>? {
            if (left == 0) return if (pos == pinyin.length) emptyList() else null
            if (pos >= pinyin.length || left > pinyin.length - pos) return null
            val key = pos * 64 + left
            if (memo.containsKey(key)) return memo[key]
            var result: List<String>? = null
            for (syl in byFirst[pinyin[pos]] ?: emptyList()) {
                if (!pinyin.startsWith(syl, pos)) continue
                val rest = go(pos + syl.length, left - 1) ?: continue
                result = listOf(syl) + rest
                break
            }
            memo[key] = result
            return result
        }
        return go(0, count)
    }

    // ---- 极简 JSON 解析：只取我们要的几个字符串字段 ----

    private fun parseJson(line: String): Map<String, String>? {
        val out = HashMap<String, String>()
        var i = 0
        val n = line.length
        while (i < n) {
            // 找下一个 "key":
            val k = line.indexOf('"', i)
            if (k < 0) break
            val kEnd = line.indexOf('"', k + 1)
            if (kEnd < 0) break
            val key = line.substring(k + 1, kEnd)
            // 冒号
            var j = kEnd + 1
            while (j < n && (line[j] == ' ' || line[j] == ':')) j++
            if (j >= n) break
            val value: String
            if (line[j] == '"') {
                val sb = StringBuilder()
                var p = j + 1
                while (p < n) {
                    val c = line[p]
                    if (c == '\\' && p + 1 < n) {
                        when (line[p + 1]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'u' -> {
                                val hex = line.substring(p + 2, minOf(p + 6, n))
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                p += 4
                            }
                            else -> sb.append(line[p + 1])
                        }
                        p += 2
                        continue
                    }
                    if (c == '"') break
                    sb.append(c)
                    p++
                }
                value = sb.toString()
                i = p + 1
            } else {
                val e = line.indexOf(',', j).let { if (it < 0) n else it }
                value = line.substring(j, e).trim()
                i = e + 1
            }
            out[key] = value
        }
        return out
    }

    private fun esc(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
