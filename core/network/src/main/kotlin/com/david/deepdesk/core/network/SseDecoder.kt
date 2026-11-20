package com.david.deepdesk.core.network

/**
 * SSE（Server-Sent Events）解码器，符合 WHATWG SSE 规范子集：
 * - 以空行（\n、\r\n、\r）分帧；一帧内多个 `data:` 行以 \n 连接
 * - 注释行（以 `:` 开头，含服务端心跳）忽略
 * - `event:` 字段忽略（本协议以 JSON body.type 判别）
 * - `retry` 忽略；流以 EOF 结束时若还有未派发帧则派发
 *
 * 纯函数实现（字符串 -> 帧列表），网络流按行喂入 [push]/[flush]，
 * 单元测试覆盖跨包断行、心跳、多行 data 等场景（设计文档 §12 风险项）。
 */
class SseDecoder {

    private val dataLines = mutableListOf<String>()

    /** 喂入一行（不含行终止符）；返回该行触发的完整事件（仅空行会触发） */
    fun push(line: String): String? {
        when {
            line.isEmpty() || line == "\r" -> return dispatch()
            line.startsWith(":") -> return null // 注释/心跳
            line.startsWith("data:") -> {
                val value = line.removePrefix("data:").removePrefix(" ")
                dataLines.add(value)
            }
            // event:/id:/retry: 及未知字段——本协议不需要
        }
        return null
    }

    /** 流结束时调用：派发残余帧 */
    fun flush(): String? = dispatch()

    private fun dispatch(): String? {
        if (dataLines.isEmpty()) return null
        val event = dataLines.joinToString("\n")
        dataLines.clear()
        return event
    }

    companion object {
        /**
         * 便捷实现：将任意 SSE 文本（含 \r\n 混排）解析为事件帧列表，测试用。
         */
        fun parseAll(text: String): List<String> {
            val decoder = SseDecoder()
            val events = mutableListOf<String>()
            var segment = StringBuilder()
            var i = 0
            fun feedLine(line: String) {
                decoder.push(line)?.let(events::add)
            }
            while (i < text.length) {
                when (val c = text[i]) {
                    '\n' -> {
                        feedLine(segment.toString())
                        segment = StringBuilder()
                        i++
                    }
                    '\r' -> {
                        feedLine(segment.toString())
                        segment = StringBuilder()
                        i++
                        if (i < text.length && text[i] == '\n') i++
                    }
                    else -> {
                        segment.append(c)
                        i++
                    }
                }
            }
            if (segment.isNotEmpty()) feedLine(segment.toString())
            decoder.flush()?.let(events::add)
            return events
        }
    }
}
