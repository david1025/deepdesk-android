package com.david.deepdesk.feature.history

import androidx.room.withTransaction
import com.david.deepdesk.core.common.TimeProvider
import com.david.deepdesk.core.database.AppDatabase
import com.david.deepdesk.core.database.AttachmentEntity
import com.david.deepdesk.core.database.BlockEntity
import com.david.deepdesk.core.database.ConversationEntity
import com.david.deepdesk.core.database.MessageEntity
import com.david.deepdesk.core.model.ReplyStatus
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 历史记录 ZIP 归档 Importer/Exporter（F16）。
 * 对齐官网 / chat-demo v3：
 * - manifest.json（按文件名查找，不要求是首个条目）
 * - 会话 JSON 使用 fragments（REQUEST/RESPONSE/THINK/FILE/TOOL_*）
 * - 同时兼容本客户端旧备份的 content/thinking 字段
 * - 导入校验消息树后单事务替换；失败回滚
 * - 不包含 API Key、设置与非图片附件原件（PDF 等只保留文件名、大小和图标）
 */
class HistoryImporterExporter(
    private val db: AppDatabase,
    private val time: TimeProvider,
) {

    /** 导出全部会话到 ZIP；返回生成的文件（调用方负责分享/保存）。阻塞 IO，须在 IO 协程调用 */
    suspend fun exportBlocking(outFile: File): File {
        val conversations = db.conversationDao().getAll()
        ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(HistoryZipFormat.encodeManifestJson().toByteArray())
            zip.closeEntry()

            val index = mutableListOf<HistoryZipFormat.IndexEntry>()

            conversations.forEach { conv ->
                val messages = db.messageDao().getBySession(conv.id)
                val messageIds = messages.map { it.id }
                val blocks = if (messageIds.isEmpty()) {
                    emptyMap()
                } else {
                    db.blockDao().getByMessages(messageIds).groupBy { it.messageId }
                }
                val attachments = db.attachmentDao().getBySession(conv.id)

                val exportedMessages = messages.map { msg ->
                    val exportedId = HistoryZipFormat.unscopeStoredId(conv.id, msg.id)
                    val msgBlocks = blocks[msg.id].orEmpty().sortedBy { it.blockIndex }.map { b ->
                        HistoryZipFormat.Block(
                            type = b.type,
                            text = b.text,
                            signature = b.signature,
                            durationMs = b.durationMs,
                            toolUseId = b.toolUseId,
                            name = b.name,
                            inputJson = b.inputJson,
                            rawJson = b.rawJson,
                            citationsJson = b.citationsJson,
                        )
                    }
                    val files = attachments.filter { it.messageId == msg.id }.map { att ->
                        val exportedFileId = HistoryZipFormat.unscopeStoredId(conv.id, att.id)
                        val localPath = att.localPath
                        val exportPath = if (att.isImage && localPath != null && File(localPath).exists()) {
                            "chat_sessions/${conv.id}_files/${exportedFileId}_${att.displayName}"
                        } else {
                            null
                        }
                        HistoryZipFormat.FileRef(
                            id = exportedFileId,
                            fileName = att.displayName,
                            size = att.sizeBytes,
                            isImage = att.isImage,
                            exportPath = exportPath,
                            mimeType = att.mimeType,
                        )
                    }
                    HistoryZipFormat.Message(
                        id = exportedId,
                        parentId = msg.parentId?.let { HistoryZipFormat.unscopeStoredId(conv.id, it) },
                        role = msg.role,
                        status = mapStatusOut(msg.status),
                        createdAt = msg.createdAt,
                        edited = msg.edited,
                        blocks = msgBlocks,
                        files = files,
                    )
                }

                val session = HistoryZipFormat.Session(
                    id = conv.id,
                    title = conv.title,
                    createdAt = conv.createdAt,
                    updatedAt = conv.updatedAt,
                    currentMessageId = conv.activeMessageId?.let { HistoryZipFormat.unscopeStoredId(conv.id, it) },
                    messages = exportedMessages,
                )
                zip.putNextEntry(ZipEntry("chat_sessions/${conv.id}.json"))
                zip.write(HistoryZipFormat.encodeSessionJson(session, time::isoUtc).toByteArray())
                zip.closeEntry()

                attachments.filter { it.isImage }.forEach { att ->
                    val local = att.localPath?.let(::File)
                    if (local != null && local.exists()) {
                        zip.putNextEntry(ZipEntry("chat_sessions/${conv.id}_files/${att.id}_${att.displayName}"))
                        local.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }

                index.add(
                    HistoryZipFormat.IndexEntry(
                        id = conv.id,
                        title = conv.title,
                        updatedAt = time.isoUtc(conv.updatedAt),
                    ),
                )
            }

            zip.putNextEntry(ZipEntry("chat_session_index.json"))
            zip.write(HistoryZipFormat.encodeIndexJson(index).toByteArray())
            zip.closeEntry()
        }
        return outFile
    }

    private fun mapStatusOut(status: String?): String? = when (status) {
        ReplyStatus.DONE.name -> "FINISHED"
        ReplyStatus.STOPPED.name, ReplyStatus.INTERRUPTED.name -> "INCOMPLETE"
        ReplyStatus.ERROR.name -> "FAILED"
        ReplyStatus.REFUSED.name -> "CONTENT_FILTER"
        ReplyStatus.STREAMING.name, ReplyStatus.PENDING.name -> "WIP"
        else -> status
    }

    @kotlinx.serialization.Serializable
    data class ImportReport(val conversations: Int, val messages: Int, val attachments: Int)

    /**
     * 导入 ZIP（替换全部本地数据）：
     * 校验 manifest 版本与消息树 → 全部通过后单事务替换；失败不落库。
     */
    suspend fun importBlocking(zipFile: File): ImportReport {
        val archive = HistoryZipFormat.parseZip(zipFile)
        var messageCount = 0
        var attachmentCount = 0
        db.withTransaction {
            db.conversationDao().deleteAll()
            archive.sessions.forEach { raw ->
                val s = HistoryZipFormat.scopeMessageIds(raw)
                val selectedChild = HistoryZipFormat.selectedChildIds(s)
                db.conversationDao().upsert(
                    ConversationEntity(
                        id = s.id,
                        title = s.title.ifBlank { "新对话" },
                        createdAt = s.createdAt.takeIf { it > 0 } ?: time.nowMillis(),
                        updatedAt = s.updatedAt.takeIf { it > 0 } ?: time.nowMillis(),
                        rootMessageId = s.messages.firstOrNull { it.parentId == null }?.id,
                        activeMessageId = s.currentMessageId,
                        titleManuallyEdited = s.title.isNotBlank(),
                    ),
                )
                s.messages.forEach { m ->
                    db.messageDao().upsert(
                        MessageEntity(
                            id = m.id,
                            sessionId = s.id,
                            role = m.role,
                            parentId = m.parentId,
                            selectedChildId = selectedChild[m.id],
                            status = mapStatusIn(m.status),
                            model = null,
                            createdAt = m.createdAt.takeIf { it > 0 } ?: time.nowMillis(),
                            edited = m.edited,
                        ),
                    )
                    val blocks = m.blocks.mapIndexed { index, b ->
                        BlockEntity(
                            messageId = m.id,
                            blockIndex = index,
                            type = b.type,
                            text = b.text,
                            signature = b.signature,
                            toolUseId = b.toolUseId,
                            name = b.name,
                            inputJson = b.inputJson,
                            rawJson = b.rawJson,
                            citationsJson = b.citationsJson,
                            durationMs = b.durationMs,
                        )
                    }
                    if (blocks.isNotEmpty()) db.blockDao().insertAll(blocks)
                    messageCount++

                    m.files.forEach { f ->
                        val storedFileId = "${s.id}:${f.id}"
                        val createdAt = m.createdAt.takeIf { it > 0 } ?: time.nowMillis()
                        val bytes = if (f.isImage) imageBytesFor(archive, f) else null
                        val root = attachmentDirProvider?.invoke()
                        if (bytes != null && root != null) {
                            val dir = File(root, s.id)
                            dir.mkdirs()
                            val safe = "${storedFileId}_${f.fileName.replace(Regex("[/\\\\:*?\"<>|]"), "_")}"
                            val dest = File(dir, safe)
                            dest.writeBytes(bytes)
                            db.attachmentDao().upsert(
                                AttachmentEntity(
                                    id = storedFileId,
                                    sessionId = s.id,
                                    messageId = m.id,
                                    localPath = dest.absolutePath,
                                    localUri = null,
                                    serverFileId = null,
                                    mimeType = f.mimeType ?: guessImageMime(f.fileName),
                                    displayName = f.fileName,
                                    sizeBytes = f.size.takeIf { it > 0 } ?: bytes.size.toLong(),
                                    purpose = "user_data",
                                    apiProtocol = "ANTHROPIC_FILES",
                                    uploadState = "LOCAL",
                                    isImage = true,
                                    createdAt = createdAt,
                                ),
                            )
                        } else {
                            // 官方 ZIP 不带 PDF 等原件，只留名字和大小。卡片照样画出来。
                            db.attachmentDao().upsert(
                                AttachmentEntity(
                                    id = storedFileId,
                                    sessionId = s.id,
                                    messageId = m.id,
                                    localPath = null,
                                    localUri = null,
                                    serverFileId = null,
                                    mimeType = f.mimeType ?: guessMime(f.fileName, f.isImage),
                                    displayName = f.fileName,
                                    sizeBytes = f.size.coerceAtLeast(0L),
                                    purpose = "user_data",
                                    apiProtocol = "ANTHROPIC_FILES",
                                    uploadState = "LOCAL",
                                    isImage = f.isImage,
                                    createdAt = createdAt,
                                ),
                            )
                        }
                        attachmentCount++
                    }
                }
            }
        }
        return ImportReport(archive.sessions.size, messageCount, attachmentCount)
    }

    private fun mapStatusIn(status: String?): String? = when (status?.uppercase()) {
        "FINISHED" -> ReplyStatus.DONE.name
        "INCOMPLETE", "TIMEOUT", "CONTEXT_LENGTH_EXCEEDED" -> ReplyStatus.INTERRUPTED.name
        "FAILED" -> ReplyStatus.ERROR.name
        "CONTENT_FILTER" -> ReplyStatus.REFUSED.name
        "WIP" -> ReplyStatus.STOPPED.name
        else -> status
    }

    private fun imageBytesFor(archive: HistoryZipFormat.Archive, f: HistoryZipFormat.FileRef): ByteArray? {
        f.exportPath?.let { archive.imageBytes[it] }?.let { return it }
        return archive.imageBytes.entries.firstOrNull { (path, _) ->
            path.endsWith("/${f.id}_${f.fileName}") || path.endsWith(f.fileName)
        }?.value
    }

    private fun guessMime(name: String, image: Boolean): String {
        if (image) return guessImageMime(name)
        return when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "csv" -> "text/csv"
            else -> "application/octet-stream"
        }
    }

    private fun guessImageMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        else -> "image/jpeg"
    }

    /** 附件根目录注入（app 层设置；避免 HistoryImporterExporter 依赖 Context） */
    var attachmentDirProvider: (() -> File)? = null

    companion object {
        const val FORMAT_VERSION = HistoryZipFormat.FORMAT_VERSION
    }
}
