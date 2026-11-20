package com.david.deepdesk.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeProviderTest {

    @Test
    fun `ID 时间有序（ZIP v3 父 ID 小于子 ID 约束）`() {
        val provider = DefaultTimeProvider()
        val first = provider.newId()
        Thread.sleep(3) // 保证毫秒推进
        val second = provider.newId()
        assertTrue(first < second)
        assertTrue(first.length == second.length)
    }

    @Test
    fun `ISO UTC 时间格式带 Z 后缀`() {
        val provider = DefaultTimeProvider()
        val iso = provider.isoUtc(1760000000000L)
        assertTrue(iso.endsWith("Z"))
        assertTrue(iso.startsWith("20"))
        // 可被 Instant 解析
        java.time.Instant.parse(iso)
    }
}
