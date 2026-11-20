package com.david.deepdesk.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** 一次请求拆开的计费用量（缓存命中 / 未命中 / 输出）。 */
data class TokenBreakdown(
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val outputTokens: Int = 0,
    val webSearchRequests: Int = 0,
) {
    val inputTokens: Int get() = cacheHitTokens + cacheMissTokens
    val totalTokens: Int get() = inputTokens + outputTokens
    val isEmpty: Boolean get() = totalTokens == 0 && webSearchRequests == 0
}

/** 会话或按日汇总。每条请求仍按当时高峰/闲时单价估算，界面只展示合计与时段标签。 */
data class TokenUsageTotals(
    val cacheHitTokens: Long = 0,
    val cacheMissTokens: Long = 0,
    val outputTokens: Long = 0,
    val webSearchRequests: Long = 0,
    val estimatedCostCnyMicros: Long = 0,
    val requestCount: Int = 0,
    val hasPeak: Boolean = false,
    val hasOffPeak: Boolean = false,
) {
    val inputTokens: Long get() = cacheHitTokens + cacheMissTokens
    val totalTokens: Long get() = inputTokens + outputTokens
    val estimatedCostCny: Double get() = estimatedCostCnyMicros / 1_000_000.0
    val cacheHitRate: Double get() = if (inputTokens == 0L) 0.0 else cacheHitTokens.toDouble() / inputTokens
    val isEmpty: Boolean get() = totalTokens == 0L && requestCount == 0
}

object TokenAccounting {
    fun from(usage: UsageInfo): TokenBreakdown {
        val output = usage.outputTokens ?: 0
        val hit = usage.cacheReadTokens ?: usage.promptCacheHitTokens ?: 0
        val input = usage.inputTokens ?: 0
        val miss = when (val explicit = usage.promptCacheMissTokens) {
            null -> if (hit > 0 && hit <= input) (input - hit).coerceAtLeast(0) else input
            else -> explicit
        }
        val searches = usage.serverToolUse?.webSearchRequests ?: 0
        return TokenBreakdown(
            cacheHitTokens = hit.coerceAtLeast(0),
            cacheMissTokens = miss.coerceAtLeast(0),
            outputTokens = output.coerceAtLeast(0),
            webSearchRequests = searches.coerceAtLeast(0),
        )
    }
}

/**
 * 官方人民币定价（元 / 百万 tokens），2026-11-19 核对
 * https://api-docs.deepseek.com/zh-cn/quick_start/pricing
 *
 * 高峰：北京时间周一至周五 9:00–12:00、14:00–18:00（不含中国法定节假日）。
 * 中国法定节假日未单独排除，高峰判定可能略宽。
 */
object TokenPricing {
    data class Rates(
        val cacheHitPerMillionCny: Double,
        val cacheMissPerMillionCny: Double,
        val outputPerMillionCny: Double,
    )

    private val FLASH_PEAK = Rates(0.04, 2.0, 8.0)
    private val FLASH_OFF = Rates(0.02, 1.0, 4.0)
    private val PRO_PEAK = Rates(0.30, 9.0, 27.0)
    private val PRO_OFF = Rates(0.15, 4.5, 13.5)

    private val BEIJING = ZoneId.of("Asia/Shanghai")

    fun isPeak(epochMillis: Long): Boolean {
        val local = Instant.ofEpochMilli(epochMillis).atZone(BEIJING)
        if (local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY) return false
        val minutes = local.hour * 60 + local.minute
        return minutes in (9 * 60) until (12 * 60) || minutes in (14 * 60) until (18 * 60)
    }

    fun ratesFor(model: String?, epochMillis: Long): Rates {
        val pro = model?.contains("pro", ignoreCase = true) == true
        val peak = isPeak(epochMillis)
        return when {
            pro && peak -> PRO_PEAK
            pro -> PRO_OFF
            peak -> FLASH_PEAK
            else -> FLASH_OFF
        }
    }

    fun estimateCny(breakdown: TokenBreakdown, model: String?, epochMillis: Long): Double {
        val rates = ratesFor(model, epochMillis)
        return breakdown.cacheHitTokens / 1_000_000.0 * rates.cacheHitPerMillionCny +
            breakdown.cacheMissTokens / 1_000_000.0 * rates.cacheMissPerMillionCny +
            breakdown.outputTokens / 1_000_000.0 * rates.outputPerMillionCny
    }

    fun cnyToMicros(cny: Double): Long = (cny * 1_000_000.0).toLong()
}

fun formatTokenCount(n: Long): String = when {
    n >= 1_000_000L -> {
        val v = n / 1_000_000.0
        if (v >= 10) "${v.toInt()}M" else String.format("%.1fM", v)
    }
    n >= 10_000L -> "${n / 1000}k"
    n >= 1_000L -> String.format("%.1fk", n / 1000.0)
    else -> n.toString()
}

fun formatCny(amount: Double): String {
    val abs = abs(amount)
    val body = when {
        amount == 0.0 -> "0.00"
        abs >= 1.0 -> String.format("%.2f", amount)
        abs >= 0.01 -> String.format("%.3f", amount)
        abs >= 0.0001 -> String.format("%.4f", amount)
        else -> "<0.0001"
    }
    return "¥$body"
}

fun formatPercent(rate: Double): String = "${(rate * 100).toInt()}%"
