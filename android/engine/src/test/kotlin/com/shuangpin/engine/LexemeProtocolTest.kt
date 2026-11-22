package com.shuangpin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 直连厂商时的**协议选择**与**鉴权**。
 *
 * 这两条守着两个曾经让「直连厂商」完全用不了的 bug：
 *
 * 1. **API key 从来没发出去** —— 客户端里根本没写 `Authorization` / `x-api-key`，
 *    厂商一律回 401。用户填了 key、填了模型，看起来配得挺好，就是连不上。
 * 2. **地址里带 `/anthropic` 时还在打 OpenAI 的 `/chat/completions`** → 404。
 *    MiniMax、Claude 这类地址长这样，路径完全不一样。
 */
class LexemeProtocolTest {

    /**
     * 起一个只收一次连接的假服务，把**请求行 + 请求头**记下来。
     *
     * 用裸 socket 而不是 JDK 的 HttpServer：后者 + HttpURLConnection 会出现
     * 「请求被重发一次」的怪现象，测出来的请求数对不上真实情况。
     */
    private fun captureHead(pathSuffix: String, protocol: String, apiKey: String): String {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val heads = java.util.Collections.synchronizedList(ArrayList<String>())
        val worker = Thread {
            try {
                server.accept().use { s ->
                    val input = s.getInputStream()
                    val head = StringBuilder()
                    while (true) {
                        val b = input.read()
                        if (b < 0) break
                        head.append(b.toChar())
                        if (head.length >= 4 && head.substring(head.length - 4) == "\r\n\r\n") break
                    }
                    val headerText = head.toString()
                    val len = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                        .find(headerText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val buf = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val n = input.read(buf, read, len - read)
                        if (n < 0) break
                        read += n
                    }
                    heads.add(headerText)
                    // 回一个 Anthropic 形状的响应
                    val resp = """{"content":[{"type":"text","text":"{\"next\":[\"x\"]}"}]}""".toByteArray()
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
            val done = CountDownLatch(1)
            LexemeClient("http://127.0.0.1:${server.localPort}$pathSuffix", protocol = protocol)
                .request(LexemeRequest(text = "测", model = "m", apiKey = apiKey)) { done.countDown() }
            done.await(10, TimeUnit.SECONDS)
        } finally {
            server.close()
        }
        return heads.firstOrNull()?.substringBefore("\r\n\r\n") ?: ""
    }

    @Test
    fun `地址带 anthropic 时走 Anthropic 的路径`() {
        val head = captureHead("/anthropic", LexemeClient.PROTOCOL_OPENAI, "sk-test")
        assertTrue(
            "应该打 /anthropic/v1/messages，实际请求：$head",
            head.startsWith("POST /anthropic/v1/messages"),
        )
    }

    @Test
    fun `直连 OpenAI 兼容时走 chat completions`() {
        val head = captureHead("/v1", LexemeClient.PROTOCOL_OPENAI, "sk-test")
        assertTrue(
            "应该打 /v1/chat/completions，实际请求：$head",
            head.startsWith("POST /v1/chat/completions"),
        )
    }

    @Test
    fun `显式选 Anthropic 协议时走 messages 路径`() {
        val head = captureHead("", LexemeClient.PROTOCOL_ANTHROPIC, "sk-test")
        assertTrue(
            "应该打 /v1/messages，实际请求：$head",
            head.startsWith("POST /v1/messages"),
        )
    }

    @Test
    fun `Anthropic 协议带 x-api-key 头`() {
        val head = captureHead("/anthropic", LexemeClient.PROTOCOL_OPENAI, "sk-test-key")
        assertTrue("应带 x-api-key，实际请求：$head", head.contains("x-api-key: sk-test-key"))
        assertTrue("应带 anthropic-version，实际请求：$head", head.contains("anthropic-version:"))
    }

    @Test
    fun `OpenAI 协议带 Bearer 头`() {
        val head = captureHead("/v1", LexemeClient.PROTOCOL_OPENAI, "sk-test-key")
        assertTrue(
            "应带 Authorization: Bearer，实际请求：$head",
            head.contains("Authorization: Bearer sk-test-key"),
        )
    }

    @Test
    fun `Anthropic 形状的响应能解析出候选`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            try {
                server.accept().use { s ->
                    val input = s.getInputStream()
                    val head = StringBuilder()
                    while (true) {
                        val b = input.read()
                        if (b < 0) break
                        head.append(b.toChar())
                        if (head.length >= 4 && head.substring(head.length - 4) == "\r\n\r\n") break
                    }
                    val resp = """{"content":[{"type":"text","text":"{\"next\":[\"智慧\"]}"}]}""".toByteArray()
                    val out = s.getOutputStream()
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${resp.size}\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    out.write(resp)
                    out.flush()
                }
            } catch (ignored: Throwable) {
            }
        }
        worker.isDaemon = true
        worker.start()
        var got: List<String>? = null
        try {
            val done = CountDownLatch(1)
            LexemeClient(
                "http://127.0.0.1:${server.localPort}/anthropic",
                protocol = LexemeClient.PROTOCOL_OPENAI,
            ).request(LexemeRequest(text = "测", model = "m", apiKey = "k")) {
                got = it
                done.countDown()
            }
            done.await(10, TimeUnit.SECONDS)
        } finally {
            server.close()
        }
        assertEquals(listOf("智慧"), got)
    }
}
