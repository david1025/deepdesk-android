package com.dsh.deepseek.feature.attachments

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.dsh.deepseek.core.common.AppLog
import com.dsh.deepseek.core.common.AppError
import com.dsh.deepseek.core.common.TimeProvider
import com.dsh.deepseek.core.model.ApiProtocol
import com.dsh.deepseek.core.model.Attachment
import com.dsh.deepseek.core.model.UploadState
import com.dsh.deepseek.core.network.DeepSeekApiException
import com.dsh.deepseek.core.network.FilesApiClient
import java.io.File
import java.io.IOException

/**
 * 附件管理器（F07/F08/F14）：
 * - SAF / Photo Picker URI → ContentResolver 转存 app 私有目录
 * - 分类判定 purpose 与大小限制；超限文件不上传（§9 413 预检）
 * - 上传统一走 Anthropic Files 路径（与 Messages 的 file_id 引用同协议）
 * - OCR 为附件显式辅助动作，不在普通上传时自动触发
 */
class AttachmentManager(
    private val context: Context,
    private val filesClient: FilesApiClient,
    private val time: TimeProvider,
) {

    /** 读取 URI 元数据并转存私有目录；返回待上传附件记录 */
    fun importBlocking(
        uri: Uri,
        sessionId: String,
        displayNameHint: String? = null,
    ): Attachment {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(resolver, uri) ?: displayNameHint
            ?: "attachment_${time.nowMillis()}"

        val mimeHint = resolver.getType(uri)
        val cls = AttachmentTypeResolver.classify(displayName, mimeHint)
        if (cls.category == AttachmentTypeResolver.Category.UNSUPPORTED) {
            throw AppError(AppError.Kind.LOCAL, "不支持的文件类型：${displayName.substringAfterLast('.', "未知")}")
        }

        val id = time.newId()
        val dir = File(context.filesDir, "attachments/$sessionId").apply { mkdirs() }
        val safeName = "${id}_${displayName.replace(Regex("[/\\\\:*?\"<>|]"), "_")}"
        val dest = File(dir, safeName)

        try {
            resolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: throw AppError(AppError.Kind.LOCAL, "无法读取所选文件")
        } catch (e: IOException) {
            dest.delete()
            throw AppError(AppError.Kind.LOCAL, "文件读取失败")
        }

        val size = dest.length()
        if (size > cls.maxBytes) {
            dest.delete()
            throw AppError(AppError.Kind.PAYLOAD_TOO_LARGE, AttachmentTypeResolver.friendlyLimit(cls))
        }

        return Attachment(
            id = id,
            sessionId = sessionId,
            localPath = dest.absolutePath,
            localUri = uri.toString(),
            mimeType = cls.mimeType,
            displayName = displayName,
            sizeBytes = size,
            purpose = cls.purpose,
            apiProtocol = ApiProtocol.ANTHROPIC_FILES,
            uploadState = UploadState.LOCAL,
            isImage = cls.isImage,
            createdAt = time.nowMillis(),
        )
    }

    /** 上传单个附件（阻塞），进度回调由调用方节流更新 UI */
    fun uploadBlocking(attachment: Attachment, onProgress: (Int) -> Unit): String {
        val file = attachment.localPath?.let(::File)
            ?: throw AppError(AppError.Kind.LOCAL, "本地文件不存在")
        val response = filesClient.uploadBlocking(
            file = file,
            mimeType = attachment.mimeType,
            fileName = attachment.displayName,
            purpose = attachment.purpose,
            onProgress = { written, total ->
                if (total > 0) onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
            },
        )
        return response.id
    }

    /** 删除本地副本（会话删除级联清理由外层负责） */
    fun deleteLocalFile(attachment: Attachment) {
        attachment.localPath?.let { File(it).delete() }
    }

    /** 孤儿清理：目录中存在但数据库无记录的文件（WorkManager 周期任务调用） */
    fun cleanupOrphansBlocking(referencedPaths: Set<String>): Int {
        val root = File(context.filesDir, "attachments")
        if (!root.exists()) return 0
        var removed = 0
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            if (f.absolutePath !in referencedPaths) {
                if (f.delete()) removed++
            }
        }
        if (removed > 0) AppLog.i(TAG, "孤儿附件清理: $removed 个")
        return removed
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "AttachmentMgr"

        fun toAppError(e: Exception): AppError = when (e) {
            is AppError -> e
            is DeepSeekApiException -> e.toAppError()
            else -> AppError(AppError.Kind.UNKNOWN, e.message ?: "附件操作失败")
        }
    }
}
