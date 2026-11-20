package com.dsh.deepseek.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dsh.deepseek.AppContainer
import com.dsh.deepseek.DeepSeekApplication
import com.dsh.deepseek.core.common.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 孤儿附件清理：删除目录中已无数据库记录引用的文件（§4.1 WorkManager 用途）。
 */
class AttachmentCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val container = (applicationContext as DeepSeekApplication).container
            val referenced = container.chatRepository.getAllAttachments()
                .mapNotNull { it.localPath }
                .toSet()
            container.attachmentManager.cleanupOrphansBlocking(referenced)
            Result.success()
        } catch (e: Exception) {
            AppLog.w("Cleanup", "附件清理失败: ${e.message}")
            Result.retry()
        }
    }
}
