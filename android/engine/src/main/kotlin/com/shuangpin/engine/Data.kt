package com.shuangpin.engine

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * 数据层：双拼方案、音节表、词典。
 *
 * 设计原则：**代码里不硬编码任何一张键位表**。所有表都来自 resources/ime 目录下的
 * 纯文本数据文件，由 tools/extract_data.ps1 从 index.html（原型唯一真源）生成。
 * 这样「原型改了 → 重新抽取 → 引擎同步」是自动的，不会出现两边不一致。
 */

/** 零声母二段表里的一条：第二键对应的韵母与音节。 */
data class ZeroEntry(val fin: String, val syllable: String)

/**
 * 一个双拼方案。
 *
 * @param initials 声母 -> 键位（zh->v 等）
 * @param finals   韵母 -> 键位
 * @param zeroStage 零声母入口键 -> (第二键 -> 条目)
 */
data class Scheme(
    val id: String,
    val name: String,
    val initials: Map<String, String>,
    val finals: Map<String, String>,
    val zeroStage: Map<String, Map<String, ZeroEntry>>,
) {
    /**
     * 键位 -> 声母。
     *
     * 方案自带映射**后**于恒等映射覆盖：自然码用 x 表示 ch，
     * 若恒等映射后写，x 会被错误地当成声母 x。
     */
    val initialByKey: Map<String, String> = buildMap {
        IDENTITY_INITIALS.forEach { put(it, it) }
        initials.forEach { (ini, key) -> put(key, ini) }
    }

    /** 键位 -> 该键承载的全部韵母，按键面标签优先级排序。 */
    val finalsByKey: Map<String, List<String>> =
        finals.entries
            .groupBy({ it.value }, { it.key })
            .mapValues { (_, fs) -> fs.sortedBy { Display.priority(it) } }

    /** 零声母：韵母 -> 两键编码（如 ang -> ah）。由 zeroStage 反推，不另存一份表。 */
    val zeroInitialCodes: Map<String, String> = buildMap {
        zeroStage.forEach { (zeroKey, entries) ->
            entries.forEach { (secondKey, entry) ->
                putIfAbsent(entry.fin, zeroKey + secondKey)
            }
        }
    }
}

/** 一个音节的解析结果：声母（零声母为 null）与韵母。 */
data class ParsedSyllable(val initial: String?, val final: String)

/**
 * 音节表：由全拼音节清单推导出的「声母|韵母 -> 音节」索引。
 *
 * 这是整个引擎的地基——双拼的歧义消解全靠它：给定声母后，
 * 某个键位上的韵母候选里，只有能组成合法音节的那个才成立。
 */
class SyllableTable(
    val initialOrder: List<String>,
    val ywFinals: Map<String, String>,
    /** "ini|fin" -> 音节（同一组合保留首个写法） */
    val byInitialFinal: Map<String, String>,
    /** 声母 -> 该声母下所有合法韵母（保持音节表原始顺序） */
    val finalsByInitial: Map<String, List<String>>,
    /** 零声母：韵母 -> 音节 */
    val zeroSyllables: Map<String, String>,
    /**
     * 全部合法音节（去重后的扁平清单）。
     *
     * 长词扩展的「枚举下一个音节」那条兜底路径要用它：
     * 拿到 412 个音节，就能穷举「当前串 + 一个音节」的所有可能，
     * 每个可能做一次精确二分——比扫整个前缀区间便宜得多。
     */
    val syllables: List<String>,
) {
    /** 解析一个全拼音节为（声母, 韵母）。 */
    fun parse(syllable: String): ParsedSyllable =
        parseWith(syllable, initialOrder, ywFinals)

    companion object {
        private fun parseWith(
            s: String,
            order: List<String>,
            yw: Map<String, String>,
        ): ParsedSyllable {
            var initial: String? = null
            var rest = s
            for (i in order) {
                // order 里 zh/ch/sh 排在单字母声母前面，保证最长匹配
                if (s.startsWith(i)) {
                    initial = i
                    rest = s.substring(i.length)
                    break
                }
            }
            if (initial == null) return ParsedSyllable(null, rest)

            // y/w 是整体认读占位，韵母要从完整音节反查
            if (initial == "y" || initial == "w") {
                return ParsedSyllable(initial, yw[s] ?: rest)
            }

            // j/q/x 后面的 u 实为 ü
            if (initial == "j" || initial == "q" || initial == "x") {
                rest = when (rest) {
                    "u" -> "v"
                    "ue" -> "ve"
                    "uan" -> "van"
                    "un" -> "vn"
                    else -> rest
                }
            }
            return ParsedSyllable(initial, rest)
        }

        fun build(
            syllableLines: List<String>,
            initialOrder: List<String>,
            ywFinals: Map<String, String>,
        ): SyllableTable {
            val byInitialFinal = LinkedHashMap<String, String>()
            val finalsByInitial = LinkedHashMap<String, MutableList<String>>()
            val zeroSyllables = LinkedHashMap<String, String>()

            for (line in syllableLines) {
                val sep = line.indexOf(':')
                if (sep < 0) continue
                for (s in line.substring(sep + 1).trim().split(WHITESPACE)) {
                    if (s.isEmpty()) continue
                    val parsed = parseWith(s, initialOrder, ywFinals)
                    val ini = parsed.initial
                    if (ini == null) {
                        zeroSyllables.putIfAbsent(parsed.final, s)
                    } else {
                        byInitialFinal.putIfAbsent("$ini|${parsed.final}", s)
                        finalsByInitial.getOrPut(ini) { mutableListOf() }
                            .let { if (!it.contains(parsed.final)) it.add(parsed.final) }
                    }
                }
            }

            return SyllableTable(
                initialOrder = initialOrder,
                ywFinals = ywFinals,
                byInitialFinal = byInitialFinal,
                finalsByInitial = finalsByInitial.mapValues { it.value.toList() },
                zeroSyllables = zeroSyllables,
                // 扁平音节清单：byInitialFinal 与 zeroSyllables 里的值合起来
                // 就是「这个音节能被拼出来」的全部音节。
                syllables = (byInitialFinal.values + zeroSyllables.values).distinct(),
            )
        }

        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * 一个候选词 / 候选字及其词频分数。
 *
 * 分数 = round(log2(权重) * 4)，越大越常用。
 * 取对数是因为词频跨度好几个数量级，对数之后才能加和（log 概率可加）。
 */
data class WordCand(val text: String, val score: Int)

/**
 * 词库：紧凑存储 + 二分查找。
 *
 * 词库有几十万个拼音串。用 `HashMap<String, String>` 装，光 Map 的开销就是
 * 两百多 MB，手机上会被系统直接杀掉输入法进程。
 *
 * 改成「一整块文本 blob + 每行起始下标的 IntArray」，内存就只有文本本身的体积
 * （二十几 MB），查询用二分。
 *
 * 前提：文本按拼音串有序（由 tools/import_dict.mjs 保证）。
 */
class PhraseStore private constructor(
    private val blob: String,
    private val lineStart: IntArray,
) {
    /** 该拼音串的候选词，按词频降序。查不到返回空列表。 */
    fun candidates(pinyin: String): List<WordCand> {
        val raw = rawValue(pinyin) ?: return emptyList()
        return raw.split(',').mapNotNull { parseCand(it) }
    }

    /**
     * 第一个 key >= prefix 的行号。
     *
     * 词库按键有序，所以这是二分定位，O(log n)。
     * 前缀区间扫描的起点就靠它。
     */
    fun lowerBound(prefix: String): Int {
        var lo = 0
        var hi = size - 1
        var ans = size
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (compareKey(mid, prefix) < 0) {
                lo = mid + 1
            } else {
                ans = mid
                hi = mid - 1
            }
        }
        return ans
    }

    /**
     * 以 prefix 开头的拼音串所占的行区间 `[start, end)`。
     *
     * **长词扩展（联想）就靠它**：二分定位起点，再往后扫到第一个不匹配的为止。
     * 实测「nihao」这个前缀只覆盖 57 行，「zhongguo」1398 行，
     * 而「shi」这种单音节前缀上万行——所以调用方要按区间大小决定扫描还是枚举。
     */
    fun prefixRange(prefix: String): IntRange {
        if (prefix.isEmpty()) return 0 until size
        val start = lowerBound(prefix)
        var end = start
        while (end < size && keyStartsWith(end, prefix)) end++
        return start until end
    }

    /**
     * 第 idx 行的**首个**候选（词频最高的那个）。
     *
     * 前缀扫描一次要过几千行，逐行 split 整行会产生大量临时字符串——
     * 这里只取到第一个逗号为止，只解析一个候选。
     */
    fun firstCandidateAt(idx: Int): WordCand? {
        if (idx < 0 || idx >= size) return null
        var i = lineStart[idx]
        while (i < blob.length && blob[i] != '\t') i++
        i++
        var end = i
        while (end < blob.length && blob[end] != ',' && blob[end] != '\n') end++
        if (end <= i) return null
        return parseCand(blob.substring(i, end))
    }

    /** 第 idx 行的全部候选，按词频降序。 */
    fun candidatesAt(idx: Int): List<WordCand> {
        val raw = rawValueAt(idx) ?: return emptyList()
        return raw.split(',').mapNotNull { parseCand(it) }
    }

    /** 该拼音串词频最高的那个候选。 */
    fun top(pinyin: String): WordCand? {
        val raw = rawValue(pinyin) ?: return null
        val end = raw.indexOf(',')
        return parseCand(if (end < 0) raw else raw.substring(0, end))
    }

    /** 词库条目数。 */
    val size: Int get() = lineStart.size

    /** 第 idx 行的拼音串（测试与排查用）。 */
    fun keyAt(idx: Int): String {
        if (idx < 0 || idx >= size) return ""
        var i = lineStart[idx]
        val end = blob.indexOf('\t', i)
        val stop = if (end < 0) blob.length else end
        return blob.substring(i, stop)
    }

    private fun rawValue(pinyin: String): String? {
        val idx = indexOf(pinyin)
        if (idx < 0) return null
        return rawValueAt(idx)
    }

    private fun rawValueAt(idx: Int): String? {
        if (idx < 0 || idx >= size) return null
        var i = lineStart[idx]
        while (i < blob.length && blob[i] != '\t') i++
        i++
        var end = blob.indexOf('\n', i)
        if (end < 0) end = blob.length
        if (i >= end) return null
        return blob.substring(i, end)
    }

    /** 第 idx 行的拼音串是否以 prefix 开头（直接比 blob，不分配字符串）。 */
    private fun keyStartsWith(idx: Int, prefix: String): Boolean {
        var i = lineStart[idx]
        var k = 0
        while (k < prefix.length) {
            if (i >= blob.length) return false
            if (blob[i] != prefix[k]) return false
            i++
            k++
        }
        return true
    }

    private fun parseCand(raw: String): WordCand? {
        val sep = raw.lastIndexOf(':')
        if (sep <= 0) return null
        return WordCand(raw.substring(0, sep), raw.substring(sep + 1).toIntOrNull() ?: 0)
    }

    private fun indexOf(key: String): Int {
        var lo = 0
        var hi = lineStart.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compareKey(mid, key)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** 比较第 idx 行的拼音串与 key。 */
    private fun compareKey(idx: Int, key: String): Int {
        var i = lineStart[idx]
        var k = 0
        while (k < key.length) {
            if (i >= blob.length) return -1
            val c = blob[i]
            if (c != key[k]) return if (c < key[k]) -1 else 1
            i++
            k++
        }
        // key 用完了，看这一行是不是正好结束
        return if (i < blob.length && blob[i] == '\t') 0 else 1
    }

    companion object {
        /** 空词库（词库还没加载完时用）。 */
        val EMPTY: PhraseStore = fromText("")

        fun fromText(text: String): PhraseStore {
            val starts = ArrayList<Int>()
            var i = 0
            val n = text.length
            while (i < n) {
                val nl = text.indexOf('\n', i)
                val end = if (nl < 0) n else nl
                if (end > i && text[i] != '#') starts.add(i)
                if (nl < 0) break
                i = nl + 1
            }
            return PhraseStore(text, starts.toIntArray())
        }
    }
}

/**
 * 词典：音节 -> 候选字；拼音串 -> 候选词。
 *
 * 候选都是**按词频降序**排列的：
 * - chars[音节] 是该音节的候选字列表
 * - phrases 是词库，拼音串 -> 逗号分隔的候选词
 */
class Dictionary(
    val chars: Map<String, List<WordCand>>,
    val phrases: PhraseStore,
) {
    fun charCandidates(syllable: String): List<WordCand> = chars[syllable] ?: emptyList()

    /**
     * 候选字（按词频降序），**一个汉字一个元素**。
     *
     * 别用 [charsOf] 拿到字符串再按字符切：
     * 扩展区的字（𫘪、𠒜 这种）在 UTF-16 里是**两个码元**，
     * 按码元切会劈成两个乱码。字表里每个字本来就是一个完整的字符串，
     * 直接取它的 text 才是对的。
     */
    fun charList(syllable: String): List<String> =
        charCandidates(syllable).map { it.text }

    /** 候选字连写（按词频降序），兼容旧的用法。 */
    fun charsOf(syllable: String): String =
        charCandidates(syllable).joinToString("") { it.text }

    /** 最高频的那个词。 */
    fun phraseOf(pinyin: String): String? = phrases.top(pinyin)?.text

    /** 全部候选词，按词频降序。 */
    fun phraseCandidates(pinyin: String): List<String> =
        phrases.candidates(pinyin).map { it.text }

    /** 全部候选词（含分数），按词频降序。 */
    fun phraseCands(pinyin: String): List<WordCand> = phrases.candidates(pinyin)
}

/** 引擎所需的全部数据。 */
class ImeData(
    val schemes: Map<String, Scheme>,
    val table: SyllableTable,
    val dictionary: Dictionary,
    /** 键位校验用例：汉字 / 拼音 / 期望编码。 */
    val checkCases: List<CheckCase>,
)

/** 一条键位校验用例。 */
data class CheckCase(
    val schemeId: String,
    val hanzi: String,
    val pinyin: String,
    val expectedCode: String,
)

/** 键面显示相关的常量与格式化（纯展示逻辑，引擎核心不依赖）。 */
object Display {
    private val FINAL_LABELS = mapOf(
        "v" to "ü", "ve" to "üe", "van" to "üan", "vn" to "ün",
    )

    /** 键面标签优先级：常用韵母排在前面，声母位显示时取前两个。 */
    private val FINAL_PRIORITY = listOf(
        "a", "o", "e", "i", "u", "v",
        "ai", "ei", "ui", "ao", "ou", "iu", "ie", "ve", "er",
        "an", "en", "in", "un", "vn",
        "ang", "eng", "ing", "ong",
        "ia", "ua", "uo", "uai", "uan", "van", "iang", "uang", "iong", "iao", "ian",
    )

    fun finalLabel(fin: String): String = FINAL_LABELS[fin] ?: fin

    fun priority(fin: String): Int {
        val i = FINAL_PRIORITY.indexOf(fin)
        return if (i < 0) FINAL_PRIORITY.size else i
    }

    /** 声母位标签：同一键位上的韵母，取优先级最高的两个，用 / 连接。 */
    fun initialSlotLabel(scheme: Scheme, key: String): String =
        scheme.finalsByKey[key].orEmpty().take(2).joinToString("/") { finalLabel(it) }
}

/** 资源加载器。basePath 指向 classpath 下的数据目录。 */
class DataLoader(private val base: String = "/ime") {

    /**
     * 读取一个文本资源（按行，去掉空行与注释）。
     *
     * 除了键位表这些数据文件，模型配置（`model.json` / `vocab.json`）也走这里。
     */
    fun lines(name: String): List<String> {
        val stream: InputStream = DataLoader::class.java.getResourceAsStream("$base/$name")
            ?: error("缺少数据文件：$base/$name")
        return stream.use { s ->
            BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).readLines()
        }.map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    /**
     * 读取一个**二进制**资源（模型文件）。
     *
     * 文本资源走 [lines]（一行一个字符串），模型图这类二进制走这里。
     */
    fun binary(name: String): ByteArray {
        val stream: InputStream = javaClass.getResourceAsStream("$base/$name")
            ?: error("缺少资源：$base/$name")
        return stream.use { it.readBytes() }
    }

    /**
     * 加载「小数据」：方案、音节表、校验用例。
     *
     * 只有几十 KB，可以放在主线程同步加载，输入法启动即可用。
     */
    fun loadCore(): ImeData {
        val names = LinkedHashMap<String, String>()
        for (line in lines("schemes.txt")) {
            val p = line.split('\t')
            if (p.size >= 3 && p[0] == "scheme") names[p[1]] = p[2]
        }

        val initials = LinkedHashMap<String, MutableMap<String, String>>()
        for (line in lines("initials.txt")) {
            val p = line.split('\t')
            if (p.size >= 4 && p[0] == "initial") {
                initials.getOrPut(p[1]) { LinkedHashMap() }[p[2]] = p[3]
            }
        }

        val finals = LinkedHashMap<String, MutableMap<String, String>>()
        for (line in lines("finals.txt")) {
            val p = line.split('\t')
            if (p.size >= 4 && p[0] == "final") {
                finals.getOrPut(p[1]) { LinkedHashMap() }[p[2]] = p[3]
            }
        }

        val schemes = names.mapValues { (id, name) ->
            Scheme(
                id = id,
                name = name,
                initials = initials[id].orEmpty().toMap(),
                finals = finals[id].orEmpty().toMap(),
                zeroStage = zeroStageFor(id),
            )
        }

        val initialOrder = lines("initials_order.txt")

        val ywFinals = LinkedHashMap<String, String>()
        for (line in lines("yw_finals.txt")) {
            val p = line.split('\t')
            if (p.size >= 2) ywFinals[p[0]] = p[1]
        }

        val table = SyllableTable.build(
            syllableLines = lines("syllables.txt"),
            initialOrder = initialOrder,
            ywFinals = ywFinals,
        )

        val checkCases = lines("check_cases.txt").mapNotNull { line ->
            val p = line.split('\t')
            if (p.size >= 4) CheckCase(p[0], p[1], p[2], p[3]) else null
        }

        return ImeData(
            schemes = schemes,
            table = table,
            dictionary = Dictionary(emptyMap(), PhraseStore.EMPTY),
            checkCases = checkCases,
        )
    }

    /**
     * 加载「大数据」：字表 + 词表。
     *
     * 词库有几十万个拼音串，必须放后台线程，否则输入法启动会卡住。
     */
    fun loadDictionary(): Dictionary {
        val chars = LinkedHashMap<String, List<WordCand>>()
        for (line in lines("chars.txt")) {
            val p = line.split('\t', limit = 2)
            if (p.size < 2) continue
            val cands = p[1].split(',').mapNotNull { raw ->
                val sep = raw.lastIndexOf(':')
                if (sep <= 0) null
                else WordCand(raw.substring(0, sep), raw.substring(sep + 1).toIntOrNull() ?: 0)
            }
            if (cands.isNotEmpty()) chars.putIfAbsent(p[0], cands)
        }

        // 词库整体读成一个 blob，用偏移数组 + 二分查找，
        // 比 HashMap 省掉两百多 MB 内存。
        val stream: InputStream = DataLoader::class.java.getResourceAsStream("$base/phrases.txt")
            ?: error("缺少数据文件：$base/phrases.txt")
        val phrases = stream.use { s ->
            PhraseStore.fromText(s.readBytes().toString(StandardCharsets.UTF_8))
        }

        return Dictionary(chars, phrases)
    }

    /** 全部数据一次加载（测试用）。 */
    fun load(): ImeData {
        val core = loadCore()
        return ImeData(
            schemes = core.schemes,
            table = core.table,
            dictionary = loadDictionary(),
            checkCases = core.checkCases,
        )
    }

    /** 读取某个 scheme 的零声母二段表。 */
    private fun zeroStageFor(schemeId: String): Map<String, Map<String, ZeroEntry>> {
        val result = LinkedHashMap<String, MutableMap<String, ZeroEntry>>()
        for (line in lines("zero_stage.txt")) {
            val p = line.split('\t')
            if (p.size < 6 || p[0] != "zero" || p[1] != schemeId) continue
            result.getOrPut(p[2]) { LinkedHashMap() }[p[3]] = ZeroEntry(fin = p[4], syllable = p[5])
        }
        return result
    }
}

/** 恒等声母（方案未改键的声母）。 */
val IDENTITY_INITIALS = listOf(
    "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
    "j", "q", "x", "r", "z", "c", "s", "y", "w",
)

/** 零声母入口键。 */
val ZERO_KEYS = listOf("a", "e", "o")
