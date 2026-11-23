package com.shuangpin.engine

/**
 * 第二阶段某个键位的解析结果。
 *
 * @param fin 显示用韵母；歧义未消解时形如 "ing/uai"
 * @param finals 该键位上的全部候选韵母（按音节表顺序）
 * @param syllable 该键位唯一确定的音节；无法唯一确定时为 null
 */
data class FinalHit(
    val fin: String,
    val finals: List<String>,
    val syllable: String?,
    /**
     * 声母那一部分的**拼音**（"xian" 的声母部分是 "x"；零声母是空串）。
     *
     * 用来判断「把这个音节截短之后是不是只剩声母」——
     * 是的话就该退回「半截音节」状态，用户接着按韵母键就能补上。
     */
    val initialPinyin: String = "",
    /**
     * 声母的**键位**（小鹤：zh→v、ch→i、sh→u）。零声母为 null。
     *
     * 退回「半截音节」时用它当 firstKey。
     */
    val initialKey: String? = null,
) {
    val isAmbiguous: Boolean get() = finals.size > 1
}

/**
 * 键面显示内容。
 *
 * @param big 主标签（声母位显示声母，韵母位显示韵母）
 * @param sub 副标签（声母位显示该键承载的常用韵母）
 * @param badge 键位字母角标（永远显示，用户要靠它找键）
 * @param dead 是否是当前状态下无效的键（灰掉）
 * @param accent 是否高亮（韵母位上可按的键）
 */
data class KeyFace(
    val big: String,
    val sub: String?,
    val badge: String?,
    val dead: Boolean,
    val accent: Boolean,
)

/**
 * 键面标签的计算。
 *
 * 放在引擎层而不是 View 里，有两个原因：
 * 1. 状态一变（按了声母 / 韵母），键面必须立刻换——这是纯逻辑，可以单测。
 *    之前标签在 layout 阶段算死存进 Key，结果按了声母键面根本不换：
 *    「按了声母还在韵母界面」「w 之后 o 键显示零」都是这个 bug。
 * 2. View 只负责画，不掺和规则。
 */
object KeyboardFaces {

    /** 算出某个键在当前状态下的显示内容。 */
    fun forKey(
        scheme: Scheme,
        stage: InputStage,
        finals: Map<String, FinalHit>,
        key: String,
    ): KeyFace {
        if (stage == InputStage.FINAL) {
            val hit = finals[key]
            if (hit != null) {
                // 韵母位：主标签就是这个键在当前声母下的**韵母**（用户要读的就是它），
                // 键位字母放左上角角标——既不喧宾夺主，又能一眼找到要按哪个键。
                return KeyFace(
                    big = Display.finalLabel(hit.fin),
                    sub = null,
                    badge = key,
                    dead = false,
                    accent = true,
                )
            }
            // 无效键：整体变灰，主标签退成通用韵母提示
            return KeyFace(
                big = Display.initialSlotLabel(scheme, key),
                sub = null,
                badge = key,
                dead = true,
                accent = false,
            )
        }

        // 声母位：主标签是声母（对绝大多数键来说就等于字母本身）
        val initial = scheme.initialByKey[key]
        val big = when {
            ZERO_KEYS.contains(key) -> "零"
            initial == "zh" || initial == "ch" || initial == "sh" -> initial
            initial != null -> initial
            else -> key
        }
        val sub = when {
            ZERO_KEYS.contains(key) -> null
            initial == "y" || initial == "w" -> "整体认读"
            else -> Display.initialSlotLabel(scheme, key)
        }
        // 角标只在主标签不等于字母时才需要（zh/ch/sh/零/整体认读 那几个键）
        val badge = if (big != key) key else null
        return KeyFace(big = big, sub = sub, badge = badge, dead = false, accent = false)
    }
}

/**
 * 长词扩展（联想候选）。
 *
 * 用户输入「nihao」时，词库里除了「你好」，还有「你好吗」「你好啊」「你好毒」……
 * 这个对象负责把它们挖出来，作为**扩展候选**交给候选栏。
 *
 * ## 为什么必须做「词级」过滤
 *
 * 词库的键是**拼音串**，所以「以拼音为前缀」不等于「以词为前缀」：
 * - 「nihao」会命中「拟好草稿」「你号被盗了」——拼音撞了，词没撞
 * - 「shi」能命中 12791 条，里面有「诗艾娜」「世阿弥」「食案方丈」这种纯巧合
 *
 * 所以扩展词必须满足：**以该拼音串的某个基准词开头**（词级），而不是拼音撞了就收。
 *
 * ## 两条算法，自适应切换
 *
 * - **范围扫描**：扫「以 pinyin 为前缀」的所有行。能覆盖 +1~+3 音节的自然扩展
 *   （「nihao」→「你好吗」，「shijie」→「世界上」），代价 O(区间大小)
 * - **枚举下一个音节**：412 个音节各做一次精确二分，只覆盖 +1 音节，
 *   但代价极低（实测 200~520 μs）
 *
 * 区间小（几千行以内）走范围扫描；区间大（单音节前缀能上万行）走枚举兜底。
 */
object Extensions {

    /**
     * 分数下限。
     *
     * rime-ice 里 **27 分是「没有人工词频」的默认档**——全库 34 万条（43.6%）都是它。
     * 这些条目不是错词，但排序上永远沉底，塞进联想只会占位置。
     */
    const val MIN_SCORE = 27

    /** 范围扫描的最大行数，超过就改走枚举。 */
    const val MAX_SCAN = 4000

    /** 扩展词最多比基准词长几个字。 */
    const val MAX_EXTRA_CHARS = 3

    /** 默认返回多少个扩展。 */
    const val DEFAULT_LIMIT = 12

    /**
     * 找出 pinyin 的长词扩展。
     *
     * @param pinyin 已确定的拼音串（整句的已输入部分）
     * @return 按词频降序的扩展词，最多 limit 个
     */
    fun find(
        dictionary: Dictionary,
        syllables: List<String>,
        pinyin: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<WordCand> {
        if (pinyin.isEmpty() || limit <= 0) return emptyList()

        // 基准词：这个拼音串在词库里的精确候选。
        // 词和字都要算——单音节（"hao"）在词表里没有词条，只有字，
        // 少了字表就一个扩展都出不来。
        val baseWords = buildList {
            addAll(dictionary.phraseCandidates(pinyin))
            addAll(dictionary.charCandidates(pinyin).map { it.text })
        }
        if (baseWords.isEmpty()) return emptyList()

        val range = dictionary.phrases.prefixRange(pinyin)
        if (range.isEmpty()) return emptyList()

        val out = ArrayList<WordCand>()

        if (range.count() <= MAX_SCAN) {
            // 区间不大，直接扫：能覆盖 +1~+3 音节的扩展。
            for (idx in range) {
                val c = dictionary.phrases.firstCandidateAt(idx) ?: continue
                if (accept(c, baseWords)) out.add(c)
            }
        } else {
            // 区间太大（单音节前缀能上万行），改走枚举：
            // 只取「正好多一个音节」的扩展，每个可能做一次精确二分。
            for (syl in syllables) {
                for (c in dictionary.phrases.candidates(pinyin + syl)) {
                    if (accept(c, baseWords)) out.add(c)
                }
            }
        }

        // 按词频降序。同分保持原有顺序（排序是稳定的）。
        out.sortByDescending { it.score }
        return out.take(limit)
    }

    /** 扩展词的准入条件：分数够、以某个基准词开头、且不比基准词长太多。 */
    private fun accept(cand: WordCand, baseWords: List<String>): Boolean {
        if (cand.score <= MIN_SCORE) return false
        val w = cand.text
        for (b in baseWords) {
            if (b.length >= w.length) continue          // 不能就是基准词本身
            if (!w.startsWith(b)) continue              // 必须是词级的前缀，不是拼音撞了
            if (w.length - b.length > MAX_EXTRA_CHARS) continue
            return true
        }
        return false
    }
}

/**
 * 候选的类别。
 *
 * 类别决定候选栏里的字号与颜色，也决定点选时的动作：
 * 词、整句、扩展都是「整条上屏」，单字是「取前面的音节 + 这个字」。
 */
enum class CandidateKind {
    /** 整句候选（分词后拼起来的那一整条） */
    SENTENCE,

    /** 整句的备选切分：同样是等长的整句，是重排模型能发挥的地方 */
    SENTENCE_ALT,

    /** 词候选：整串命中、分出来的词 */
    WORD,

    /** 长词扩展（联想）：词库里有、但没被分词选中的更长词 */
    EXTENSION,

    /**
     * 自动纠错候选。
     *
     * 拼音打错了（zh/z、ch/c、sh/s 搞混之类），按近音变体在词库里
     * 找到了一个**明显更常用**的词——它排在候选栏最前面，
     * 按空格就上屏它（这就是「自动」）。
     */
    CORRECTION,

    /**
     * 云端下一词建议。
     *
     * 不是引擎算出来的——是云端模型根据上文预测的「接下来该打什么词」，
     * 所以它不属于引擎的候选装配，只在 App 层插进候选栏。
     */
    NEXT_WORD,

    /**
     * 云端给的**整句纠错建议**。
     *
     * 不是候选词，是「你刚上屏的这句有个错别字，改成这样」的完整句子。
     * 做成候选栏里的一个 chip（点一下才改），
     * 免得大模型擅自改用户的原话。
     *
     * 和上面的 [CORRECTION] 是两回事：
     * - `CORRECTION`：**本地**拼音容错（用户这串拼音打错了，引擎换近音再查词库）
     * - `CORRECTION_TEXT`：**云端**整句纠错（已上屏的中文里有错别字，大模型改）
     */
    CORRECTION_TEXT,

    /** 单字候选 */
    CHAR,

    /**
     * **用户自学习词**。
     *
     * 用户自己打出来过的词——词库里根本没有的也行
     * （游戏专有名词「炼气士」、人名、缩写……）。
     *
     * 打过一次之后再打同样的拼音，它就排在候选栏**第一位**。
     * 见 [UserDict]。
     */
    USER,
}

/**
 * 候选栏里的一项。
 *
 * @param promoted 是否被云端「按上文消歧」提到了前面。
 *                 它只是个标记，不改变候选本身的类别——
 *                 被提升的词还是词、还是整句，只是排序变了、样式上给个提示。
 * @param pinyin 单字候选属于**哪个音节**（别的候选是 null）。
 *               候选栏在字上面标出这个拼音，用户才知道这个字是哪来的，
 *               点它就能单独改那个音节。
 */
data class Candidate(
    val text: String,
    val kind: CandidateKind,
    val promoted: Boolean = false,
    val pinyin: String? = null,
    /**
     * 是不是**整串精确命中**——这一串拼音本身就是词库里的一个词
     * （"xianrenfuwoding" → 「仙人抚我顶」）。
     *
     * 这是**最强的信号**：比任何分词结果都强，
     * 所以它排第一，而且**不参与端侧重排**——
     * 端侧模型只见过常用词，碰到生僻的整词反而会把它排下去
     * （用户明明打对了 "xianrenfuwoding"，却被排成「现任服我定」）。
     */
    val exact: Boolean = false,
)

/**
 * 候选栏的内容装配。
 *
 * 放在引擎层而不是 App 层，是因为**它不只是一段 UI 排版**：
 * 候选的顺序、每类各取多少、去重规则，都是「这个输入法给出什么候选」的
 * 定义本身。端侧模型评估要拿的就是这份定义——
 * 装配逻辑放在 App 里，评估用的候选池就和用户看到的对不上了。
 */
object Candidates {

    /** 词候选取几个（整串命中与分词各一批）。 */
    const val MAX_PHRASE = 5

    /** 长词扩展取几个。 */
    const val MAX_EXTENSION = 12

    /**
     * 单字候选取几个。
     *
     * 实测：单字占了整个候选池的 **86%**（中位 27 个，最大 146 个）——
     * 因为它是最后一个音节的**全部**候选字，尾部全是生僻字。
     * 不限流的话候选栏会有中位 34 个候选（9 页），没人愿意翻。
     * 限到 12 个之后中位降到 16 个（4 页），而常用的字本来就在前面。
     */
    /**
     * 单字候选最多给多少个。
     *
     * 24 是因为现在**每个音节**都要给字（用户要能选到前面的音节），
     * 两个音节各 12 个正好；音节更多时每个音节分到 4~8 个。
     */
    const val MAX_CHAR = 24

    /**
     * 整句的备选切分给几个。
     *
     * 每条备选都是**等长**整句（一个音节一个字），所以它们同时是：
     * - 用户的「另一种切法」选择
     * - 重排模型的候选池（等长候选越多，模型越有东西可排）
     */
    const val MAX_SENTENCE_ALT = 5

    /**
     * **整串精确命中的词频分低于它，纠错候选就顶到前面。**
     *
     * 道理：用户打错的那串拼音，在词库里常常**也有命中**——
     * 「智慧」打成「zihui」，词库里查得到「自会」（55 分）。
     * 那不是用户想打的，可它是「整串精确命中」，排在前面就把纠错压住了，
     * 于是「自动纠错」并不自动（用户得滑一下才生效）。
     *
     * 但用户**打对了**的时候，整串命中往往是个常用词（分高），
     * 这时候不该被纠错候选顶掉。
     *
     * 所以用词频分当「这个精确命中有多可信」的代理：
     * 分低 = 冷门词 = 更可能是错打出来的 → 让纠错顶上去；
     * 分高 = 常用词 = 更可能是用户故意打的 → 精确命中照旧在前。
     *
     * **阈值 50**：用户反馈「自会」(55 分) 这类还是压着纠错，要划一下才生效。
     * 提到 50 之后 55 分的「自会」照旧在前（≥50），
     * 但 40~49 分的冷门词会让位给纠错候选。
     *
     * 代价（实测）：正确输入里被纠错顶掉的从 0% 涨到约 2%——
     * 也就是每 50 次「打对了」会有 1 次上屏的是近音词而不是原词。
     * 换来的好处是错打时空格命中 +1.5pp。
     */
    const val HOIST_MAX_SCORE = 50

    /**
     * 装配候选栏内容。
     *
     * 顺序：**用户自学习词 →（条件）自动纠错 → 整串命中 → 整句 → 备选整句 → 分出来的词 → 长词扩展 → 单字**。
     *
     * 用户自学习词排最前：那是用户**明确打出来过**的东西，
     * 没有任何候选比它更贴近用户此刻的意图。
     *
     * 自动纠错**可能**插到整串命中前面——条件见 [HOIST_MAX_SCORE]。
     *
     * 扩展排在单字前面：它比单字精确得多，单字是最不靠谱的兜底。
     *
     * @param includeCorrections 是否带上「自动纠错」候选。
     *        **默认关**——端侧模型评估用的候选池必须是固定的，
     *        纠错候选会改变池子的构成，混进去基线就没法比了。
     *        App 端显式打开。
     * @param userDict 用户自学习词库。默认取引擎上挂的那个（评估端是 null）。
     */
    fun build(
        engine: PinyinEngine,
        session: InputSession,
        maxPhrase: Int = MAX_PHRASE,
        maxExtension: Int = MAX_EXTENSION,
        maxChar: Int = MAX_CHAR,
        includeCorrections: Boolean = false,
        userDict: UserDict? = engine.userDict,
    ): List<Candidate> {
        val out = ArrayList<Candidate>()
        // 去重：整句、词、扩展、单字四层经常重叠
        // （比如整句就是"你"，字表第一位也是"你"）
        val seen = HashSet<String>()

        // ⓪ 用户自学习词：用户打过的词排最前。
        // 词库里没有的词（「炼气士」）也能进候选栏——这正是自学习词库的意义。
        if (userDict != null) {
            for (entry in userDict.lookup(session.pinyin)) {
                if (entry.word.isEmpty()) continue
                if (seen.add(entry.word)) out += Candidate(entry.word, CandidateKind.USER)
            }
        }

        // ⓪b **整串精确命中**：这一串拼音本身就是词库里的一个词
        //    （"xianrenfuwoding" → 「仙人抚我顶」）
        //
        //    它是很强的信号——比任何分词结果都强。
        //    引擎自己的 commitTop() 也是这么选的（整串命中优先于分词）。
        //
        //    **但它可能就是「错打出来的冷门词」**：
        //    「智慧」打成「zihui」，词库里查得到「自会」——
        //    那不是用户想打的，可它是「整串精确命中」，
        //    排在纠错前面就把纠错压住了（空格上屏的是第一条）。
        //    所以纠错候选可以顶到它前面——条件见 [HOIST_MAX_SCORE]。
        //
        //    一次查拿齐文本和分数（词库是 @Volatile 的，中间换库就地对不上了）。
        val dictionary = engine.dictionary
        val exactCand = dictionary.phrases.top(session.pinyin)
        val exactText = exactCand?.text
        val exactScore = exactCand?.score ?: 0

        // ① 自动纠错：拼音打错了？把更像的词提上来。
        // 用户按空格就上屏它，这就是「自动」。
        val corrections = if (includeCorrections) {
            val syllables = session.pending.map { it.syllable ?: "" }
            Typos.corrections(dictionary, engine.table, syllables)
        } else {
            emptyList()
        }

        // 精确命中先「占位」去重集合：同一个词既是精确命中又是纠错候选时，
        // 保留「精确命中」这个身份（它的标记更准）。
        val exactTaken = exactText != null && seen.add(exactText)

        // 纠错候选也占位（去重）
        val correctionItems = ArrayList<Candidate>(corrections.size)
        for (corr in corrections) {
            if (seen.add(corr.word)) {
                correctionItems += Candidate(corr.word, CandidateKind.CORRECTION)
            }
        }

        // **条件置顶**：纠错候选压过整串命中，当且仅当
        //   - 整串命中不存在（打错成了词库里根本没有的东西），或者
        //   - 整串命中的词频分低于 HOIST_MAX_SCORE（是个冷门词，更可能是错打出来的）
        val hoistCorrections = correctionItems.isNotEmpty() &&
            (exactText == null || exactScore < HOIST_MAX_SCORE)

        if (hoistCorrections) out += correctionItems
        if (exactTaken && exactText != null) {
            out += Candidate(exactText, CandidateKind.SENTENCE, exact = true)
        }
        if (!hoistCorrections) out += correctionItems

        // ② 整句候选（分词后拼起来）
        val sentence = session.sentence
        if (sentence.isNotEmpty() && seen.add(sentence)) {
            out += Candidate(sentence, CandidateKind.SENTENCE)
        }

        // ③ 整句的备选切分（等长候选）——重排模型能发挥的地方
        for (alt in session.alternativeSentences) {
            if (seen.add(alt)) out += Candidate(alt, CandidateKind.SENTENCE_ALT)
        }

        // ④ 整串在词库里的其它命中
        for (word in session.phraseCandidates.take(maxPhrase)) {
            if (seen.add(word)) out += Candidate(word, CandidateKind.WORD)
        }

        // ⑤ 分出来的各个词
        for (word in session.segmentWords.take(maxPhrase)) {
            if (seen.add(word)) out += Candidate(word, CandidateKind.WORD)
        }

        // ⑥ 长词扩展（联想）：「你好」之外再给「你好吗」「你好啊」
        for (cand in engine.extensions(session.pinyin, maxExtension)) {
            if (seen.add(cand.text)) out += Candidate(cand.text, CandidateKind.EXTENSION)
        }

        // ⑦ 单字（限流：候选字有一大堆生僻字，全放进来会把候选栏撑爆）
        //
        // **每个音节的字都给**，不只是最后一个音节——
        // 用户反馈「多个字或者句子，右划选词区要能从前选择」：
        // 原来只给最后一个音节的字，前面那些音节根本改不了。
        // 最后一个音节的字排前面（最常要改的就是它），前面的音节往后排，
        // 往右划就能选到。每个字都带着**自己那个音节的拼音**（角标上显示），
        // 点角标就能单独改那个音节。
        var charCount = 0
        val charSyllables = session.pending.map { it.syllable ?: "" }.reversed()
        // 每个音节至少 4 个字（不然多音节时一个都挑不到），再多就按总数平分
        val perSyllable = maxOf(4, maxChar / charSyllables.size.coerceAtLeast(1))
        for (syl in charSyllables) {
            if (syl.isEmpty()) continue
            var taken = 0
            for (s in engine.candidateCharList(syl)) {
                if (charCount >= maxChar || taken >= perSyllable) break
                if (!seen.add(s)) continue
                out += Candidate(s, CandidateKind.CHAR, pinyin = syl)
                charCount++
                taken++
            }
        }
        return out
    }

    /**
     * 把「优先词」提到最前面。
     *
     * 云端按上文消歧之后会给出一个优先顺序，这里把它套到候选上。
     *
     * 两条规矩：
     * 1. **只在候选池里的词算数**——云端要是给出了池子外的词，
     *    说明它跑偏了（或者引擎不支持这个词），直接丢掉，
     *    绝不能把一个用户按不出来的词塞进候选栏。
     * 2. 没被点名的候选保持原顺序（稳定排序）。
     *
     * @param preferred 优先词，按可能性从高到低
     */
    fun promote(preferred: List<String>, items: List<Candidate>): List<Candidate> {
        if (preferred.isEmpty() || items.isEmpty()) return items
        val rank = HashMap<String, Int>()
        for ((i, text) in preferred.withIndex()) {
            if (text.isNotEmpty() && !rank.containsKey(text)) rank[text] = i
        }
        if (rank.isEmpty()) return items
        val hit = items.filter { rank.containsKey(it.text) }
        if (hit.isEmpty()) return items
        val rest = items.filter { !rank.containsKey(it.text) }
        // 命中的按云端给的顺序排，没命中的保持原顺序接在后面
        val promoted = hit.sortedBy { rank[it.text] ?: Int.MAX_VALUE }
            .map { it.copy(promoted = true) }
        return promoted + rest
    }
}

/**
 * 双拼引擎。
 *
 * 核心职责：**在第二阶段就把歧义解掉**。
 *
 * 普通双拼里 9 个键（k/s/x/l/o/v/y/t/r）各自对应两个韵母，用户按下去是盲赌。
 * 这里利用「声母一旦确定，合法韵母集合也随之确定」这一事实，
 * 只保留能组成合法音节的那个韵母——用户按下的键永远是对的。
 */
class PinyinEngine(
    val scheme: Scheme,
    val table: SyllableTable,
    initialDictionary: Dictionary = Dictionary(emptyMap(), PhraseStore.EMPTY),
) {
    /**
     * 词典。
     *
     * 词库有上百万条，手机上必须放后台线程加载、加载完再整体替换，
     * 所以这里用 volatile 而不是构造参数。
     */
    @Volatile
    var dictionary: Dictionary = initialDictionary

    /**
     * **用户自学习词库**。
     *
     * 挂着它，候选装配就会把用户打过的词提到最前面（见 [Candidates.build]）。
     * 评估端不挂（null），候选池才是固定的，基线才可比。
     */
    @Volatile
    var userDict: UserDict? = null

    /**
     * 给定第一键，算出所有合法的第二键。
     *
     * @return 第二键 -> 解析结果。键的顺序即音节表顺序，天然按常用度排列。
     */
    fun resolveFinals(firstKey: String): Map<String, FinalHit> {
        val out = LinkedHashMap<String, FinalHit>()

        // 零声母走自己的二段表
        if (ZERO_KEYS.contains(firstKey)) {
            scheme.zeroStage[firstKey]?.forEach { (secondKey, entry) ->
                out[secondKey] = FinalHit(
                    fin = entry.fin,
                    finals = listOf(entry.fin),
                    syllable = entry.syllable,
                    // 零声母不贡献拼音（「ai」整体就是韵母「ai」）
                    initialPinyin = "",
                    initialKey = null,
                )
            }
            return out
        }

        val initial = scheme.initialByKey[firstKey] ?: return out
        val finals = table.finalsByInitial[initial] ?: return out

        // 按键位分组：同一个键位上的多个韵母在这里被唯一化
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (fin in finals) {
            val key = scheme.finals[fin] ?: continue
            groups.getOrPut(key) { mutableListOf() }.add(fin)
        }

        for ((key, list) in groups) {
            out[key] = FinalHit(
                fin = list.joinToString("/"),
                finals = list,
                // 歧义已由音节合法性消解，取首个（合法）韵母对应的音节
                syllable = table.byInitialFinal["$initial|${list[0]}"],
                initialPinyin = initialPinyinOf(initial),
                initialKey = firstKey,
            )
        }
        return out
    }

    /**
     * 声母那一部分的拼音。
     *
     * 「zh/ch/sh」是两个字母，其余是一个字母——声母的拼音就是它自己。
     */
    private fun initialPinyinOf(initial: String): String = initial

    /**
     * 全拼 -> 双拼编码。
     *
     * @return 两键编码；无法编码时返回空串。
     */
    fun encode(syllable: String): String {
        val parsed = table.parse(syllable)
        val initial = parsed.initial
            ?: return scheme.zeroInitialCodes[parsed.final] ?: ""

        val initialKey = scheme.initials[initial] ?: initial
        val finalKey = scheme.finals[parsed.final] ?: return ""
        return initialKey + finalKey
    }

    /**
     * 双拼编码 -> 全拼音节。
     *
     * 这是「编码可逆」的保证：任何能打出来的两键码都能反解回音节。
     */
    fun decode(code: String): String? {
        if (code.length != 2) return null
        val hit = resolveFinals(code[0].toString())[code[1].toString()] ?: return null
        return hit.syllable
    }

    /** 音节的候选字（按频率降序）。 */
    fun candidateChars(syllable: String): String = dictionary.charsOf(syllable)

    /**
     * 音节的候选字，**一个汉字一个元素**。
     *
     * 要逐字处理（候选栏、选字面板）就用这个：
     * [candidateChars] 返回的是连写字符串，按 UTF-16 码元切会把
     * 扩展区的字（𫘪 这种）劈成两个乱码。
     */
    fun candidateCharList(syllable: String): List<String> = dictionary.charList(syllable)

    /** 拼音串对应的词（最高频的那个）。 */
    fun phraseFor(pinyin: String): String? = dictionary.phraseOf(pinyin)

    /**
     * 拼音串对应的最高频词的**分数**（没有命中就是 0）。
     *
     * 分数 = `round(log2(权重)*4)`，越大越常用。
     * 候选栏用它判断「这个精确命中有多可信」（见 [Candidates.HOIST_MAX_SCORE]）。
     */
    fun phraseScoreFor(pinyin: String): Int = dictionary.phrases.top(pinyin)?.score ?: 0

    /** 拼音串对应的全部候选词，按词频降序。 */
    fun phraseCandidates(pinyin: String): List<String> = dictionary.phraseCandidates(pinyin)

    /**
     * 长词扩展（联想候选）。
     *
     * 输入「nihao」时，除了「你好」还能挖出「你好吗」「你好啊」「你好毒」……
     */
    fun extensions(pinyin: String, limit: Int = Extensions.DEFAULT_LIMIT): List<WordCand> =
        Extensions.find(dictionary, table.syllables, pinyin, limit)

    /**
     * 把音节序列切成词——「整句」能力的基础。
     *
     * 输入法的整句本质上就是两件事：把用户敲的音节切成词，再把词拼成句子。
     * 这里用经典的 DP：
     * - 候选来自词库（拼音串 -> 词），单音节且词库没有时回退到最高频的字
     * - 打分 = 词频分数 + 长词加分 - 分词罚分
     *
     * 纯 unigram 打分有天然局限（分不出「会回去」和「会回去」的上下文差别），
     * 但实测集上准确率已经 ~87%。要再往上得接 n-gram / LSTM 整句模型。
     */
    fun segmentWords(syllables: List<String>): List<String> =
        segmentPaths(syllables, 1).firstOrNull()?.words ?: emptyList()

    /**
     * 分词结果，**带上每个词覆盖的音节**。
     *
     * 返回 `(拼音切片, 词)`：拼音切片是这些音节拼起来的拼音串。
     *
     * 用户自学习词库靠它把「我打出来的这一句」拆成一个个词去记：
     * 「我想吃饭」记成 `woxiang→我想` + `chifan→吃饭`，
     * 下次只打「chifan」就能直接出「吃饭」。
     *
     * 词长和音节数对不上的（词库里有极少数这种怪词）会被跳过——
     * 对不上就不知道这个词对应哪几个音节，记了也是错的。
     */
    fun segmentUnits(syllables: List<String>): List<Pair<String, String>> {
        val path = segmentPaths(syllables, 1).firstOrNull() ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        var idx = 0
        for (i in path.words.indices) {
            val word = path.words[i]
            val count = path.counts.getOrNull(i) ?: break
            val end = (idx + count).coerceAtMost(syllables.size)
            if (word.length == count && end > idx) {
                out += syllables.subList(idx, end).joinToString("") to word
            }
            idx = end
            if (idx >= syllables.size) break
        }
        return out
    }

    /**
     * 整句的 N-best 切分。
     *
     * [segmentWords] 只给出一条最优切分，于是**等长候选几乎总是只有一条**——
     * 而这正是重排模型发挥不了的原因（实测：每条用例的等长候选中位只有 1 个，
     * 只有 24.4% 的用例有 2 个以上，详见 PROGRESS「端侧模型评估」）。
     *
     * 这里给出前 K 条不同切分。因为**一个音节对应一个字**，每条切分出来的整句
     * 长度都等于音节数，所以它们天然是**等长候选**——既能当用户的备选，
     * 也能交给重排模型排序。
     */
    fun segmentSentences(syllables: List<String>, limit: Int = Candidates.MAX_SENTENCE_ALT): List<String> {
        if (limit <= 0) return emptyList()
        // 多取一些：不同的切法可能拼出同一句话（「你」+「好」与「你好」），
        // 去重之后还得能凑够 limit 条
        val out = LinkedHashSet<String>()
        for (path in segmentPaths(syllables, limit * 3)) {
            out.add(path.sentence)
            if (out.size >= limit) break
        }
        return out.toList()
    }

    /** 一条切分路径。 */
    private data class Path(val score: Int, val words: List<String>, val counts: List<Int>) {
        val sentence: String get() = words.joinToString("")
    }

    /**
     * DAG 上的 K-best DP。
     *
     * 边权可加，所以**前驱的 K-best 一定够用**：前 K 名之外的前缀，
     * 配同一条后缀只会得到更差的完整路径，不可能挤进前 K。
     *
     * 同分时保持到达顺序（稳定排序），与原来单最优 DP 的并列处理一致——
     * 所以 [segmentWords] 的结果不变。
     */
    private fun segmentPaths(syllables: List<String>, limit: Int): List<Path> {
        val n = syllables.size
        if (n == 0 || limit <= 0) return emptyList()

        // 每个位置保留 K 条最优路径
        val best = arrayOfNulls<MutableList<Path>>(n + 1)
        best[0] = mutableListOf(Path(0, emptyList(), emptyList()))

        for (j in 1..n) {
            val bucket = ArrayList<Path>()
            for (i in 0 until j) {
                val prev = best[i] ?: continue
                val key = syllables.subList(i, j).joinToString("")
                val cand = candidateFor(key, j - i == 1) ?: continue
                val edge = cand.score + LONG_BONUS * (j - i - 1) - WORD_PENALTY
                for (p in prev) {
                    bucket.add(Path(p.score + edge, p.words + cand.text, p.counts + (j - i)))
                }
            }
            if (bucket.isEmpty()) continue
            bucket.sortByDescending { it.score }
            best[j] = bucket.take(limit).toMutableList()
        }
        return best[n] ?: emptyList()
    }

    /** 整句候选。 */
    fun sentence(syllables: List<String>): String =
        segmentWords(syllables).joinToString("")

    private fun candidateFor(key: String, single: Boolean): WordCand? {
        dictionary.phrases.top(key)?.let { return it }
        if (single) {
            val c = dictionary.charCandidates(key).firstOrNull() ?: return null
            return WordCand(c.text, c.score - CHAR_FALLBACK_PENALTY)
        }
        return null
    }

    companion object {
        /** 长词每多覆盖一个音节额外加分，鼓励组成词而不是逐字切。 */
        private const val LONG_BONUS = 10

        /** 每个词的罚分 discouraging 过度切分。参数由 tools/segment_eval.mjs 网格搜出来。 */
        private const val WORD_PENALTY = 120

        /** 词库里没有、只能退回单字时的额外罚分。 */
        private const val CHAR_FALLBACK_PENALTY = 20
    }
}

/** 由 ImeData 构造指定方案的引擎。 */
fun ImeData.engine(schemeId: String): PinyinEngine {
    val scheme = schemes[schemeId] ?: error("未知方案：$schemeId")
    return PinyinEngine(scheme, table, dictionary)
}

/** 输入会话的阶段。 */
enum class InputStage {
    /** 声母位：等待第一个键 */
    INITIAL,

    /** 韵母位：等待第二个键 */
    FINAL,

    /** 音节已定：等待选字/上屏 */
    SYLLABLE,
}

/**
 * 上滑删除的结果——删掉了什么。
 *
 * 留着是为了能撤销：下滑撤销要把删掉的音节原样放回去。
 */
data class SwipeRemoval(
    /** 被删掉的完整音节（退成半截音节时也记着——撤销要还原**整个**音节）。 */
    val syllable: FinalHit? = null,
    /** 被丢掉的半截音节：声母键（没有则为 null）。 */
    val partialFirstKey: String? = null,
    /** 被丢掉的半截音节：这一声母下的候选韵母。 */
    val partialFinals: Map<String, FinalHit> = emptyMap(),
)

/**
 * 整轮输入的**状态快照**。
 *
 * 「上滑停顿 = 删掉所有已输入的拼音」要用它来撤销——
 * 一次删掉的是**整轮**，不是一格，恢复时得整个恢复。
 */
data class InputSnapshot(
    val pending: List<FinalHit>,
    val firstKey: String?,
    val finals: Map<String, FinalHit>,
    val stage: InputStage,
    val insertAt: Int,
    val caret: Int,
)

/**
 * 输入会话状态机。
 *
 * 与原型 index.html 的 state 结构一一对应，
 * 便于逐条对照行为。
 */
class InputSession(private val engine: PinyinEngine) {

    var stage: InputStage = InputStage.INITIAL
        private set

    var firstKey: String? = null
        private set

    var finals: Map<String, FinalHit> = emptyMap()
        private set

    /** 本轮已输入的音节序列（用于词组匹配）。 */
    val pending: MutableList<FinalHit> = mutableListOf()

    /**
     * **下一个音节插在 [pending] 的哪个位置**（0..pending.size）。
     *
     * 正常情况下 == pending.size（在末尾接着打）。
     *
     * 用户把光标点到拼音中间时它会 < pending.size——
     * 接着打的键插在那儿，后面已经打好的音节（`pending[insertAt..]`）跟在后头。
     * 这就是「拼音打错了，点一下就能改」的实现。
     */
    var insertAt: Int = 0
        private set

    /**
     * **光标在预编辑区里的字符偏移**。
     *
     * 默认在末尾（= [composingText].length）。
     * 用户点了拼音中间它就会变小——这时退格删的是**光标前面那个字**，
     * 不是最后一个字。
     */
    var caret: Int = 0
        private set

    /** 已上屏文本。 */
    var committed: String = ""
        private set

    /** 当前拼音串，如 "nihao"。 */
    val pinyin: String get() = pending.joinToString("") { it.syllable ?: "" }

    /**
     * 预编辑区应显示的内容。
     *
     * 音节还没定下来时显示已输入的编码（如 "b"），
     * 否则用户会以为键盘没反应。
     *
     * 注意：已经完成的音节要一直留在预编辑区里。
     * 敲完 "ni" 再敲下一个音节的第一个键时，这里必须显示 "nih" 而不是 "h"，
     * 否则前面那个音节会在用户眼皮底下消失。
     *
     * 光标在中间时，顺序也按视觉顺序来：
     * `pending[0..insertAt-1]` + 半截音节 + `pending[insertAt..]`。
     */
    val composingText: String
        get() = segments().joinToString("") { it.text }

    /** 预编辑区的一段：完整音节，或「半截音节」（只有声母键）。 */
    private class Segment(
        val hit: FinalHit?,
        val text: String,
        /** 是不是「半截音节」（刚按了声母、韵母还没定）。 */
        val half: Boolean,
        /** 它在 [pending] 里的下标（半截音节没有下标，为 -1）。 */
        val index: Int,
    )

    /**
     * 预编辑区按视觉顺序的分段。
     *
     * 「半截音节」永远排在插入点上——它就是「正在打的那个音节」。
     */
    private fun segments(): List<Segment> {
        val out = ArrayList<Segment>()
        for (i in pending.indices) {
            if (i == insertAt && stage == InputStage.FINAL && firstKey != null) {
                out += Segment(null, halfText(firstKey!!), true, -1)
            }
            out += Segment(pending[i], pending[i].syllable ?: "", false, i)
        }
        if (stage == InputStage.FINAL && insertAt >= pending.size && firstKey != null) {
            out += Segment(null, halfText(firstKey!!), true, -1)
        }
        return out
    }

    /**
     * 半截音节在预编辑区里显示的文字：**声母的拼音**。
     *
     * 显示键位字母是不行的——小鹤里 sh 在 `u` 键上，
     * 屏幕上冒出个 "u" 用户根本看不出那是 "sh"。
     * （单字母声母两者一样，显示的就是那个字母。）
     */
    private fun halfText(key: String): String =
        engine.scheme.initialByKey[key] ?: key

    /**
     * 插入点（下一个音节该插的地方）在预编辑区里的字符偏移。
     *
     * 光标就跟着它走——输入框里看到的插入位置，永远是会话里真正的插入位置。
     */
    private fun insertionOffset(): Int {
        var acc = 0
        val n = insertAt.coerceIn(0, pending.size)
        for (i in 0 until n) acc += pending[i].syllable?.length ?: 0
        // 半截音节就在插入点上，它本身也占位置。
        //
        // ⚠ 要按**显示出来的文字**算，不是按键位字母算：
        // 小鹤里 sh 在 `u` 键上，预编辑区显示的是 "sh"（两个字符），
        // 按键位字母算会少一个——光标就会落在半截音节的中间。
        if (stage == InputStage.FINAL) acc += halfText(firstKey ?: "").length
        return acc
    }

    /** 当前串的默认首字串，用于整句上屏。 */
    val previewChars: String
        get() = pending.joinToString("") {
            engine.candidateCharList(it.syllable ?: "").firstOrNull().orEmpty()
        }

    /** 当前串命中的词（最高频）。 */
    val phrase: String? get() = engine.phraseFor(pinyin)

    /** 当前串命中的全部候选词，按词频降序。 */
    val phraseCandidates: List<String> get() = engine.phraseCandidates(pinyin)

    /** 当前串切分出来的词。 */
    val segmentWords: List<String>
        get() = engine.segmentWords(pending.map { it.syllable ?: "" })

    /**
     * 整句的备选切分（等长候选）。
     *
     * 与 [segmentWords] 的区别：它给的是**前 K 条不同切分**，
     * 每条都是等长整句。既是用户的备选，也是重排模型的候选池。
     */
    val alternativeSentences: List<String>
        get() = engine.segmentSentences(pending.map { it.syllable ?: "" })

    /** 整句候选（分词后拼起来）。 */
    val sentence: String get() = segmentWords.joinToString("")

    /** 候选字（最后一个音节的）。 */
    val candidates: String
        get() = pending.lastOrNull()?.let { engine.candidateChars(it.syllable ?: "") } ?: ""

    /** 按下一个键。 */
    fun pressKey(key: String): Boolean {
        when (stage) {
            InputStage.INITIAL -> {
                val resolved = engine.resolveFinals(key)
                if (resolved.isEmpty()) return false
                firstKey = key
                finals = resolved
                stage = InputStage.FINAL
                caret = insertionOffset()
                return true
            }

            InputStage.FINAL -> return chooseFinal(key)

            InputStage.SYLLABLE -> {
                // 音节已定，按新字母即开下一个音节
                val resolved = engine.resolveFinals(key)
                if (resolved.isEmpty()) return false
                firstKey = key
                finals = resolved
                stage = InputStage.FINAL
                caret = insertionOffset()
                return true
            }
        }
    }

    /** 在韵母位选定一个第二键。 */
    fun chooseFinal(key: String): Boolean {
        val hit = finals[key] ?: return false
        // 插在插入点上——光标在中间时，新音节就插在那个位置
        pending.add(insertAt, hit)
        insertAt++
        stage = InputStage.SYLLABLE
        caret = insertionOffset()
        return true
    }

    /**
     * **把光标挪到预编辑区的第 [offset] 个字符处。**
     *
     * 挪完之后，接着按的键会**插在光标处**——
     * 这就是「拼音打错了，点一下就能改」的实现。
     *
     * 光标落在「半截音节」前面时，那半截音节作废（它只占了一个键，
     * 而半截音节只能待在插入点上，没法留在别处）。
     *
     * @return 有没有真的挪动
     */
    fun setCaret(offset: Int): Boolean {
        val text = composingText
        val clamped = offset.coerceIn(0, text.length)
        var acc = 0
        var pendingIdx = 0
        for (s in segments()) {
            if (s.half) {
                if (clamped <= acc) {
                    // 光标在半截音节前面 → 它作废（半截音节只能待在插入点上）
                    firstKey = null
                    finals = emptyMap()
                    stage = if (pending.isNotEmpty()) InputStage.SYLLABLE else InputStage.INITIAL
                }
                // 光标在半截音节后面 → 保留它（下一个键补全它）
                insertAt = pendingIdx
                caret = clamped
                return true
            }
            val segStart = acc
            acc += s.text.length
            pendingIdx++
            if (clamped <= segStart) {
                // 光标在这个音节的开头 → 插在它**前面**
                insertAt = pendingIdx - 1
                caret = clamped
                return true
            }
            if (clamped <= acc) {
                // 光标在这个音节的里面或末尾 → 插在它**后面**
                insertAt = pendingIdx
                caret = clamped
                return true
            }
        }
        insertAt = pendingIdx
        caret = clamped
        return true
    }

    /**
     * **删掉光标前面那个字。**
     *
     * 光标在末尾时，删的是最后一个音节的最后一个字母；
     * 光标在中间时，删的是那一处的字母。
     *
     * 截短之后那个音节可能：
     * - **正好只剩声母**（"ri" → "r"）→ 退回「半截音节」，
     *   用户接着按韵母键就能补上（按 "f" 就是 "ren"）
     * - **还是个体面的音节**（"xian" → "xia"）→ 换成它
     * - **不成立了**（"xian" → "xin"）→ 退而求其次只留光标前面那截，
     *   再不济就把整个音节删掉
     */
    fun deleteBeforeCaret(): Boolean {
        if (caret <= 0) return false
        var acc = 0
        for (s in segments()) {
            if (caret - 1 < acc + s.text.length) {
                val k = (caret - 1) - acc    // 分段内的下标
                return if (s.half) {
                    // 半截音节：整个删掉
                    firstKey = null
                    finals = emptyMap()
                    stage = if (pending.isNotEmpty()) InputStage.SYLLABLE else InputStage.INITIAL
                    caret = acc
                    true
                } else {
                    deleteInSyllable(s.hit!!, k, s.index, acc)
                }
            }
            acc += s.text.length
        }
        return false
    }

    /**
     * 删掉某个音节里的一个字。
     *
     * @param hit   要改的音节
     * @param k     删掉的是它里面的第几个字
     * @param index 它在 pending 里的下标
     * @param segStart 它在预编辑区里的起始偏移
     */
    private fun deleteInSyllable(hit: FinalHit, k: Int, index: Int, segStart: Int): Boolean {
        val orig = hit.syllable ?: ""
        // 删掉第 k 个字：前半截 + 后半截
        val prefix = orig.substring(0, k)
        val suffix = orig.substring(k + 1)
        val cut = prefix + suffix

        // ① 截短之后还是个体面的音节 → 直接换
        //    （最常见："xian" 删掉 "a" → "xin"）
        val cutHit = syllableOf(cut)
        if (cutHit != null) {
            pending[index] = cutHit
            caret = segStart + k
            return true
        }

        val prefixHit = if (prefix.isEmpty()) null else syllableOf(prefix)
        val suffixHit = if (suffix.isEmpty()) null else syllableOf(suffix)

        // ② 前后两截**各自都是音节** → 拆成两个音节
        //
        //    以前只留前半截、后半截直接扔掉——用户删一个字，
        //    结果后面一截拼音凭空消失（用户反馈「删的不是一个字，是别的拼音」）。
        if (prefixHit != null && suffixHit != null) {
            pending[index] = prefixHit
            pending.add(index + 1, suffixHit)
            if (insertAt > index) insertAt++
            caret = segStart + k
            return true
        }

        // ③ 前半截正好只剩声母 → 退回「半截音节」，用户接着按韵母键就能补上
        //    （"ri" 删掉 "i" → "r"，再按 "f" → "ren"）
        //
        //    光标停在**半截音节后面**（segStart + k = 保留部分的长度）：
        //    停在前面的话，下一次退格会越过这个字、删到它前头去
        //    （用户反馈「删除 u 后光标跑到 f 前面，再删就跳过 f 一直往前删」）。
        val initKey = hit.initialKey
        if (prefix.isNotEmpty() && prefix == hit.initialPinyin && initKey != null) {
            pending.removeAt(index)
            insertAt = index
            firstKey = initKey
            finals = engine.resolveFinals(initKey)
            stage = InputStage.FINAL
            caret = segStart + k
            return true
        }

        // ④ 前半截自己是个音节 → 换成它
        //    （后半截不是音节，留不住——但至少只丢它自己那一截）
        if (prefixHit != null) {
            pending[index] = prefixHit
            caret = segStart + k
            return true
        }

        // ⑤ 后半截自己是个音节 → 换成它
        //    （"wei" 删掉 "w" → "ei"；以前整个音节都被删掉了）
        if (suffixHit != null) {
            pending[index] = suffixHit
            caret = segStart + k
            return true
        }

        // ⑥ 整个音节删掉
        pending.removeAt(index)
        if (insertAt > index) insertAt--
        caret = segStart
        return true
    }

    /**
     * 把一段拼音换算成音节；换算不出来（或者换算出来不是它）就 null。
     *
     * 「换算出来不是它」这一条很重要：拼音串被截短之后，
     * 同一个键位编码可能对应另一个音节——那就不是用户想要的那个。
     */
    private fun syllableOf(pinyin: String): FinalHit? {
        if (pinyin.isEmpty()) return null
        // 零声母的音节（a/ai/an/ang/ao/e/ei/en/eng/er/o/ou）走零声母表
        engine.scheme.zeroInitialCodes[pinyin]?.let { code ->
            val hit = hitOf(code, pinyin)
            if (hit != null) return hit
        }
        val code = engine.encode(pinyin)
        if (code.length != 2) return null
        return hitOf(code, pinyin)
    }

    /** 两键编码 → 音节；解出来的音节跟 [expect] 不是同一个就当失败。 */
    private fun hitOf(code: String, expect: String): FinalHit? {
        if (code.length != 2) return null
        val hit = engine.resolveFinals(code.substring(0, 1))[code.substring(1, 2)] ?: return null
        if (hit.syllable != expect) return null
        return hit
    }

    /** 空格上屏：返回要上屏的文本，并清空本轮输入。 */
    fun commitTop(): String? {
        if (stage != InputStage.SYLLABLE || pending.isEmpty()) return null
        // 整串在词库里有命中就优先用它（那是人工标注的词）；
        // 否则用分词拼出来的句子（"woxiangchifan" -> "我想吃饭"）。
        val exact = engine.phraseFor(pinyin)
        val segmented = sentence
        val text = when {
            exact != null && exact.length >= segmented.length -> exact
            segmented.isNotEmpty() -> segmented
            else -> previewChars
        }
        committed += text
        reset()
        return text
    }

    /** 选定一个字上屏：前面的音节用默认首字，最后一个音节用选中的字。 */
    fun commitChar(ch: String): String {
        val base = pending.dropLast(1).joinToString("") {
            engine.candidateCharList(it.syllable ?: "").firstOrNull().orEmpty()
        }
        val text = base + ch
        committed += text
        reset()
        return text
    }

    /** 提交任意文本（候选栏点词时用）。 */
    fun commitText(text: String): String {
        committed += text
        reset()
        return text
    }

    /** 退格：音节位回退到韵母位，韵母位回退到声母位，声母位删一个音节，再往后删已上屏字符。 */
    fun backspace(): Boolean {
        when (stage) {
            InputStage.SYLLABLE -> {
                pending.removeLastOrNull()
                if (insertAt > pending.size) insertAt = pending.size
                stage = InputStage.FINAL
                caret = insertionOffset()
                return true
            }

            InputStage.FINAL -> {
                stage = InputStage.INITIAL
                firstKey = null
                finals = emptyMap()
                caret = insertionOffset()
                return true
            }

            InputStage.INITIAL -> {
                // 声母位但还有已完成的音节时，退格应该先把这个音节删掉，
                // 而不是直接去动宿主应用里的已上屏文本——否则会话和宿主会脱节。
                if (pending.isNotEmpty()) {
                    pending.removeLastOrNull()
                    if (insertAt > pending.size) insertAt = pending.size
                    caret = insertionOffset()
                    return true
                }
                if (committed.isEmpty()) return false
                committed = committed.dropLast(1)
                return true
            }
        }
    }

    /** 清空整轮输入。 */
    fun clear() {
        reset()
        committed = ""
    }

    /**
     * 上滑删除：**退一格**。
     *
     * 退的粒度是「**声母/韵母**」，不是整个音节：
     *
     * | 当前状态 | 退一格的结果 |
     * |---|---|
     * | 最后一个是完整音节（"ren"） | 删掉它的**韵母**，退回半截音节（"r"）——用户按个韵母键就能改 |
     * | 最后一个已经是半截音节（"r"） | 整个删掉（没了） |
     * | 拼音全没了 | 返回 null，交给上层去删已上屏的文字 |
     *
     * 为什么不一步删光：用户上滑往往只是想退回一两个音节重打，
     * 一步全删掉等于把已经打对的字也丢了。
     */
    fun swipeDelete(): SwipeRemoval? = when (stage) {
        InputStage.FINAL -> {
            // 半截音节：这一格就是「最后输入的」
            val removal = SwipeRemoval(
                partialFirstKey = firstKey,
                partialFinals = finals,
            )
            firstKey = null
            finals = emptyMap()
            stage = if (pending.isNotEmpty()) InputStage.SYLLABLE else InputStage.INITIAL
            caret = insertionOffset()
            removal
        }

        InputStage.SYLLABLE -> {
            val hit = pending.lastOrNull()
            if (hit == null) {
                null
            } else {
                val initKey = hit.initialKey
                if (initKey != null && hit.initialPinyin.isNotEmpty() && hit.syllable != hit.initialPinyin) {
                    // 有声母 → 删掉韵母，退回半截音节（"ren" → "r"）
                    pending.removeAt(pending.size - 1)
                    if (insertAt > pending.size) insertAt = pending.size
                    firstKey = initKey
                    finals = engine.resolveFinals(initKey)
                    stage = InputStage.FINAL
                    caret = insertionOffset()
                    // 撤销时要把**整个音节**放回去（不是还原成半截）
                    SwipeRemoval(syllable = hit)
                } else {
                    // 零声母音节（ai/e/er…）没有声母可留 → 整个删掉
                    pending.removeAt(pending.size - 1)
                    if (insertAt > pending.size) insertAt = pending.size
                    if (pending.isEmpty()) stage = InputStage.INITIAL
                    caret = insertionOffset()
                    SwipeRemoval(syllable = hit)
                }
            }
        }

        InputStage.INITIAL -> {
            // 理论上 INITIAL 时没有已完成的音节，但退格可能把状态留在这儿
            val hit = pending.removeLastOrNull() ?: return null
            if (insertAt > pending.size) insertAt = pending.size
            caret = insertionOffset()
            SwipeRemoval(syllable = hit)
        }
    }

    /**
     * 整轮输入的状态快照（撤销「全部删掉」时用）。
     */
    fun snapshot(): InputSnapshot = InputSnapshot(
        pending = pending.toList(),
        firstKey = firstKey,
        finals = finals,
        stage = stage,
        insertAt = insertAt,
        caret = caret,
    )

    /**
     * 把整轮输入恢复成快照的样子（撤销「全部删掉」）。
     */
    fun restore(snapshot: InputSnapshot) {
        pending.clear()
        pending.addAll(snapshot.pending)
        firstKey = snapshot.firstKey
        finals = snapshot.finals
        stage = snapshot.stage
        insertAt = snapshot.insertAt
        caret = snapshot.caret
    }

    /**
     * 清空**整轮**输入（不动已上屏的文字）。
     *
     * 「上滑停顿 = 删掉所有已输入的拼音」用这个。
     */
    fun clearInput() {
        pending.clear()
        firstKey = null
        finals = emptyMap()
        stage = InputStage.INITIAL
        insertAt = 0
        caret = 0
    }

    /**
     * 撤销一次上滑删除——把删掉的音节原样放回去。
     */
    fun undoSwipe(removal: SwipeRemoval) {
        removal.syllable?.let {
            pending.add(it)
            if (insertAt > pending.size) insertAt = pending.size
            stage = InputStage.SYLLABLE
            caret = insertionOffset()
        }
        removal.partialFirstKey?.let {
            firstKey = it
            finals = removal.partialFinals
            stage = InputStage.FINAL
            caret = insertionOffset()
        }
    }

    private fun reset() {
        pending.clear()
        stage = InputStage.INITIAL
        firstKey = null
        finals = emptyMap()
        insertAt = 0
        caret = 0
    }
}
