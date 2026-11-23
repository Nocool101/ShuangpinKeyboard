package com.shuangpin.engine

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * **纠错候选的端侧重排**评测。
 *
 * ## 问的是什么
 *
 * 纠错候选的初始顺序是**词频**：「防静电」(66) >「很经典」(53) >「毁经典」(27)。
 * 词频只能分高低，分不出「用户此刻想要哪个」——那要看上文：
 * 「绝缘」后面接的是「防静电」，不是「很经典」。
 *
 * 这一轮把纠错候选单独送端侧重排模型（它们天然等长，正好满足模型「候选等长」的要求），
 * 量两件事：
 * 1. **没有上文时**（空上文）：模型自己的偏好比词频准不准？
 * 2. **有上文时**：能不能把纠错候选排对？
 *
 * ## 结论（实测，App 层据此定策略）
 *
 * - **空上文时模型更差**：词频第一命中 67/94 → 重排后 44/94（弄坏 26 条）。
 *   原因是重排模型是**字级 LM**：它算的是「这些字连起来的概率」，
 *   不是「这个词有多常用」。空上文时它就退化成「哪个候选的字更常见」——
 *   「很经典」的字比「防静电」的字常见，于是排到了前面。
 * - **有上文时模型才对**：「她的」→ 思想 ✓、「绝缘」→ 防静电 ✓。
 *
 * 所以 App 层的策略是：**只有上文非空才采纳模型的顺序**，没上文就按词频。
 * 这个测试把那两条都量出来，并且守住「有上文时不能比词频差」。
 */
class TyposRerankEvalTest {

    companion object {
        private lateinit var engine: PinyinEngine
        private lateinit var reranker: Reranker

        @BeforeClass
        @JvmStatic
        fun load() {
            engine = TestData.engine()
            val loader = DataLoader()
            reranker = Reranker.load(
                modelBytes = loader.binary("reranker/lm_step.ort"),
                configJson = loader.lines("reranker/model.json").joinToString("\n"),
                vocabJson = loader.lines("reranker/vocab.json").joinToString("\n"),
            )
        }

        /**
         * **带上文的小样本**：每一行是「错打音节 → 上文 → 期望排第一的纠错候选」。
         *
         * 上文都是**能明显分出高下**的那种（不是勉强凑的），
         * 这样「模型排对」才说明它真的用上了上文。
         */
        private val CONTEXTED = listOf(
            Triple(listOf("hang", "jing", "dian"), "绝缘", "防静电"),
            Triple(listOf("tuo", "san"), "处理得很", "妥善"),
            Triple(listOf("su", "nv"), "下载", "速率"),
            Triple(listOf("mang", "hua"), "连载的", "漫画"),
            Triple(listOf("leng", "ling", "qi"), "冰箱里的", "冷凝器"),
            Triple(listOf("kan", "dao", "li"), "我", "看到你"),
            Triple(listOf("da", "ma", "ni"), "这辆车的", "大马力"),
            Triple(listOf("zhu", "ni", "jun"), "他是", "主力军"),
            Triple(listOf("wei", "si", "ji"), "喝一杯", "威士忌"),
            Triple(listOf("si", "xian"), "她的", "思想"),
            Triple(listOf("san", "hai", "jing"), "《", "山海经"),
            Triple(listOf("qi", "nan", "shi"), "合同的", "期满时"),
            Triple(listOf("quan", "ming", "xin"), "赛事的", "全明星"),
            Triple(listOf("qiong", "zui"), "不舍得", "穷追"),
            Triple(listOf("men", "sui"), "醒来时", "梦碎"),
            Triple(listOf("nin", "xuan"), "参加", "遴选"),
            Triple(listOf("xing", "ha", "lu"), "这起", "性贿赂"),
            Triple(listOf("shu", "shi", "ya"), "价值", "数十亿"),
            Triple(listOf("ha", "jia", "zi"), "他摆", "花架子"),
            Triple(listOf("ben", "zan"), "首页", "本站"),
            Triple(listOf("bu", "nai", "en"), "篮球明星", "布莱恩"),
            Triple(listOf("gang", "zao", "qi"), "烘箱里的", "干燥器"),
            Triple(listOf("ha", "bu", "shang"), "画的", "画布上"),
            Triple(listOf("ji", "zai", "ci"), "他", "将再次"),
            Triple(listOf("ha", "man", "yi"), "她", "很满意"),
            Triple(listOf("wei", "ha"), "这种行为", "违法"),
            Triple(listOf("yin", "xiong"), "他是个", "英雄"),
            Triple(listOf("sheng", "hu"), "比赛的", "胜负"),
            Triple(listOf("chan", "ting"), "去", "餐厅"),
            Triple(listOf("men", "long"), "他是条", "猛龙"),
            Triple(listOf("bi", "cu"), "他们", "彼此"),
            Triple(listOf("ha", "shang"), "明天的", "会上"),
            Triple(listOf("duo", "zuan"), "一夜", "多赚"),
            Triple(listOf("shuan", "zi", "zuo"), "十二月的", "双子座"),
            Triple(listOf("ga", "lao"), "这座建筑很", "古老"),
            Triple(listOf("nin", "da"), "那位是", "琳达"),
            Triple(listOf("ling", "xie"), "我们", "领先"),
            Triple(listOf("she", "ze"), "这块布的", "色泽"),
            Triple(listOf("ling", "nong"), "这对玉佩很", "玲珑"),
            Triple(listOf("shi", "zi", "nin"), "苏州的", "狮子林"),
            Triple(listOf("zeng", "jiu"), "我们必须", "拯救"),
            Triple(listOf("ke", "nai"), "这个", "可买"),
            Triple(listOf("guang", "nu"), "这条", "光路"),
            Triple(listOf("bao", "xiang", "ka"), "银行发的", "保险卡"),
            Triple(listOf("nuo", "ni"), "那个明星", "罗尼"),
            Triple(listOf("bu", "zhi", "ning"), "一个", "不知名"),
            Triple(listOf("di", "wu", "nei"), "这是", "第五类"),
            Triple(listOf("niu", "ne"), "早上喝", "牛奶"),
            Triple(listOf("xi", "nuo"), "在", "西南"),
            Triple(listOf("tu", "kuan"), "合同里的", "条款"),
            Triple(listOf("qin", "dao", "fu"), "游戏里的", "清道夫"),
            Triple(listOf("nuo", "bo"), "拔", "萝卜"),
            Triple(listOf("chan", "ting"), "饭点去", "餐厅"),
            Triple(listOf("xuan", "chuang", "yue"), "这个月的", "宣传月"),
            Triple(listOf("bao", "xiang", "ka"), "车险的", "保险卡"),
        )
    }

    private fun corrections(syllables: List<String>): List<Correction> =
        Typos.corrections(engine.dictionary, engine.table, syllables)

    private data class Case(val syllables: List<String>, val gold: String, val type: String)

    private fun loadCases(): List<Case> =
        DataLoader().lines("typo_cases.txt").mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 3) return@mapNotNull null
            Case(
                syllables = p[0].split(' ').filter { it.isNotEmpty() },
                gold = p[1],
                type = p[2],
            )
        }

    @Test
    fun `空上文时重排模型不如词频`() {
        val cases = loadCases().filter { it.type != "negative" }

        var total = 0
        var baseHit = 0
        var modelHit = 0
        var improved = 0
        var broken = 0
        val times = ArrayList<Long>()

        for (case in cases) {
            val corr = corrections(case.syllables)
            if (corr.size < 2) continue
            total++

            val before = corr.first().word
            val t0 = System.nanoTime()
            val ranked = reranker.rank("", corr.map { it.word })
            times.add((System.nanoTime() - t0) / 1000)

            val after = ranked.firstOrNull() ?: before
            if (before == case.gold) baseHit++
            if (after == case.gold) modelHit++
            if (before != case.gold && after == case.gold) improved++
            if (before == case.gold && after != case.gold) broken++
        }

        val avgUs = if (times.isEmpty()) 0.0 else times.sum().toDouble() / times.size
        println("── 纠错候选重排（空上文）──")
        println(
            "  有两个以上纠错候选的用例 %d 条：词频第一命中 %d，重排后第一命中 %d（救回 %d / 弄坏 %d）".format(
                total, baseHit, modelHit, improved, broken,
            )
        )
        println("  重排耗时：平均 %.0f us，最慢 %d us".format(avgUs, times.maxOrNull() ?: 0))

        // **这条门禁记录的是「当前结论」**：空上文时模型比词频差。
        // 哪天它翻转了（模型变强 / 词库换血），这条会红——
        // 那就说明该重新评估「纠错通道要不要开」了。
        assertTrue(
            "空上文时重排模型不再比词频差（$baseHit → $modelHit）：" +
                "纠错通道的开关要重新评估了",
            modelHit < baseHit,
        )    }

    @Test
    fun `带上文时重排模型仍然不如词频`() {
        var ok = 0
        var beforeOk = 0
        var n = 0
        println("── 纠错候选重排（带上文）──")
        for ((syllables, ctx, expect) in CONTEXTED) {
            val corr = corrections(syllables)
            if (corr.size < 2) continue
            n++
            val before = corr.first().word
            val ranked = reranker.rank(ctx, corr.map { it.word })
            val after = ranked.firstOrNull() ?: before
            if (before == expect) beforeOk++
            if (after == expect) ok++
            if (before != expect || after != expect) {
                println("  上文「$ctx」 ${syllables.joinToString(" ")}：词频第一=$before → 重排第一=$after（期望 $expect）")
            }
        }
        println("  带上文样本 %d 条：词频第一对 %d，重排第一对 %d".format(n, beforeOk, ok))
        assertTrue("带上文样本太少：$n", n >= 40)
        assertTrue(
            "带上文时重排模型不再比词频差（$beforeOk → $ok）：纠错通道的开关要重新评估了",
            ok <= beforeOk,
        )
    }
}
