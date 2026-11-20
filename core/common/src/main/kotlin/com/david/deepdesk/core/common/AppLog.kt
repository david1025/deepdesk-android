package com.david.deepdesk.core.common

import android.util.Log

/**
 * 统一日志出口。
 * 安全约束（设计文档 §8）：API Key、原始音频、全文对话内容禁止进入日志；
 * 调用方只允许记录错误类别、request_id、状态码等诊断字段。
 */
object AppLog {
    private const val PREFIX = "DSH/"

    fun d(tag: String, message: String) {
        Log.d(PREFIX + tag, message.take(MAX_LEN))
    }

    fun i(tag: String, message: String) {
        Log.i(PREFIX + tag, message.take(MAX_LEN))
    }

    fun w(tag: String, message: String, tr: Throwable? = null) {
        Log.w(PREFIX + tag, message.take(MAX_LEN), tr)
    }

    fun e(tag: String, message: String, tr: Throwable? = null) {
        Log.e(PREFIX + tag, message.take(MAX_LEN), tr)
    }

    private const val MAX_LEN = 2000
}
