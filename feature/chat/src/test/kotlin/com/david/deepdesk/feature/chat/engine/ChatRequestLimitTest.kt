package com.david.deepdesk.feature.chat.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRequestLimitTest {
    @Test
    fun recognizesRequestLimitsFromHttpAndStreamErrors() {
        assertEquals(ChatRequestLimit.CONTEXT, classify(400, "Maximum context length exceeded."))
        assertEquals(ChatRequestLimit.CONTEXT, classify(null, "Input exceeds the context window."))
        assertEquals(ChatRequestLimit.DOCUMENT_COUNT, classify(400, "At most 20 documents are allowed."))
        assertEquals(ChatRequestLimit.DOCUMENT_TEXT, classify(400, "Total document text exceeds 8 MiB."))
        assertEquals(ChatRequestLimit.FILE_UNAVAILABLE, classify(404, "File not found."))
        assertNull(classify(400, "Invalid image file format."))
        assertNull(classify(503, "File not found."))
    }

    @Test
    fun retainsRecoveryActionInSavedMessage() {
        for (limit in ChatRequestLimit.entries) {
            assertEquals(limit, ChatRequestLimit.fromBlock(limit.toBlock()))
        }
    }

    private fun classify(status: Int?, message: String) =
        ChatRequestLimit.fromFailure(MessageEventReducer.StreamFailure(httpStatus = status, message = message))
}
