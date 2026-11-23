package com.david.deepdesk.feature.chat.engine

import com.david.deepdesk.core.model.ReplyBlock
import kotlinx.serialization.json.JsonPrimitive

internal const val MAX_DOCUMENT_REFERENCES = 20

internal enum class ChatRequestLimit {
    CONTEXT, DOCUMENT_COUNT, DOCUMENT_TEXT, FILE_UNAVAILABLE;

    fun toBlock() = ReplyBlock.Raw(BLOCK_TYPE, JsonPrimitive(name).toString())

    companion object {
        private const val BLOCK_TYPE = "deepdesk_request_limit"

        fun fromBlock(block: ReplyBlock): ChatRequestLimit? =
            (block as? ReplyBlock.Raw)?.takeIf { it.type == BLOCK_TYPE }?.let { raw ->
                entries.firstOrNull { JsonPrimitive(it.name).toString() == raw.rawJson }
            }

        fun fromFailure(failure: MessageEventReducer.StreamFailure): ChatRequestLimit? {
            if (failure.httpStatus != null && failure.httpStatus !in listOf(400, 404, 422)) return null
            val type = failure.errorType.orEmpty().lowercase()
            val message = failure.message.orEmpty().lowercase()
            return when {
                type in setOf("context_length_exceeded", "context_window_exceeded") ||
                    contextLimit.containsMatchIn(message) -> CONTEXT
                "document" in message && documentTextLimit.containsMatchIn(message) -> DOCUMENT_TEXT
                "document" in message && documentCountLimit.containsMatchIn(message) -> DOCUMENT_COUNT
                type in setOf("file_not_found", "file_expired") ||
                    (fileReference.containsMatchIn(message) && missingFile.containsMatchIn(message)) -> FILE_UNAVAILABLE
                else -> null
            }
        }

        private val contextLimit = Regex(
            """(?:context (?:length|window)|input (?:length|tokens)).{0,80}(?:exceed|too (?:long|large)|limit|maximum)|(?:exceeds? (?:the )?|maximum )(?:model['’]s )?context (?:length|window)|prompt is too long""",
        )
        private val documentTextLimit = Regex("""8\s*mi?b|8388608|(?:document text|extracted text|parsed text).{0,60}(?:exceed|too (?:long|large))""")
        private val documentCountLimit = Regex("""too many (?:\w+ )?documents|(?:maximum|limit|at most|exceed|more than).{0,60}\b20\b""")
        private val fileReference = Regex("""\bfile(?:s|[_ -]id)?\b""")
        private val missingFile = Regex("""not found|does not exist|doesn't exist|expired|deleted|not accessible""")
    }
}
