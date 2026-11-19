package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端联想客户端的测试。
 *
 * 三件事：
 * 1. **解析**：模型回什么格式都要能读懂（这是最容易悄悄出错的地方）
 * 2. **请求体**：三个字段（上文 / 拼音 / 候选池）都要真的发出去——
 *    少发一个字段，云端就只能瞎猜
 * 3. **真打一次服务**：本地联想服务起着的时候，直接打一遍，
 *    确认请求体、响应解析、超时这套真的通
 */
class LexemeClientTest {

    // ---------------------------------------------------------------
    // 响应解析
    // ---------------------------------------------------------------

    @Test
    fun `解析正常的响应`() {
        val json = """{"next":["世界","朋友","同事"]}"""
        assertEquals(
            listOf("世界", "朋友", "同事"),
            MiniJson.parseObject(json).stringList("next"),
        )
    }

    @Test
    fun `解析空数组与缺字段`() {
        assertTrue(MiniJson.parseObject("""{"next":[]}""").stringList("next").isEmpty())
        // 没有 next 字段时，stringList 会抛（KeyError 似的行为）
        try {
            MiniJson.parseObject("""{"error":"x"}""").stringList("next")
            throw AssertionError("缺字段时应该抛")
        } catch (e: Exception) {
            // 预期：调用方要兜住
        }
    }

    @Test
    fun `解析带转义的字符串`() {
        val json = """{"next":["a\"b","c\\d","e\nf"]}"""
        assertEquals(
            listOf("a\"b", "c\\d", "e\nf"),
            MiniJson.parseObject(json).stringList("next"),
        )
    }

    // ---------------------------------------------------------------
    // 请求体
    // ---------------------------------------------------------------

    @Test
    fun `请求体带上文拼音和候选池`() {
        val bodies = captureBodies(
            LexemeRequest(
                text = "智慧",
                pinyin = "zhihui",
                candidates = listOf("智慧", "只会"),
            )
        )
        assertEquals("应该只发一个请求，实际 $bodies", 1, bodies.size)
        val body = bodies.first()
        assertTrue("缺上文：$body", body.contains("\"text\":\"智慧\""))
        assertTrue("缺拼音：$body", body.contains("\"pinyin\":\"zhihui\""))
        assertTrue("缺候选池：$body", body.contains("\"candidates\":[\"智慧\",\"只会\"]"))
        assertTrue("缺协议版本：$body", body.contains("\"schema\":2"))
    }

    @Test
    fun `请求体对特殊字符做转义`() {
        val body = captureBodies(LexemeRequest(text = "a\"b\\c")).first()
        // JSON 里的值应该是 "a\"b\\c"（引号和反斜杠都被转义）
        assertTrue("没转义：$body", body.contains(""""text":"a\"b\\c""""))
    }

    @Test
    fun `空请求不发出去`() {
        val bodies = captureBodies(LexemeRequest())
        assertEquals("空请求不该发网络，实际发了 $bodies 次", 0, bodies.size)
    }

    // ---------------------------------------------------------------
    // 真打一次服务
    // ---------------------------------------------------------------

    /** 本地联想服务（tools/lexeme_server.mjs）起着就打一遍。 */
    @Test
    fun `打本地联想服务`() {
        val base = "http://127.0.0.1:${System.getProperty("lexeme.port") ?: "8787"}"
        assumeTrue("联想服务没起，跳过", serviceAlive(base))

        var result: List<String>? = null
        var error: String? = null
        LexemeClient(base) { msg -> error = msg }
            .request(LexemeRequest(text = "你好")) { r -> result = r }

        val deadline = System.currentTimeMillis() + 30_000
        while (result == null && error == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertEquals("请求不该失败：$error", null, error)
        assertTrue("应该拿到下一词建议，实际=$result", !result.isNullOrEmpty())
        assertTrue("建议应该是 1~4 个汉字，实际=$result", result!!.all { it.length in 1..4 })
    }

    /**
     * 消歧模式：给了候选池，服务端只能从池子里挑。
     *
     * 这是「云端不能发明新词」这条规则的端到端保证。
     */
    @Test
    fun `消歧模式只从候选池里挑`() {
        val base = "http://127.0.0.1:${System.getProperty("lexeme.port") ?: "8787"}"
        assumeTrue("联想服务没起，跳过", serviceAlive(base))

        val pool = listOf("智慧", "只会", "指挥")
        var result: List<String>? = null
        var error: String? = null
        LexemeClient(base) { msg -> error = msg }
            .request(LexemeRequest(text = "智慧城市", pinyin = "zhihui", candidates = pool)) { r ->
                result = r
            }

        val deadline = System.currentTimeMillis() + 30_000
        while (result == null && error == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertEquals("请求不该失败：$error", null, error)
        assertTrue("不该有池子外的词：$result", result!!.all { pool.contains(it) })
    }

    @Test
    fun `端点不通时安静失败`() {
        var result: List<String>? = null
        var error: String? = null
        // 端口 1 上肯定没有服务
        LexemeClient("http://127.0.0.1:1", onError = { msg -> error = msg })
            .request(LexemeRequest(text = "你好")) { r -> result = r }
        val deadline = System.currentTimeMillis() + 15_000
        while (result == null && error == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertEquals("失败时结果应是 null", null, result)
        assertTrue("失败应有原因", error != null)
    }

    /**
     * 请求还在飞的时候又来一个请求——**后来的那个不能丢**。
     *
     * 上屏后的「下一词」请求常常还在飞，用户紧接着敲下一个字，
     * 这时发的「消歧」请求如果直接丢掉，功能就等于没有。
     */
    @Test
    fun `在飞时的请求排队而不是丢掉`() {
        val base = "http://127.0.0.1:${System.getProperty("lexeme.port") ?: "8787"}"
        assumeTrue("联想服务没起，跳过", serviceAlive(base))

        val client = LexemeClient(base, onError = { })
        var first: List<String>? = null
        var second: List<String>? = null
        client.request(LexemeRequest(text = "你好")) { r -> first = r }
        // 第一个还在飞：立刻发第二个
        client.request(LexemeRequest(text = "你好", pinyin = "nihao", candidates = listOf("你好"))) { r ->
            second = r
        }

        val deadline = System.currentTimeMillis() + 60_000
        while ((first == null || second == null) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertTrue("第一个请求要有结果", first != null)
        assertTrue("第二个请求不该被丢掉，实际=$second", second != null)
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    /**
     * 起一个只收一次连接的假服务，把收到的请求体记下来。
     *
     * 用裸 socket 而不是 JDK 的 HttpServer：
     * 后者 + HttpURLConnection 会出现「请求被重发一次」的怪现象，
     * 测出来的请求数对不上真实情况。裸 socket 一次连接一次请求，
     * 测出来的就是客户端真的发了什么。
     */
    private fun captureBodies(req: LexemeRequest): List<String> {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val bodies = java.util.Collections.synchronizedList(ArrayList<String>())
        val worker = Thread {
            try {
                server.accept().use { s ->
                    val input = s.getInputStream()
                    // 先把请求头读完（到空行为止）
                    val head = StringBuilder()
                    while (true) {
                        val b = input.read()
                        if (b < 0) break
                        head.append(b.toChar())
                        if (head.length >= 4 && head.substring(head.length - 4) == "\r\n\r\n") break
                    }
                    val headerText = head.toString()
                    // 按 Content-Length 读请求体
                    val len = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                        .find(headerText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val buf = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val n = input.read(buf, read, len - read)
                        if (n < 0) break
                        read += n
                    }
                    bodies.add(String(buf, 0, read, Charsets.UTF_8))
                    val resp = """{"next":["x"]}""".toByteArray()
                    val out = s.getOutputStream()
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${resp.size}\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    out.write(resp)
                    out.flush()
                }
            } catch (ignored: Throwable) {
                // 测试用的假服务，异常不用管
            }
        }
        worker.isDaemon = true
        worker.start()
        try {
            val done = java.util.concurrent.CountDownLatch(1)
            LexemeClient("http://127.0.0.1:${server.localPort}") { }
                .request(req) { done.countDown() }
            done.await(10, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            server.close()
        }
        return ArrayList(bodies)
    }

    private fun serviceAlive(base: String): Boolean = try {
        val conn = URL("$base/health").openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 1500
        val code = conn.responseCode
        conn.disconnect()
        code == 200
    } catch (t: Throwable) {
        false
    }
}
