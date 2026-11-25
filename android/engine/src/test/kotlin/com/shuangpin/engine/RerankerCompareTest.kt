package com.shuangpin.engine

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 临时对比：同一个候选池上，8M(6x256) 与 25M(8x448) 的重排效果。
 *
 * 用**我们发布的 Kotlin 实现**跑（不是 Python 参考实现）——
 * 引擎单测里的 Kotlin 口径才是线上真实行为。
 *
 * 模型目录用系统属性给：
 * `-Dshuangpin.rerank.model=D:/tmp/ime-analysis/model-25M`
 */
class RerankerCompareTest {

    private val poolPath = "D:/tmp/ime-analysis/our-pool.jsonl"

    private fun loadFrom(dir: String): Reranker {
        val d = File(dir)
        return Reranker.load(
            modelBytes = d.resolve("lm_step.ort").readBytes(),
            configJson = d.resolve("model.json").readText(),
            vocabJson = d.resolve("vocab.json").readText(),
            threads = 1,
        )
    }

    @Test
    fun `对比两个模型`() {
        val pool = File(poolPath)
        assumeTrue("评估集不在，跳过：$poolPath", pool.exists())
        // 按需触发：`-Dshuangpin.rerank.compare=<模型目录>`
        // 不给就整个跳过——这个用例要跑几十分钟，不能拖慢日常单测。
        val extraDir = System.getProperty("shuangpin.rerank.compare")
        assumeTrue("没给 shuangpin.rerank.compare，跳过模型对比", extraDir != null)
        val cases = pool.readLines().filter { it.isNotBlank() }.map { parseCase(it) }

        // 线上的那份（classpath）
        val loader = DataLoader()
        val shipped = Reranker.load(
            modelBytes = loader.binary("reranker/lm_step.ort"),
            configJson = loader.lines("reranker/model.json").joinToString("\n"),
            vocabJson = loader.lines("reranker/vocab.json").joinToString("\n"),
            threads = 1,
        )
        run(shipped, cases, "线上那份（classpath 6x256）")

        for (dir in listOfNotNull(extraDir)) {
            val d = File(dir)
            if (!d.resolve("lm_step.ort").exists()) {
                println("跳过 $dir（文件不在）")
                continue
            }
            val r = loadFrom(dir)
            run(r, cases, dir)
        }
    }

    private fun run(reranker: Reranker, cases: List<Case>, label: String) {
        var n = 0
        var baseHit = 0
        var modelHit = 0
        val times = ArrayList<Long>()
        for (case in cases) {
            val equal = case.candidates.filter { it.length == case.syllables }
            if (equal.size < 2) continue
            n++
            val t0 = System.nanoTime()
            val ranked = reranker.rank(case.context, equal)
            times.add((System.nanoTime() - t0) / 1000)
            if (equal.first() == case.gold) baseHit++
            if (ranked.first() == case.gold) modelHit++
        }
        val sorted = times.sorted()
        val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)]
        println("MODEL=$label")
        println("  可重排用例 $n / ${cases.size}")
        println("  引擎首选命中 : $baseHit/$n = ${pct(baseHit, n)}%")
        println("  模型重排命中 : $modelHit/$n = ${pct(modelHit, n)}%")
        println("  延迟(微秒)   : 中位 ${sorted[sorted.size / 2]}  p95 $p95  最大 ${sorted.last()}")
        assertTrue("样本太少", n > 100)
    }

    private fun pct(hit: Int, n: Int): String =
        if (n == 0) "0.0" else String.format("%.1f", 100.0 * hit / n)

    private data class Case(
        val id: String,
        val gold: String,
        val context: String,
        val syllables: Int,
        val candidates: List<String>,
    )

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
