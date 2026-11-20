package com.dsh.deepseek.core.common

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random

/**
 * 时间与 ID 生成统一出口，便于测试注入固定值。
 * 会话/消息 ID 采用时间有序的 32 位 hex，保证 ZIP v3 校验规则
 * “父消息 ID 必须小于子消息 ID”在时间顺序创建时天然成立。
 */
interface TimeProvider {
    fun nowMillis(): Long
    fun newId(): String
    fun isoUtc(millis: Long): String
}

class DefaultTimeProvider : TimeProvider {

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun newId(): String {
        // 20 位毫秒时间戳 hex + 12 位随机，字典序 == 时间序
        val ts = java.lang.Long.toHexString(System.currentTimeMillis())
            .padStart(16, '0')
        val rand = StringBuilder()
        repeat(12) { rand.append(HEX[Random.nextInt(HEX.length)]) }
        return ts + rand
    }

    override fun isoUtc(millis: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(millis))

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
