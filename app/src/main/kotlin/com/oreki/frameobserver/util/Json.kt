package com.oreki.frameobserver.util

/**
 * 极简 JSON 序列化器（Kotlin 版）。
 *
 * 为什么手写而不引第三方库：
 * 1. 本工具输出 **JSONL**（每行一个 JSON），要求「单行、无换行、字段顺序稳定」，
 *    常见库默认美化换行；
 * 2. 零依赖让离线命令行构建只依赖 `android.jar`；
 * 3. 字段全部由本类控制，`null` 一律输出 JSON `null` 而不是字符串 `"null"` ——
 *    下游按 schema 解析时两者语义完全不同。
 *
 * 只做「对象 + 标量字段」这一种形状；数组用 [putRaw] 传入手工拼好的片段
 * （本工程里只有控件树的 children 用到）。
 */
class Json private constructor() {

    private val sb = StringBuilder(512)
    private var first = true

    init {
        sb.append('{')
    }

    fun put(key: String, value: String?): Json {
        key(key)
        if (value == null) sb.append("null") else quote(value)
        return this
    }

    fun put(key: String, value: Long): Json {
        key(key)
        sb.append(value)
        return this
    }

    fun put(key: String, value: Int): Json {
        key(key)
        sb.append(value)
        return this
    }

    fun put(key: String, value: Boolean): Json {
        key(key)
        sb.append(value)
        return this
    }

    /** 浮点统一保留 3 位小数：避免 0.30000000000000004 噪声，也控制日志体积。 */
    fun put(key: String, value: Double): Json {
        key(key)
        if (value.isNaN() || value.isInfinite()) sb.append("null") else sb.append(round3(value))
        return this
    }

    /** 直接塞入已构造好的 JSON 片段（对象或数组）。调用方负责片段自身合法。 */
    fun putRaw(key: String, rawJson: String?): Json {
        key(key)
        sb.append(rawJson ?: "null")
        return this
    }

    fun end(): String {
        sb.append('}')
        return sb.toString()
    }

    override fun toString(): String = end()

    private fun key(k: String) {
        if (!first) sb.append(',')
        first = false
        quote(k)
        sb.append(':')
    }

    private fun quote(s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    // 控制字符必须转义，否则 JSONL 会被解析器拒绝；中文保持原样（UTF-8）。
                    if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    companion object {
        @JvmStatic
        fun obj(): Json = Json()

        private fun round3(v: Double): String {
            val scaled = Math.round(v * 1000.0)
            return (scaled / 1000L).toString() + "." +
                String.format("%03d", Math.abs(scaled % 1000L))
        }

        /**
         * 标准 JSON 字符串字面量（含两端引号）。
         *
         * 与 [Json.put] 的区别：那个生成的是 `{"v":...}` 包装对象（给旧端点拼响应体用），
         * 这里要的是裸字符串值 —— 直接拼进 `{"tag":...}` 才不会变成 `{"tag":{"v":"x"}}`。
         */
        @JvmStatic
        fun quoteRaw(s: String?): String {
            if (s == null) return "null"
            val b = StringBuilder(s.length + 2)
            b.append('"')
            for (c in s) {
                when (c) {
                    '"' -> b.append("\\\"")
                    '\\' -> b.append("\\\\")
                    '\n' -> b.append("\\n")
                    '\r' -> b.append("\\r")
                    '\t' -> b.append("\\t")
                    else ->
                        if (c < ' ') b.append(String.format("\\u%04x", c.code)) else b.append(c)
                }
            }
            b.append('"')
            return b.toString()
        }
    }
}
