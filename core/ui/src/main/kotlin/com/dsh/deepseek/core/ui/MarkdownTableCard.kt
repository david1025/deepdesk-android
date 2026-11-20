package com.dsh.deepseek.core.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import org.intellij.markdown.ast.ASTNode
import java.io.File

/**
 * 官方 GFM 表格：卡片顶栏「表格」+ 复制 / 下载 / 全屏。
 * 格子带横竖线，一屏正好两列，多出来的列在边框里横滑，文字在格子边上截断。
 * 全屏去掉标题，换成返回、复制、下载、旋转（GFMTableFullScreen / ic_phone_rotate_vertical_outline）。
 */
@Composable
internal fun ChatMarkdownTable(model: MarkdownComponentModel) {
    val slice = tableSlice(model.content, model.node)
    val rows = remember(slice) { parseMarkdownTable(slice) }
    if (rows.isEmpty()) return
    val copy = rememberCopyAction()
    val context = LocalContext.current
    val saved = stringResource(R.string.markdown_table_saved)
    val failed = stringResource(R.string.markdown_table_save_failed)
    val hint = LocalCenterHint.current
    var expanded by remember(slice) { mutableStateOf(false) }
    val download = {
        val ok = runCatching { saveTableCsv(context, markdownTableToCsv(slice)) }.isSuccess
        hint(if (ok) saved else failed)
    }
    TableCard(
        rows = rows,
        onCopy = { copy(slice) },
        onDownload = download,
        onExpand = { expanded = true },
    )
    if (expanded) {
        TableFullscreen(
            rows = rows,
            onCopy = { copy(slice) },
            onDownload = download,
            onDismiss = { expanded = false },
        )
    }
}

@Composable
private fun TableCard(
    rows: List<List<MarkdownTableCell>>,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onExpand: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val rule = tableRuleColor()
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(1.dp, rule, shape),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.markdown_table),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TableIconButton(R.drawable.ic_copy_outline, stringResource(R.string.markdown_table_copy), onCopy)
                    TableIconButton(R.drawable.ic_download_outline_20, stringResource(R.string.markdown_table_download), onDownload)
                    TableIconButton(R.drawable.ic_expand_outline_20, stringResource(R.string.markdown_table_expand), onExpand)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(rule))
            BorderedTable(rows, framed = false)
        }
    }
}

@Composable
private fun TableFullscreen(
    rows: List<List<MarkdownTableCell>>,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val previousOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = previousOrientation }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        Surface(
                            onClick = onDismiss,
                            shape = CircleShape,
                            color = if (LocalDeepSeekDark.current) DeepSeekColors.UserBubbleDark else DeepSeekColors.UserBubbleLight,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                DsIcon(
                                    R.drawable.ic_chevron_left_outline_20,
                                    contentDescription = stringResource(R.string.back),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    Box(Modifier.weight(1f))
                    TableIconButton(R.drawable.ic_copy_outline, stringResource(R.string.markdown_table_copy), onCopy)
                    TableIconButton(R.drawable.ic_download_outline_20, stringResource(R.string.markdown_table_download), onDownload)
                    TableIconButton(R.drawable.ic_phone_rotate_vertical_outline, stringResource(R.string.markdown_table_rotate)) {
                        val host = activity ?: return@TableIconButton
                        host.requestedOrientation = if (host.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                            previousOrientation
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    BorderedTable(rows, framed = true)
                }
            }
        }
    }
}

/**
 * 边框固定在视口上，里面按「视口宽度 / 2」排格子。多出来的列横滑，文字单行截断。
 */
@Composable
private fun BorderedTable(rows: List<List<MarkdownTableCell>>, framed: Boolean) {
    val cols = rows.maxOf { it.size }
    if (cols == 0) return
    val rule = tableRuleColor()
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val viewport = if (maxWidth == Dp.Infinity) 320.dp else maxWidth
        val gap = 1.dp
        val colWidth = if (cols <= 1) viewport else (viewport - gap) / 2
        val gridWidth = colWidth * cols + gap * (cols - 1)
        val scroll = rememberScrollState()
        val bounded = maxWidth != Dp.Infinity
        val frame = if (framed) {
            Modifier
                .clip(shape)
                .border(1.dp, rule, shape)
        } else {
            Modifier
        }
        Box(
            Modifier
                .then(if (bounded) Modifier.fillMaxWidth() else Modifier.width(viewport))
                .then(frame)
                .horizontalScroll(scroll),
        ) {
            Column(Modifier.width(gridWidth)) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(rule))
                    }
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        for (column in 0 until cols) {
                            if (column > 0) {
                                Box(Modifier.fillMaxHeight().width(1.dp).background(rule))
                            }
                            val cell = row.getOrNull(column) ?: MarkdownTableCell("", bold = false)
                            val header = index == 0
                            Box(
                                modifier = Modifier
                                    .width(colWidth)
                                    .padding(horizontal = 14.dp, vertical = 14.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text = cell.text,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp,
                                    fontWeight = if (header || cell.bold) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun tableRuleColor(): Color =
    if (LocalDeepSeekDark.current) Color(0xFF3A3B42) else Color(0xFFE3E4E8)

@Composable
private fun TableIconButton(id: Int, label: String, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color.Transparent,
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                DsIcon(id, contentDescription = label, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun rememberCopyAction(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val hint = LocalCenterHint.current
    val copied = stringResource(R.string.copied)
    return { slice ->
        clipboard.setText(AnnotatedString(slice))
        hint(copied)
    }
}

private fun tableSlice(content: String, node: ASTNode): String {
    val start = node.startOffset.coerceIn(0, content.length)
    val end = node.endOffset.coerceIn(start, content.length)
    return content.substring(start, end)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun saveTableCsv(context: Context, csv: String) {
    val name = "table-${System.currentTimeMillis()}.csv"
    val bytes = csv.toByteArray()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("downloads insert failed")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("downloads stream failed")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return
    }
    val dir = File(context.cacheDir, "tables").apply { mkdirs() }
    val file = File(dir, name)
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
