package com.david.deepdesk.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.david.deepdesk.core.database.ConversationSummaryRow
import com.david.deepdesk.feature.chat.data.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 历史记录抽屉 ViewModel：会话列表/搜索/重命名/删除/导入导出（F09/F16）。
 */
class HistoryViewModel(
    private val repository: ChatRepository,
    private val importerExporter: HistoryImporterExporter,
) : ViewModel() {

    sealed interface BackupNotice {
        data class Imported(val conversations: Int, val messages: Int) : BackupNotice
        data object Exported : BackupNotice
        data class Failed(val importing: Boolean, val detail: String?) : BackupNotice
    }

    data class OperationState(
        val busy: Boolean = false,
        val error: String? = null,
        val notice: BackupNotice? = null,
    )

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    @OptIn(ExperimentalCoroutinesApi::class)
    val conversations: StateFlow<List<ConversationSummaryRow>> =
        _query.flatMapLatest { q ->
            val keyword = q.trim()
            if (keyword.isEmpty()) repository.observeConversations()
            else repository.searchConversations(keyword)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _operation = MutableStateFlow(OperationState())
    val operation: StateFlow<OperationState> = _operation

    fun search(text: String) {
        _query.value = text
    }

    fun newConversation(onCreated: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val conv = repository.createConversation()
            withContext(Dispatchers.Main) { onCreated(conv.id) }
        }
    }

    fun rename(id: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.rename(id, title)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(id)
        }
    }

    fun deleteMany(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { repository.delete(it) }
        }
    }

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (pinned) {
                val ok = repository.pin(id)
                if (!ok) {
                    _operation.value = OperationState(error = "最多置顶 10 个对话")
                }
            } else {
                repository.unpin(id)
            }
        }
    }

    fun setPinnedMany(ids: Collection<String>, pinned: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (pinned) {
                var blocked = false
                ids.forEach { id ->
                    if (!repository.pin(id)) blocked = true
                }
                if (blocked) {
                    _operation.value = OperationState(error = "最多置顶 10 个对话")
                }
            } else {
                ids.forEach { repository.unpin(it) }
            }
        }
    }

    /** 导出 ZIP（阻塞 IO，导入导出期间置 busy）。成功与否由调用方在写入目标位置后回报。 */
    fun export(to: File, onDone: (File?) -> Unit) {
        _operation.value = OperationState(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = importerExporter.exportBlocking(to)
                _operation.value = OperationState()
                withContext(Dispatchers.Main) { onDone(file) }
            } catch (e: Exception) {
                _operation.value = OperationState(
                    notice = BackupNotice.Failed(importing = false, detail = e.message),
                )
                withContext(Dispatchers.Main) { onDone(null) }
            }
        }
    }

    fun reportExportSaved() {
        _operation.value = OperationState(notice = BackupNotice.Exported)
    }

    fun reportExportFailed(detail: String?) {
        _operation.value = OperationState(notice = BackupNotice.Failed(importing = false, detail = detail))
    }

    fun import(from: File, onDone: (Boolean) -> Unit = {}) {
        _operation.value = OperationState(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val report = importerExporter.importBlocking(from)
                _operation.value = OperationState(
                    notice = BackupNotice.Imported(report.conversations, report.messages),
                )
                withContext(Dispatchers.Main) { onDone(true) }
            } catch (e: Exception) {
                _operation.value = OperationState(
                    notice = BackupNotice.Failed(importing = true, detail = e.message),
                )
                withContext(Dispatchers.Main) { onDone(false) }
            }
        }
    }

    fun consumeOperation() {
        _operation.value = OperationState()
    }
}
