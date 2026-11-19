package com.shuangpin.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词库加载性能基准。
 *
 * 输入法启动时用户是等不了的：词库有二十 MB，必须确认「小数据同步加载」够快，
 * 「大数据后台加载」不会把主线程卡住。
 *
 * 这里的数字是 JVM 上的基线，安卓 ART 大致同量级。
 */
class DictionaryLoadBenchmark {

    @Test
    fun `测量词库加载耗时与查询速度`() {
        val rt = Runtime.getRuntime()

        // ---- 小数据：主线程同步加载 ----
        var t0 = System.nanoTime()
        val core = DataLoader().loadCore()
        val coreColdMs = (System.nanoTime() - t0) / 1_000_000.0

        t0 = System.nanoTime()
        DataLoader().loadCore()
        val coreWarmMs = (System.nanoTime() - t0) / 1_000_000.0
        println("[bench] 核心数据加载（键位表+音节表）: 冷 ${"%.1f".format(coreColdMs)} ms / 热 ${"%.1f".format(coreWarmMs)} ms")

        // ---- 大数据：后台加载 ----
        System.gc()
        Thread.sleep(200)
        val heapBefore = rt.totalMemory() - rt.freeMemory()
        t0 = System.nanoTime()
        val dictionary = DataLoader().loadDictionary()
        val dictMs = (System.nanoTime() - t0) / 1_000_000.0
        System.gc()
        Thread.sleep(200)
        val heapAfter = rt.totalMemory() - rt.freeMemory()
        val heapMb = (heapAfter - heapBefore) / 1024.0 / 1024.0
        println("[bench] 词库加载（${dictionary.phrases.size} 个拼音串）: ${"%.1f".format(dictMs)} ms，堆占用 ${"%.1f".format(heapMb)} MB")

        // ---- 查询速度 ----
        val keys = listOf("nihao", "zhongguo", "womenshi", "xiexie", "shijian", "gongzuo")
        t0 = System.nanoTime()
        var hits = 0
        repeat(20_000) { i ->
            if (dictionary.phraseCandidates(keys[i % keys.size]).isNotEmpty()) hits++
        }
        val lookupUs = (System.nanoTime() - t0) / 20_000.0 / 1000.0
        println("[bench] 词候选查询: ${"%.2f".format(lookupUs)} μs/次，命中 $hits/20000")

        println("[bench] 词库条目: ${dictionary.phrases.size} 个拼音串，${dictionary.chars.size} 个音节")

        // 冷加载含 JVM 类加载，受机器负载影响大，只做冒烟；
        // 真正要守住的是热加载（输入法进程常驻，第二次以后都走这条路）。
        assertTrue("核心数据冷加载太慢：${"%.1f".format(coreColdMs)} ms", coreColdMs < 1500)
        assertTrue("核心数据热加载太慢：${"%.1f".format(coreWarmMs)} ms", coreWarmMs < 100)
        // 词库是后台加载，但也不能慢到离谱（用户会以为输入法坏了）
        assertTrue("词库加载太慢：${"%.1f".format(dictMs)} ms", dictMs < 3000)
        // 查询必须够快，用户每敲一个键都要查
        assertTrue("词候选查询太慢：${"%.2f".format(lookupUs)} μs", lookupUs < 500)
        // 命中数要正常
        assertTrue("词候选命中率异常：$hits", hits > 19_000)
        // 内存是关键风险：78 万个 key 若用 HashMap 装，光 Map 就是 200MB+，
        // Android 会直接杀掉输入法进程。这里必须守住紧凑存储的收益。
        assertTrue("词库堆占用过大：${"%.1f".format(heapMb)} MB", heapMb < 80)
    }
}
