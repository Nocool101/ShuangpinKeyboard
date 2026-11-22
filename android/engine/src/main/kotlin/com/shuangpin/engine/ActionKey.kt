package com.shuangpin.engine

/**
 * **动作键**（键盘右下角那颗蓝键）在不同宿主里的含义。
 *
 * 同一颗键，在不同应用里该干的事完全不一样：
 *
 * | 宿主 | 键面 | 点击之后 |
 * |---|---|---|
 * | 微信 / QQ 等聊天工具 | **发送** | 把消息发出去 |
 * | 多行文本框（备忘录、评论框） | **换行** | 换到下一行 |
 * | 浏览器地址栏 | **前往** | 搜索 / 打开网址 |
 * | 普通单行输入框 | **完成** | 收尾这一轮输入 |
 *
 * ## 为什么要单独判定
 *
 * `EditorInfo.imeOptions` 里的 action 只是「编辑框声明的意图」，
 * 而实际上 App 经常**不声明**（微信的聊天框就没声明 action）。
 * 所以判定要同时看三样东西：**包名 + action + 是否多行**。
 *
 * ## 放在引擎模块的理由
 *
 * 引擎模块是整个工程里**唯一有单测基建**的模块（app 模块没有测试依赖）。
 * 这段判定是纯逻辑（三个 int 进、一个枚举出），放这儿就能直接被单测覆盖，
 * 不必为了测它给 app 模块新增依赖。
 *
 * 代价是这里**不能直接用 `EditorInfo` / `InputType` 的常量**——
 * 引擎模块刻意不依赖 Android SDK。所以下面的常量是从 AOSP 抄的，
 * 值和 `android.view.inputmethod.EditorInfo` / `android.text.InputType` 完全一致。
 */
enum class ActionKind {
    /** 发送消息（聊天工具）。 */
    SEND,

    /** 换行（多行文本框）。 */
    NEWLINE,

    /** 前往（浏览器地址栏 / action=GO）。 */
    GO,

    /** 搜索（action=SEARCH，但不是浏览器）。 */
    SEARCH,

    /** 完成（action=DONE，或没声明 action 的单行输入框）。 */
    DONE,

    /** 什么都不做（宿主声明的是「下一个 / 上一个」这类媒体 action）。 */
    NONE,
}

/**
 * 动作键的判定规则。
 *
 * 优先级是**从具体到宽泛**：先认「这是聊天软件」（包名），
 * 再认「编辑框声明了 action」，最后才看「是不是多行」。
 *
 * 顺序不能换：微信的聊天框**既没声明 action 又是多行的**，
 * 要是先看多行，它就会被判成「换行」——那用户永远发不出消息。
 */
object ActionKey {

    // ---- EditorInfo.IME_ACTION_*（照抄 AOSP 的值）----

    /** 编辑框没声明 action。 */
    const val IME_ACTION_UNSPECIFIED = 0

    /** 显式声明「没有 action」。 */
    const val IME_ACTION_NONE = 1

    /** 「前往」。 */
    const val IME_ACTION_GO = 2

    /** 「搜索」。 */
    const val IME_ACTION_SEARCH = 3

    /** 「发送」。 */
    const val IME_ACTION_SEND = 4

    /** 「下一个」（媒体控制）。 */
    const val IME_ACTION_NEXT = 5

    /** 「完成」。 */
    const val IME_ACTION_DONE = 6

    /** 「上一个」（媒体控制）。 */
    const val IME_ACTION_PREVIOUS = 7

    // ---- InputType（照抄 AOSP 的值）----

    /**
     * `InputType.TYPE_TEXT_FLAG_MULTI_LINE`。
     *
     * 编辑框允许多行 → 回车应该是「换行」而不是「发送」。
     */
    const val TYPE_TEXT_FLAG_MULTI_LINE = 0x00020000

    /**
     * 聊天类 App。
     *
     * 这些 App 的输入框**一般不声明 action**，而且**是多行的**——
     * 只看 `imeOptions` / `inputType` 一律判错，只能认包名。
     */
    val CHAT_PACKAGES: Set<String> = setOf(
        "com.tencent.mm",             // 微信
        "com.tencent.mobileqq",       // QQ
        "com.tencent.tim",            // TIM
        "com.tencent.wework",         // 企业微信
        "com.sina.weibo",             // 微博
        "com.xingin.xhs",             // 小红书
        "com.alibaba.android.rimet",  // 钉钉
        "com.immomo.momo",            // 陌陌
    )

    /**
     * 浏览器。
     *
     * 地址栏里回车 = 搜索 / 打开网址，所以这类应用里键面是「前往」。
     */
    val BROWSER_PACKAGES: Set<String> = setOf(
        "mark.via.gp",               // Via 浏览器
        "com.android.chrome",
        "mark.android.browser",
        "com.android.browser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.UCMobile.intl",
        "com.baidu.browser.apps",
        "com.huawei.browser",
        "com.honor.browser",
        "com.quark.browser",
        "com.duckduckgo.mobile.android",
    )

    /**
     * 这个宿主里，动作键该是什么。
     *
     * @param packageName 宿主 App 的包名（拿不到就传空串）
     * @param imeAction   `EditorInfo.imeOptions and EditorInfo.IME_MASK_ACTION`
     * @param inputType   `EditorInfo.inputType`
     */
    fun resolve(packageName: String, imeAction: Int, inputType: Int): ActionKind {
        // ① 聊天软件：回车就是「发送消息」。
        //    放在最前面，因为微信的聊天框既不声明 action 又是多行的。
        if (packageName in CHAT_PACKAGES) return ActionKind.SEND

        // ② 编辑框自己声明了「发送」。
        if (imeAction == IME_ACTION_SEND) return ActionKind.SEND

        // ③ 多行文本框：回车是「换行」。
        val multiline = (inputType and TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (multiline) return ActionKind.NEWLINE

        // ④ 浏览器：地址栏回车 = 搜索 / 打开网址。
        //    放在 action 之前——浏览器地址栏常声明的是 NONE 或 SEARCH，
        //    但对用户来说那一下永远是「搜索」。
        if (packageName in BROWSER_PACKAGES) return ActionKind.GO

        // ⑤ 编辑框声明的 action。
        return when (imeAction) {
            IME_ACTION_GO -> ActionKind.GO
            IME_ACTION_SEARCH -> ActionKind.SEARCH
            IME_ACTION_DONE -> ActionKind.DONE
            // 媒体控制（下一个/上一个）跟输入无关，不给动作。
            IME_ACTION_NEXT, IME_ACTION_PREVIOUS -> ActionKind.NONE
            // 没声明 action 的单行输入框：回车是「我填完了」。
            else -> ActionKind.DONE
        }
    }

    /** 键面上的字。 */
    fun label(kind: ActionKind): String = when (kind) {
        ActionKind.SEND -> "发送"
        ActionKind.NEWLINE -> "换行"
        ActionKind.GO -> "前往"
        ActionKind.SEARCH -> "搜索"
        ActionKind.DONE -> "完成"
        ActionKind.NONE -> "确认"
    }
}
