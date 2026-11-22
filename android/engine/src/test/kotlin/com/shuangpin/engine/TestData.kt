package com.shuangpin.engine

/**
 * 测试用的**共享数据**。
 *
 * 词库有 23 MB，每个测试类都 `DataLoader().load()` 一份的话，
 * 十来个类就能把测试 JVM 的堆吃光（`OutOfMemoryError: Java heap space`）。
 *
 * 这里加载一次，大家共用：
 * - [data] 共享（不可变）
 * - [engine] 每次调用给一个**新的引擎实例**（引擎基本无状态，
 *   但测试里有人会改 `engine.dictionary`，共用一个实例会互相污染）
 */
object TestData {

    /** 全量数据（词库 + 键位表 + 音节表）。只加载一次。 */
    val data: ImeData by lazy { DataLoader().load() }

    /** 小鹤方案的新引擎实例。 */
    fun engine(): PinyinEngine = data.engine("xiaohe")
}
