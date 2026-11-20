package com.david.deepdesk.feature.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import com.david.deepdesk.core.ui.DsConfirmDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.david.deepdesk.core.ui.R as UiR
import java.io.File

/**
 * 导入 / 导出 DeepSeek 会话 ZIP（官网 v3）。
 * 导入前确认：会替换本机全部会话，不影响 API Key。
 */
@Composable
fun HistoryBackupHost(
    historyViewModel: HistoryViewModel,
    onImported: () -> Unit = {},
    content: @Composable (
        onImport: () -> Unit,
        onExport: () -> Unit,
        busy: Boolean,
    ) -> Unit,
) {
    val context = LocalContext.current
    val operation by historyViewModel.operation.collectAsState()
    var pendingImport by remember { mutableStateOf<File?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val f = File(context.cacheDir, "deepseek_export_${System.currentTimeMillis()}.zip")
        historyViewModel.export(f) { out ->
            if (out == null) {
                f.delete()
                return@export
            }
            try {
                val dest = context.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException()
                dest.use { os -> out.inputStream().use { it.copyTo(os) } }
                historyViewModel.reportExportSaved()
            } catch (e: Exception) {
                historyViewModel.reportExportFailed(e.message?.takeIf { it.isNotBlank() })
            } finally {
                f.delete()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val f = File(context.cacheDir, "deepseek_import_${System.currentTimeMillis()}.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { input.copyTo(it) }
        }
        pendingImport = f
    }

    content(
        { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
        { exportLauncher.launch("deepseek_data_${System.currentTimeMillis()}.zip") },
        operation.busy,
    )

    operation.notice?.let { notice ->
        val title = when (notice) {
            is HistoryViewModel.BackupNotice.Imported -> stringResource(UiR.string.import_success_title)
            HistoryViewModel.BackupNotice.Exported -> stringResource(UiR.string.export_success_title)
            is HistoryViewModel.BackupNotice.Failed -> stringResource(
                if (notice.importing) UiR.string.import_failed_title else UiR.string.export_failed_title,
            )
        }
        val body = when (notice) {
            is HistoryViewModel.BackupNotice.Imported -> stringResource(
                UiR.string.import_success_message,
                notice.conversations,
                notice.messages,
            )
            HistoryViewModel.BackupNotice.Exported -> stringResource(UiR.string.export_success_message)
            is HistoryViewModel.BackupNotice.Failed -> notice.detail?.takeIf { it.isNotBlank() }
                ?: stringResource(
                    if (notice.importing) UiR.string.import_failed_title else UiR.string.backup_write_failed,
                )
        }
        DsConfirmDialog(
            title = title,
            message = body,
            confirmLabel = stringResource(UiR.string.got_it),
            onConfirm = { historyViewModel.consumeOperation() },
            onDismiss = { historyViewModel.consumeOperation() },
        )
    }

    pendingImport?.let { file ->
        DsConfirmDialog(
            title = stringResource(UiR.string.import_confirm_title),
            message = stringResource(UiR.string.import_confirm_message),
            confirmLabel = stringResource(UiR.string.import_replace),
            destructive = true,
            dismissLabel = stringResource(UiR.string.cancel),
            onConfirm = {
                pendingImport = null
                historyViewModel.import(file) { ok ->
                    file.delete()
                    if (ok) onImported()
                }
            },
            onDismiss = {
                file.delete()
                pendingImport = null
            },
        )
    }
}

@Composable
fun HistoryBackupButtons(
    onImport: () -> Unit,
    onExport: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onImport, enabled = !busy) {
                Text(stringResource(UiR.string.import_chats))
            }
            OutlinedButton(onClick = onExport, enabled = !busy) {
                Text(stringResource(UiR.string.export_chats))
            }
        }
        if (busy) {
            Text(
                stringResource(UiR.string.backup_working),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            stringResource(UiR.string.backup_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
