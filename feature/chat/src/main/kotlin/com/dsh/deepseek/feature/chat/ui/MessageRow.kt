package com.dsh.deepseek.feature.chat.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.annotation.DrawableRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dsh.deepseek.core.model.Attachment
import com.dsh.deepseek.core.model.ReplyBlock
import com.dsh.deepseek.core.model.ReplyStatus
import java.io.File
import com.dsh.deepseek.core.ui.DeepSeekColors
import com.dsh.deepseek.core.ui.LocalDeepSeekDark
import com.dsh.deepseek.core.ui.DsIcon
import com.dsh.deepseek.core.ui.R as UiR
import com.dsh.deepseek.feature.chat.UiMessage

/**
 * 单条消息渲染：用户靠右气泡、助手靠左全宽（对齐官方 Demo 交互）。
 * 支持长按复制、分享、编辑提问、重新生成、切换分支（F09）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    item: UiMessage,
    streaming: Boolean,
    isSpeaking: Boolean,
    onRegenerate: () -> Unit,
    onEdit: (String) -> Unit,
    onSwitchVersion: (Int) -> Unit,
    onToggleTts: () -> Unit,
    onCopied: () -> Unit = {},
    autoCollapseThinking: Boolean = true,
) {
    if (item.message.role == "user") {
        UserMessageRow(item, onEdit, onSwitchVersion, onCopied)
    } else {
        AssistantMessageRow(
            item, streaming, isSpeaking, onRegenerate, onSwitchVersion, onToggleTts, onCopied,
            autoCollapseThinking,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessageRow(
    item: UiMessage,
    onEdit: (String) -> Unit,
    onSwitchVersion: (Int) -> Unit,
    onCopied: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showEdit by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    val text = item.blocks.filterIsInstance<ReplyBlock.Text>().joinToString("\n") { it.text }
    val bubble = if (LocalDeepSeekDark.current) DeepSeekColors.UserBubbleDark else DeepSeekColors.UserBubbleLight

    val images = item.attachments.filter { it.isPreviewableImage() }
    val files = item.attachments.filterNot { it.isPreviewableImage() }
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        if (files.isNotEmpty()) {
            UserFileCards(files)
        }
        if (text.isBlank() && images.isEmpty() && !item.message.edited) return@Column
        Box {
            Surface(
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                color = bubble,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true },
                    ),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (item.message.edited) {
                        Text(
                            stringResource(UiR.string.edited_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    UserAttachments(images, onOpenImage = { previewPath = it })
                    if (text.isNotBlank()) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(UiR.string.copy)) },
                    leadingIcon = { DsIcon(UiR.drawable.ic_copy_outline, null) },
                    onClick = {
                        clipboard.setText(AnnotatedString(text))
                        onCopied()
                        showMenu = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(UiR.string.share)) },
                    leadingIcon = { DsIcon(UiR.drawable.ic_share_outline_20, null) },
                    onClick = {
                        shareText(context, text)
                        showMenu = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(UiR.string.edit)) },
                    leadingIcon = { DsIcon(UiR.drawable.ic_edit_outline_20, null) },
                    onClick = {
                        showEdit = true
                        showMenu = false
                    },
                )
                if (item.versionCount > 1) {
                    DropdownMenuItem(
                        text = { Text(stringResource(UiR.string.prev_version)) },
                        enabled = item.versionIndex > 0,
                        onClick = {
                            onSwitchVersion(-1)
                            showMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(UiR.string.next_version)) },
                        enabled = item.versionIndex < item.versionCount - 1,
                        onClick = {
                            onSwitchVersion(+1)
                            showMenu = false
                        },
                    )
                }
            }
        }
    }

    if (showEdit) {
        var editedText by remember(text) { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { showEdit = false },
            title = { Text(stringResource(UiR.string.edit_question)) },
            text = {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    minLines = 2,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showEdit = false
                    onEdit(editedText)
                }) { Text(stringResource(UiR.string.send_new_branch)) }
            },
            dismissButton = {
                TextButton(onClick = { showEdit = false }) { Text(stringResource(UiR.string.cancel)) }
            },
        )
    }

    previewPath?.let { path ->
        ImageLightbox(path = path, onDismiss = { previewPath = null })
    }
}

private fun Attachment.isPreviewableImage(): Boolean {
    val path = localPath
    return isImage && path != null && File(path).exists()
}

private fun Attachment.isPdf(): Boolean {
    val ext = displayName.substringAfterLast('.', "").lowercase()
    return ext == "pdf" || mimeType.equals("application/pdf", ignoreCase = true)
}

/** 官方用户消息上方的文件卡片：图标、截断文件名、大小。PDF 原件可以没有。 */
@Composable
private fun UserFileCards(files: List<Attachment>) {
    Row(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        files.forEach { FileAttachmentCard(it) }
    }
}

@Composable
private fun FileAttachmentCard(att: Attachment) {
    val pdf = att.isPdf()
    Row(
        modifier = Modifier
            .width(188.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (pdf) {
                Icon(
                    painter = painterResource(UiR.drawable.ic_file_pdf),
                    contentDescription = stringResource(UiR.string.file_pdf),
                    tint = Color.Unspecified,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Icon(
                    painter = painterResource(UiR.drawable.ic_file_generic),
                    contentDescription = att.displayName,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                text = att.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val size = formatAttachmentSize(att.sizeBytes)
            if (size.isNotEmpty()) {
                Text(
                    text = size,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun formatAttachmentSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        mb >= 1024 -> trimSize(mb / 1024.0) + "GB"
        mb >= 1 -> trimSize(mb) + "MB"
        bytes >= 1024 -> trimSize(bytes / 1024.0) + "KB"
        else -> "${bytes}B"
    }
}

private fun trimSize(value: Double): String {
    val pattern = if (value >= 10.0) "%.1f" else "%.2f"
    return String.format(java.util.Locale.US, pattern, value)
}

@Composable
private fun UserAttachments(
    attachments: List<Attachment>,
    onOpenImage: (String) -> Unit,
) {
    if (attachments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        attachments.forEach { att ->
            val path = att.localPath ?: return@forEach
            AsyncImage(
                model = File(path),
                contentDescription = att.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenImage(path) },
            )
        }
    }
}

@Composable
private fun ImageLightbox(path: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = File(path),
                contentDescription = stringResource(UiR.string.preview),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AssistantMessageRow(
    item: UiMessage,
    streaming: Boolean,
    isSpeaking: Boolean,
    onRegenerate: () -> Unit,
    onSwitchVersion: (Int) -> Unit,
    onToggleTts: () -> Unit,
    onCopied: () -> Unit,
    autoCollapseThinking: Boolean,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val text = item.blocks.filterIsInstance<ReplyBlock.Text>().joinToString("\n") { it.text }
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(item.message.id) {
                        detectTapGestures(onLongPress = { showMenu = true })
                    },
            ) {
                ReplyBlocks(
                    blocks = item.blocks,
                    streaming = streaming,
                    autoCollapseThinking = autoCollapseThinking,
                )
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(UiR.string.copy)) },
                    leadingIcon = { DsIcon(UiR.drawable.ic_copy_outline, null) },
                    onClick = {
                        clipboard.setText(AnnotatedString(text))
                        onCopied()
                        showMenu = false
                    },
                )
                if (item.versionCount > 1) {
                    DropdownMenuItem(
                        text = { Text(stringResource(UiR.string.prev_version)) },
                        enabled = item.versionIndex > 0,
                        onClick = {
                            onSwitchVersion(-1)
                            showMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(UiR.string.next_version)) },
                        enabled = item.versionIndex < item.versionCount - 1,
                        onClick = {
                            onSwitchVersion(+1)
                            showMenu = false
                        },
                    )
                }
            }
        }

        when {
            streaming && item.blocks.isEmpty() -> TypingIndicator()
            item.message.status == ReplyStatus.REFUSED -> RefusalCard()
            item.message.status == ReplyStatus.INTERRUPTED -> StatusBanner(
                stringResource(UiR.string.reply_interrupted), retryable = true, onRetry = onRegenerate,
            )
            item.message.status == ReplyStatus.ERROR -> StatusBanner(
                stringResource(UiR.string.generation_failed), retryable = true, onRetry = onRegenerate,
            )
            item.message.status == ReplyStatus.STOPPED -> StatusBanner(stringResource(UiR.string.generation_stopped), retryable = false)
        }

        if (!streaming) {
            val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                MessageActionIcon(
                    icon = UiR.drawable.ic_copy_outline,
                    contentDescription = stringResource(UiR.string.copy),
                    tint = iconTint,
                    onClick = {
                        clipboard.setText(AnnotatedString(text))
                        onCopied()
                    },
                )
                MessageActionIcon(
                    icon = if (isSpeaking) UiR.drawable.ic_stop_speech_outline_20 else UiR.drawable.ic_play_media_outline_20,
                    contentDescription = stringResource(if (isSpeaking) UiR.string.stop_speech else UiR.string.read_aloud),
                    tint = if (isSpeaking) DeepSeekColors.BrandBlue else iconTint,
                    onClick = onToggleTts,
                )
                MessageActionIcon(
                    icon = UiR.drawable.ic_share_outline_20,
                    contentDescription = stringResource(UiR.string.share),
                    tint = iconTint,
                    onClick = { shareText(context, text) },
                )
                Spacer(Modifier.weight(1f))
                MessageActionIcon(
                    icon = UiR.drawable.ic_regenerate_outline_24,
                    contentDescription = stringResource(UiR.string.regenerate),
                    tint = iconTint,
                    onClick = onRegenerate,
                )
            }
        }
    }
}

@Composable
private fun MessageActionIcon(
    @DrawableRes icon: Int,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .requiredSize(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
        ) {
            DsIcon(
                id = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun TypingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            "…",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun RefusalCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(UiR.string.content_refused),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
fun StatusBanner(message: String, retryable: Boolean, onRetry: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (retryable && onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(UiR.string.retry), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun shareText(context: android.content.Context, text: String) {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(UiR.string.share_chooser)))
    }
}
