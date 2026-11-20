package com.david.deepdesk.feature.attachments

import android.webkit.MimeTypeMap

/**
 * 附件类型策略（§5.6）：
 * - 图片 64 MiB；PDF/Office 100 MiB；纯文本/表格/电子书 32 MiB
 * - purpose：图片/文本/表格/电子书 → user_data；PDF/Office → 文本识别或图文混排
 */
object AttachmentTypeResolver {

    enum class Category { IMAGE, DOCUMENT, TEXT_TABLE_BOOK, UNSUPPORTED }

    data class Classification(
        val category: Category,
        val mimeType: String,
        val purpose: String,
        val maxBytes: Long,
        val isImage: Boolean,
        val ocrSupported: Boolean,
    )

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp")
    private val OFFICE_EXT = setOf("pdf", "doc", "docx", "ppt", "pptx")
    private val TEXT_EXT = setOf("txt", "md", "json", "csv", "tsv", "xml", "html", "htm", "yml", "yaml", "log", "kt", "java", "py", "js", "ts", "go", "rs", "c", "cpp", "h", "sh", "sql")
    private val TABLE_BOOK_EXT = setOf("xls", "xlsx", "epub", "mobi")

    fun classify(displayName: String, providedMime: String? = null): Classification {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val mime = providedMime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                in IMAGE_EXT -> "image/${if (ext == "jpg") "jpeg" else ext}"
                "pdf" -> "application/pdf"
                "md" -> "text/markdown"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }

        return when {
            ext in IMAGE_EXT || mime.startsWith("image/") -> Classification(
                Category.IMAGE, mime, "user_data", 64L * 1024 * 1024, true, true,
            )
            ext == "pdf" -> Classification(
                Category.DOCUMENT, mime, "rich_layout_recognition", 100L * 1024 * 1024, false, true,
            )
            ext in OFFICE_EXT -> Classification(
                Category.DOCUMENT, mime, "text_recognition", 100L * 1024 * 1024, false, true,
            )
            ext in TEXT_EXT -> Classification(
                Category.TEXT_TABLE_BOOK, if (mime.startsWith("text/")) mime else "text/plain", "user_data", 32L * 1024 * 1024, false, ext in setOf("txt", "md"),
            )
            ext in TABLE_BOOK_EXT -> Classification(
                Category.TEXT_TABLE_BOOK, mime, "user_data", 32L * 1024 * 1024, false, false,
            )
            else -> Classification(
                Category.UNSUPPORTED, mime, "user_data", 0L, false, false,
            )
        }
    }

    fun friendlyLimit(cls: Classification): String = when (cls.category) {
        Category.IMAGE -> "图片最大 64 MiB"
        Category.DOCUMENT -> "文档最大 100 MiB"
        Category.TEXT_TABLE_BOOK -> "文本/表格/电子书最大 32 MiB"
        Category.UNSUPPORTED -> "不支持的文件类型"
    }
}
