package com.dsh.deepseek.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId

class TokenUsageTest {

    private val beijing = ZoneId.of("Asia/Shanghai")

    @Test
    fun `anthropic style input tokens are cache miss`() {
        val usage = UsageInfo(inputTokens = 100, outputTokens = 20, cacheReadTokens = 0)
        val b = TokenAccounting.from(usage)
        assertEquals(0, b.cacheHitTokens)
        assertEquals(100, b.cacheMissTokens)
        assertEquals(20, b.outputTokens)
    }

    @Test
    fun `openai style input includes cache hit`() {
        val usage = UsageInfo(inputTokens = 100, outputTokens = 10, cacheReadTokens = 80)
        val b = TokenAccounting.from(usage)
        assertEquals(80, b.cacheHitTokens)
        assertEquals(20, b.cacheMissTokens)
    }

    @Test
    fun `explicit miss field wins`() {
        val usage = UsageInfo(
            inputTokens = 100,
            outputTokens = 1,
            cacheReadTokens = 40,
            promptCacheMissTokens = 55,
        )
        val b = TokenAccounting.from(usage)
        assertEquals(40, b.cacheHitTokens)
        assertEquals(55, b.cacheMissTokens)
    }

    @Test
    fun `flash off-peak estimate uses official CNY rates`() {
        val weekend = ZonedDateTime.of(2026, 11, 15, 12, 0, 0, 0, beijing).toInstant().toEpochMilli()
        assertFalse(TokenPricing.isPeak(weekend))
        val cny = TokenPricing.estimateCny(
            TokenBreakdown(cacheHitTokens = 1_000_000, cacheMissTokens = 1_000_000, outputTokens = 1_000_000),
            "deepseek-flash",
            weekend,
        )
        assertEquals(0.02 + 1.0 + 4.0, cny, 1e-9)
    }

    @Test
    fun `flash peak weekday beijing morning window`() {
        val peak = ZonedDateTime.of(2026, 11, 17, 10, 0, 0, 0, beijing).toInstant().toEpochMilli()
        assertTrue(TokenPricing.isPeak(peak))
        val cny = TokenPricing.estimateCny(
            TokenBreakdown(cacheHitTokens = 0, cacheMissTokens = 1_000_000, outputTokens = 0),
            "deepseek-flash",
            peak,
        )
        assertEquals(2.0, cny, 1e-9)
    }

    @Test
    fun `weekday noon break is off-peak`() {
        val noon = ZonedDateTime.of(2026, 11, 17, 13, 0, 0, 0, beijing).toInstant().toEpochMilli()
        assertFalse(TokenPricing.isPeak(noon))
    }

    @Test
    fun `session totals keep a single cost and period flags`() {
        val mixed = TokenUsageTotals(
            cacheMissTokens = 1_500_000,
            outputTokens = 100_000,
            estimatedCostCnyMicros = 2_900_000,
            requestCount = 3,
            hasPeak = true,
            hasOffPeak = true,
        )
        assertTrue(mixed.hasPeak)
        assertTrue(mixed.hasOffPeak)
        assertEquals(2.9, mixed.estimatedCostCny, 1e-9)

        val offOnly = TokenUsageTotals(
            cacheMissTokens = 100,
            estimatedCostCnyMicros = 100,
            requestCount = 1,
            hasOffPeak = true,
        )
        assertTrue(offOnly.hasOffPeak)
        assertFalse(offOnly.hasPeak)
        assertEquals(100L, offOnly.estimatedCostCnyMicros)
    }
}
