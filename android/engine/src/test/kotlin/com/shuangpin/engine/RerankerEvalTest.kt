package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 端侧重排模型的评测。
 *
 * 用**现有的评估集**跑两件事：
 * 1. **命中率**：重排后第一候选是不是金标准
 * 2. **一致性**：Kotlin 实现选出的候选与 Python 参考实现
 *    （`neural/choice/score_ort.py`）是否**完全一致**
 *
 * 第 2 条是实现正确性的硬验证——两边的喂数逻辑是各自独立写的，
 * 选出同一个候选才说明移植没错。
 *
 * 评估集是 `:engine:exportEvalPool` 用真引擎导出的候选池
 * （见 `tools/` 与 PROGRESS「端侧模型评估」一节）。
 * 池文件不在时整个用例跳过。
 */
class RerankerEvalTest {

    private val poolPath = "D:/tmp/ime-analysis/our-pool.jsonl"
    private val kotlinPicksPath = "D:/tmp/ime-analysis/kotlin-picks.jsonl"

    private fun loadReranker(): Reranker {
        val loader = DataLoader()
        return Reranker.load(
            modelBytes = loader.binary("reranker/lm_step.ort"),
            configJson = loader.lines("reranker/model.json").joinToString("\n"),
            vocabJson = loader.lines("reranker/vocab.json").joinToString("\n"),
        )
    }

    @Test
    fun `重排命中率与延迟`() {
        val pool = File(poolPath)
        assumeTrue("评估集不在，跳过：$poolPath", pool.exists())

        val reranker = loadReranker()
        val cases = pool.readLines().filter { it.isNotBlank() }.map { parseCase(it) }
        assumeTrue("评估集不该是空的", cases.isNotEmpty())

        var n = 0
        var baseHit = 0
        var modelHit = 0
        var multi = 0
        var multiBaseHit = 0
        var multiModelHit = 0
        val times = ArrayList<Long>()
        val picks = StringBuilder()

        for (case in cases) {
            val equal = case.candidates.filter { it.length == case.syllables }
            if (equal.size < 2) continue
            multi++
            n++

            val t0 = System.nanoTime()
            val ranked = reranker.rank(case.context, equal)
            times.add((System.nanoTime() - t0) / 1000)

            val pick = ranked.first()
            if (equal.first() == case.gold) baseHit++
            if (pick == case.gold) modelHit++
            if (equal.first() == case.gold) multiBaseHit++
            if (pick == case.gold) multiModelHit++

            picks.append("{\"id\":\"").append(case.id).append("\",\"pick\":\"").append(pick).append("\"}\n")
        }

        File(kotlinPicksPath).writeText(picks.toString(), Charsets.UTF_8)

        val sorted = times.sorted()
        val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)]
        println("重排评测：可重排用例 $n 条（总 ${cases.size} 条）")
        println("  引擎首选命中 : $baseHit/$n = ${pct(baseHit, n)}%")
        println("  模型重排命中 : $modelHit/$n = ${pct(modelHit, n)}%")
        println("  延迟(微秒)   : 中位 ${sorted[sorted.size / 2]}  p95 $p95  最大 ${sorted.last()}")
        println("  选中的候选已写入 $kotlinPicksPath")

        assertTrue("应该有多条等长候选可重排", n > 100)
        assertTrue(
            "模型重排不该比引擎首选更差：模型 ${pct(modelHit, n)}% vs 引擎 ${pct(baseHit, n)}%",
            modelHit >= baseHit,
        )
    }

    @Test
    fun `与 Python 参考实现选出同一个候选`() {
        val pool = File(poolPath)
        val pythonPicks = File("D:/tmp/ime-analysis/python-picks.jsonl")
        assumeTrue("评估集不在，跳过", pool.exists())
        assumeTrue("Python 参考结果不在，跳过：$pythonPicks.path", pythonPicks.exists())

        val reranker = loadReranker()
        val cases = pool.readLines().filter { it.isNotBlank() }.map { parseCase(it) }
        val reference = pythonPicks.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            val id = field(line, "id")
            val pick = field(line, "pick")
            if (id != null && pick != null) id to pick else null
        }.toMap()

        var compared = 0
        var mismatch = 0
        for (case in cases) {
            val expected = reference[case.id] ?: continue
            val equal = case.candidates.filter { it.length == case.syllables }
            if (equal.size < 2) continue
            val pick = reranker.rank(case.context, equal).first()
            compared++
            if (pick != expected) {
                mismatch++
                if (mismatch <= 5) {
                    println("不一致：${case.id}  Kotlin=$pick  Python=$expected")
                }
            }
        }
        println("与 Python 参考实现对比：$compared 条，不一致 $mismatch 条")
        // 允许极少量不一致：Java 的 float 运算次序与 numpy 略有差异，
        // 分数几乎相等时（实测 3588 条里有 1 条）谁排第一会翻个个儿。
        assertTrue(
            "与 Python 参考实现的不一致不该超过 0.1%，实际 $mismatch/$compared",
            mismatch * 1000 <= compared,
        )
        assertTrue("对比样本不该太少", compared > 100)
    }

    /** 取一个 JSON 字符串字段的值（够用即可，不引 JSON 库）。 */
    private fun field(line: String, key: String): String? {
        val k = line.indexOf("\"$key\"")
        if (k < 0) return null
        var i = line.indexOf(':', k)
        if (i < 0) return null
        i++
        // 跳过冒号与空白，找开引号
        while (i < line.length && line[i] != '"') {
            if (line[i] == '}' || line[i] == ']') return null
            i++
        }
        if (i >= line.length) return null
        i++
        val sb = StringBuilder()
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                sb.append(line[i + 1])
                i += 2
                continue
            }
            if (c == '"') break
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------

    private fun pct(hit: Int, n: Int): String =
        if (n == 0) "0.0" else String.format("%.1f", 100.0 * hit / n)

    private data class Case(
        val id: String,
        val gold: String,
        val context: String,
        val syllables: Int,
        val candidates: List<String>,
    )

    /** 极简解析：只需要 id / gold / context / syllables / candidates。 */
    private fun parseCase(line: String): Case {
        val map = HashMap<String, String>()
        var i = 0
        val n = line.length
        while (i < n) {
            val k = line.indexOf('"', i)
            if (k < 0) break
            val kEnd = line.indexOf('"', k + 1)
            if (kEnd < 0) break
            val key = line.substring(k + 1, kEnd)
            var j = kEnd + 1
            while (j < n && (line[j] == ' ' || line[j] == ':')) j++
            if (j >= n) break
            val value: String
            if (line[j] == '"') {
                val sb = StringBuilder()
                var p = j + 1
                while (p < n && line[p] != '"') {
                    if (line[p] == '\\' && p + 1 < n) {
                        sb.append(line[p + 1])
                        p += 2
                        continue
                    }
                    sb.append(line[p])
                    p++
                }
                value = sb.toString()
                i = p + 1
            } else {
                val e = line.indexOf(',', j).let { if (it < 0) n else it }
                value = line.substring(j, e).trim()
                i = e + 1
            }
            map[key] = value
        }
        // candidates 是一个字符串数组
        val raw = line.substringAfter("\"candidates\":[")
        val list = raw.substringBefore("]").split(",")
            .map { it.trim().trim('"').replace("\\\"", "\"").replace("\\\\", "\\") }
            .filter { it.isNotEmpty() }
        return Case(
            id = map["id"] ?: "",
            gold = map["gold"] ?: "",
            context = map["context"] ?: "",
            syllables = map["syllables"]?.toIntOrNull() ?: 0,
            candidates = list,
        )
    }
}
