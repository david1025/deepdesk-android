package com.david.deepdesk.feature.history

import com.david.deepdesk.core.database.ConversationSummaryRow
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ConversationGroup(
    val label: String,
    val conversations: List<ConversationSummaryRow>,
)

data class DateGroupLabels(
    val pinned: String = "置顶",
    val today: String = "今天",
    val yesterday: String = "昨天",
    val last7Days: String = "7 天内",
    val last30Days: String = "30 天内",
    val earlier: String = "更早",
)

/**
 * 置顶组在前，其余按日期桶分组。
 * 空桶不输出；置顶组内顺序跟随入参（DAO 已按 pinnedAt DESC）。
 */
fun groupConversations(
    items: List<ConversationSummaryRow>,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    labels: DateGroupLabels = DateGroupLabels(),
): List<ConversationGroup> {
    val pinned = items.filter { it.pinned }
    val rest = items.filter { !it.pinned }
    val dateGroups = groupConversationsByDate(rest, nowMillis, zoneId, labels)
    return buildList {
        if (pinned.isNotEmpty()) add(ConversationGroup(labels.pinned, pinned))
        addAll(dateGroups)
    }
}

/**
 * 按官方 App 抽屉规则分组：今天 / 昨天 / 7 天内 / 30 天内 / 更早。
 * 空桶不输出；入参已按 updatedAt 降序时组内顺序保持不变。
 */
fun groupConversationsByDate(
    items: List<ConversationSummaryRow>,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    labels: DateGroupLabels = DateGroupLabels(),
): List<ConversationGroup> {
    if (items.isEmpty()) return emptyList()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val buckets = linkedMapOf(
        labels.today to mutableListOf<ConversationSummaryRow>(),
        labels.yesterday to mutableListOf(),
        labels.last7Days to mutableListOf(),
        labels.last30Days to mutableListOf(),
        labels.earlier to mutableListOf(),
    )
    items.forEach { item ->
        val date = Instant.ofEpochMilli(item.updatedAt).atZone(zoneId).toLocalDate()
        val days = ChronoUnit.DAYS.between(date, today)
        val label = when {
            days <= 0L -> labels.today
            days == 1L -> labels.yesterday
            days < 7L -> labels.last7Days
            days < 30L -> labels.last30Days
            else -> labels.earlier
        }
        buckets.getValue(label).add(item)
    }
    return buckets.mapNotNull { (label, list) ->
        list.takeIf { it.isNotEmpty() }?.let { ConversationGroup(label, it) }
    }
}
