package com.david.deepdesk.feature.history

import com.david.deepdesk.core.database.ConversationSummaryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ConversationGroupsTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 11, 18)
    private val now = today.atTime(LocalTime.of(15, 0)).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun groupsByOfficialDateBuckets() {
        val items = listOf(
            row("today", today),
            row("yesterday", today.minusDays(1)),
            row("six-days", today.minusDays(6)),
            row("twenty", today.minusDays(20)),
            row("old", today.minusDays(40)),
        )
        val groups = groupConversationsByDate(items, now, zone)
        assertEquals(listOf("今天", "昨天", "7 天内", "30 天内", "更早"), groups.map { it.label })
        assertEquals(listOf("today"), groups[0].conversations.map { it.id })
        assertEquals(listOf("yesterday"), groups[1].conversations.map { it.id })
        assertEquals(listOf("six-days"), groups[2].conversations.map { it.id })
        assertEquals(listOf("twenty"), groups[3].conversations.map { it.id })
        assertEquals(listOf("old"), groups[4].conversations.map { it.id })
    }

    @Test
    fun omitsEmptyBucketsAndKeepsOrder() {
        val items = listOf(
            row("a", today),
            row("b", today),
            row("c", today.minusDays(40)),
        )
        val groups = groupConversationsByDate(items, now, zone)
        assertEquals(listOf("今天", "更早"), groups.map { it.label })
        assertEquals(listOf("a", "b"), groups[0].conversations.map { it.id })
    }

    @Test
    fun emptyInput() {
        assertTrue(groupConversationsByDate(emptyList(), now, zone).isEmpty())
    }

    @Test
    fun pinnedGroupComesFirst() {
        val items = listOf(
            row("today", today, pinned = false),
            row("pin-old", today.minusDays(40), pinned = true),
            row("pin-today", today, pinned = true),
        )
        val groups = groupConversations(items, now, zone)
        assertEquals(listOf("置顶", "今天"), groups.map { it.label })
        assertEquals(listOf("pin-old", "pin-today"), groups[0].conversations.map { it.id })
        assertEquals(listOf("today"), groups[1].conversations.map { it.id })
    }

    @Test
    fun noPinnedOmitsPinnedBucket() {
        val items = listOf(row("today", today))
        val groups = groupConversations(items, now, zone)
        assertEquals(listOf("今天"), groups.map { it.label })
    }

    private fun row(id: String, date: LocalDate, pinned: Boolean = false) = ConversationSummaryRow(
        id = id,
        title = id,
        updatedAt = date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli(),
        pinned = pinned,
        pinnedAt = if (pinned) 1L else 0L,
    )
}
