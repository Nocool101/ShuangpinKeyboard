package com.shuangpin.ime

import com.shuangpin.engine.DataLoader
import com.shuangpin.engine.Reranker
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * 端侧重排的异步控制器。
 *
 * 三条硬约束：
 *
 * 1. **绝不阻塞打字**。模型加载（8 MB 权重 + 建会话，几百毫秒）和推理都在后台线程，
 *    主线程只负责把结果贴回候选栏。
 * 2. **过期结果丢弃**。用户还在打字时算完的结果没有意义——
 *    每次输入会话换一个「代次」，回来时对不上就扔掉。
 * 3. **失败就当没有**。模型加载不了、推理出错，一律静默降级成「不重排」，
 *    输入法照常用（重排是加分项，不是依赖项）。
 */
class RerankController(
    private val appContext: android.content.Context,
    private val loader: DataLoader = DataLoader(),
) {

    /** 模型就绪后回调一次（用来触发一次刷新）。 */
    var onReady: (() -> Unit)? = null

    private val executor = Executors.newSingleThreadExecutor(
        ThreadFactory { r -> Thread(r, "shuangpin-rerank").apply { isDaemon = true } },
    )

    @Volatile
    private var reranker: Reranker? = null

    @Volatile
    private var loading = false

    private val counter = AtomicInteger(0)

    /** 当前这次请求的代号；每次开始新的一轮输入就换掉。 */
    @Volatile
    var token: Long = 0
        private set

    /** 换一个代号：开始新的一轮输入，之前算完的结果全部作废。 */
    fun newToken(): Long {
        val t = counter.incrementAndGet().toLong()
        token = t
        return t
    }

    /** 加载模型（幂等）。在后台线程跑，不会拖慢输入法启动。 */
    fun ensureLoaded() {
        if (reranker != null || loading) return
        loading = true
        executor.execute {
            try {
                diagnostic(appContext, "开始加载模型", null)
                val r = Reranker.load(
                    modelBytes = loader.binary("reranker/lm_step.ort"),
                    configJson = loader.lines("reranker/model.json").joinToString("\n"),
                    vocabJson = loader.lines("reranker/vocab.json").joinToString("\n"),
                    threads = 1,
                )
                reranker = r
                diagnostic(appContext, "模型加载完成", null)
                onReady?.invoke()
            } catch (t: Throwable) {
                // 模型加载不了不是致命错误：不重排而已。
                // 但要留下痕迹——否则「重排没生效」会查不到原因。
                android.util.Log.w(TAG, "模型加载失败，退回不重排", t)
                diagnostic(appContext, "模型加载失败", t)
                reranker = null
            } finally {
                loading = false
            }
        }
    }

    /** 模型是否就绪。 */
    val ready: Boolean get() = reranker != null

    /**
     * 请求重排。
     *
     * @param context 光标之前的已上屏文本（模型的上文）
     * @param candidates **等长**候选
     * @param requestToken 本次请求的代号，回来时用它判断是否过期
     * @param onDone 重排完成（只在结果仍然有效时回调）
     */
    fun request(
        context: String,
        candidates: List<String>,
        requestToken: Long,
        onDone: (List<String>) -> Unit,
    ) {
        val r = reranker ?: return
        if (candidates.size < 2) return
        executor.execute {
            try {
                val t0 = System.nanoTime()
                val ranked = r.rank(context, candidates)
                val us = (System.nanoTime() - t0) / 1000
                android.util.Log.d(TAG, "重排 $us us / ${candidates.size} 个等长候选")
                diagnostic(appContext, "重排 $us us / ${candidates.size} 个等长候选", null)
                if (requestToken == token) onDone(ranked)
            } catch (t: Throwable) {
                // 推理失败就当没重排
                android.util.Log.w(TAG, "重排失败", t)
                diagnostic(appContext, "重排失败", t)
            }
        }
    }

    companion object {
        private const val TAG = "ShuangpinRerank"

        /**
         * 诊断记录写到 App 私有目录。
         *
         * 有些 ROM 会过滤第三方进程的 logcat（「重排没生效」就变成无头案），
         * 所以关键信息要落到文件里——`adb shell run-as` 就能读。
         */
        private fun diagnostic(context: android.content.Context, what: String, t: Throwable?) {
            try {
                val file = java.io.File(context.filesDir, "rerank.log")
                val head = "${System.currentTimeMillis()} $what\n"
                val body = if (t == null) "" else
                    "  ${t.javaClass.name}: ${t.message}\n" +
                        (t.stackTrace.take(6).joinToString("\n") { "    at $it" }) + "\n"
                file.appendText(head + body)
            } catch (ignored: Throwable) {
                // 诊断本身失败就算了
            }
        }
    }

    /** 释放。 */
    fun release() {
        executor.shutdownNow()
    }
}
