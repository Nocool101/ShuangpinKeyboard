package com.shuangpin.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime 的建张量方式（1.18）。
 *
 * 这里记的是**踩过的坑**，不是凑数的用例：
 *
 * - `createTensor(env, data, shape)` 这种三参写法**不存在**，
 *   编译期会匹配到包级私有的 `(env, allocator, data)` 然后报
 *   「actual type is 'LongArray', but 'OrtAllocator!' was expected」。
 * - 数值张量走 Buffer 重载：`createTensor(env, LongBuffer/FloatBuffer.wrap(x), shape)`。
 * - **bool 没有 Buffer 重载**。二维 bool（模型的 `visible` 输入）
 *   要用嵌套的 `BooleanArray[]`——Object 版会推成二维形状 ✓。
 */
class OrtTensorTest {

    private val env = OrtEnvironment.getEnvironment()

    @Test
    fun `数值张量用 Buffer 重载`() {
        val a = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(1, 2, 3)), longArrayOf(3))
        assertEquals("3", a.info.shape.joinToString(","))

        val b = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(1, 2, 3, 4)), longArrayOf(2, 2))
        assertEquals("2,2", b.info.shape.joinToString(","))

        val c = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1f, 2f)), longArrayOf(2))
        assertEquals("2", c.info.shape.joinToString(","))
    }

    @Test
    fun `bool 张量用嵌套数组`() {
        val one = OnnxTensor.createTensor(env, booleanArrayOf(true, false, true))
        assertEquals("3", one.info.shape.joinToString(","))

        val rows = arrayOf(booleanArrayOf(true, false), booleanArrayOf(false, true))
        val two = OnnxTensor.createTensor(env, rows)
        assertEquals("2,2", two.info.shape.joinToString(","))
    }
}
