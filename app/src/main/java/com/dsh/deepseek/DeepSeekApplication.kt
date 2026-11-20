package com.dsh.deepseek

import android.app.Application
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dsh.deepseek.core.common.AppLog
import com.dsh.deepseek.feature.chat.data.ChatRepository
import com.dsh.deepseek.worker.AttachmentCleanupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class DeepSeekApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 进程回收恢复：未完结 SSE 标记为 INTERRUPTED（不自动重发，避免二次扣费 §6.3）
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { container.chatRepository.markAllSessionsInterrupted() }
                .onFailure { AppLog.w("App", "中断标记失败: ${it.message}") }
        }

        // 孤儿附件周期清理（WorkManager 仅用于可重试任务，§7.2）
        WorkManager.getInstance(this).enqueue(
            PeriodicWorkRequestBuilder<AttachmentCleanupWorker>(7, TimeUnit.DAYS).build()
        )
    }
}
