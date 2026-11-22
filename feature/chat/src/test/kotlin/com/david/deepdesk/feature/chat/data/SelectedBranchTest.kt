package com.david.deepdesk.feature.chat.data

import com.david.deepdesk.core.database.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedBranchTest {
    private fun message(id: String, parent: String? = null, selected: String? = null) = MessageEntity(
        id = id, sessionId = "session", role = "user", parentId = parent,
        selectedChildId = selected, status = null, model = null, createdAt = 0,
    )

    @Test fun restoresSelectedDescendantsOfEachVersion() {
        val messages = listOf(
            message("original", selected = "answer1"), message("answer1", "original", "followup"),
            message("followup", "answer1"), message("edited", selected = "answer2"),
            message("answer2", "edited"),
        )
        assertEquals("followup", selectedBranchLeaf(messages, "original"))
        assertEquals("answer2", selectedBranchLeaf(messages, "edited"))
    }

    @Test fun fallsBackToNewestChildAndHandlesMissingNode() {
        val messages = listOf(message("root"), message("old", "root"), message("new", "root"))
        assertEquals("new", selectedBranchLeaf(messages, "root"))
        assertNull(selectedBranchLeaf(messages, "missing"))
    }
}
