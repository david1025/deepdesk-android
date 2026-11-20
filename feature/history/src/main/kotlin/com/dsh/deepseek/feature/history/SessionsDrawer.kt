package com.dsh.deepseek.feature.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.annotation.DrawableRes
import com.dsh.deepseek.core.ui.DeepSeekColors
import com.dsh.deepseek.core.ui.DsIcon
import com.dsh.deepseek.core.ui.LocalDeepSeekDark
import com.dsh.deepseek.core.ui.R as UiR
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dsh.deepseek.core.database.ConversationSummaryRow

/**
 * 左侧抽屉：对齐官方 App（搜索 / 日期分组 / 多选）。
 * 底栏不登录，不放头像和「本地用户」。余额做成小号胶囊，点开用量统计，三个点进设置。导入导出只放在设置里。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsDrawer(
    historyViewModel: HistoryViewModel,
    activeSessionId: String?,
    onSelectSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    balanceLabel: String? = null,
    onOpenUsage: () -> Unit = {},
) {
    val conversations by historyViewModel.conversations.collectAsState()
    val query by historyViewModel.query.collectAsState()
    val operation by historyViewModel.operation.collectAsState()
    var menuFor by remember { mutableStateOf<ConversationSummaryRow?>(null) }
    var renameFor by remember { mutableStateOf<ConversationSummaryRow?>(null) }
    var deleteFor by remember { mutableStateOf<ConversationSummaryRow?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var bulkDelete by remember { mutableStateOf(false) }

    val searching = query.trim().isNotEmpty()
    val searchResultsLabel = stringResource(UiR.string.search_results)
    val dateLabels = DateGroupLabels(
        pinned = stringResource(UiR.string.date_pinned),
        today = stringResource(UiR.string.date_today),
        yesterday = stringResource(UiR.string.date_yesterday),
        last7Days = stringResource(UiR.string.date_last_7),
        last30Days = stringResource(UiR.string.date_last_30),
        earlier = stringResource(UiR.string.date_earlier),
    )
    val groups = remember(conversations, searching, searchResultsLabel, dateLabels) {
        if (searching) {
            if (conversations.isEmpty()) emptyList()
            else listOf(ConversationGroup(searchResultsLabel, conversations))
        } else {
            groupConversations(conversations, System.currentTimeMillis(), labels = dateLabels)
        }
    }

    fun exitSelect() {
        selecting = false
        selectedIds = emptySet()
    }

    BackHandler(enabled = selecting) { exitSelect() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (selecting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(
                    if (selectedIds.isEmpty()) {
                        stringResource(UiR.string.select_chats)
                    } else {
                        stringResource(UiR.string.selected_chats, selectedIds.size)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(onClick = { exitSelect() }),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        DsIcon(
                            UiR.drawable.ic_close_outline_20,
                            contentDescription = stringResource(UiR.string.cancel_multiselect),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        } else {
            SearchField(
                value = query,
                onValueChange = { historyViewModel.search(it) },
            )
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (groups.isEmpty() && searching) {
                item(key = "empty-search") {
                    Text(
                        stringResource(UiR.string.no_matching_chats),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 24.dp),
                    )
                }
            }
            groups.forEachIndexed { index, group ->
                item(key = "header-${group.label}") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 0.dp, top = 10.dp, bottom = 4.dp),
                    ) {
                        Text(
                            group.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (!searching && !selecting && index == 0) {
                            IconButton(
                                onClick = { selecting = true },
                                modifier = Modifier.size(36.dp),
                            ) {
                                DsIcon(
                                    UiR.drawable.ic_checklist_outline_24,
                                    contentDescription = stringResource(UiR.string.multiselect),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
                items(group.conversations, key = { it.id }) { conv ->
                    val selected = conv.id in selectedIds
                    val active = conv.id == activeSessionId && !selecting
                    ConversationRow(
                        conv = conv,
                        active = active,
                        selecting = selecting,
                        checked = selected,
                        menuOpen = menuFor?.id == conv.id,
                        onClick = {
                            if (selecting) {
                                selectedIds = if (selected) selectedIds - conv.id else selectedIds + conv.id
                            } else {
                                onSelectSession(conv.id)
                            }
                        },
                        onLongClick = {
                            if (selecting) {
                                selectedIds = if (selected) selectedIds - conv.id else selectedIds + conv.id
                            } else {
                                menuFor = conv
                            }
                        },
                        onOpenMenu = { menuFor = conv },
                        onDismissMenu = { menuFor = null },
                        onRename = { renameFor = conv; menuFor = null },
                        onPin = {
                            historyViewModel.setPinned(conv.id, !conv.pinned)
                            menuFor = null
                        },
                        onMultiSelect = {
                            selecting = true
                            selectedIds = setOf(conv.id)
                            menuFor = null
                        },
                        onDelete = { deleteFor = conv; menuFor = null },
                    )
                }
            }
        }

        operation.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (selecting) {
            val selectedRows = conversations.filter { it.id in selectedIds }
            val allPinned = selectedRows.isNotEmpty() && selectedRows.all { it.pinned }
            val enabled = selectedIds.isNotEmpty()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(enabled = enabled) {
                            historyViewModel.setPinnedMany(selectedIds, pinned = !allPinned)
                            exitSelect()
                        },
                ) {
                    DsIcon(
                        if (allPinned) UiR.drawable.ic_unpin_outline_20 else UiR.drawable.ic_pin_outline_20,
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (allPinned) stringResource(UiR.string.unpin) else stringResource(UiR.string.pin),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    )
                }
                VerticalDivider(
                    modifier = Modifier.height(24.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(enabled = enabled) { bulkDelete = true },
                ) {
                    DsIcon(
                        UiR.drawable.ic_trash_outline_20,
                        contentDescription = null,
                        tint = if (enabled) DeepSeekColors.Danger else DeepSeekColors.Danger.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(UiR.string.delete),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) DeepSeekColors.Danger else DeepSeekColors.Danger.copy(alpha = 0.7f),
                    )
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
            ) {
                val dark = LocalDeepSeekDark.current
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Surface(
                        onClick = onOpenUsage,
                        shape = RoundedCornerShape(20.dp),
                        color = if (dark) DeepSeekColors.UserBubbleDark else DeepSeekColors.UserBubbleLight,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                stringResource(UiR.string.balance_tag),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                balanceLabel ?: "—",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (dark) DeepSeekColors.BrandBlueDark else DeepSeekColors.BrandBlue,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) {
                    DsIcon(
                        UiR.drawable.ic_ellipsis_outline,
                        contentDescription = stringResource(UiR.string.settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    renameFor?.let { conv ->
        var newName by remember(conv.id) { mutableStateOf(conv.title) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text(stringResource(UiR.string.rename_chat)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) historyViewModel.rename(conv.id, newName)
                    renameFor = null
                }) { Text(stringResource(UiR.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text(stringResource(UiR.string.cancel)) } },
        )
    }

    deleteFor?.let { conv ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(UiR.string.delete_chat)) },
            text = {
                Text(
                    stringResource(
                        UiR.string.delete_chat_message,
                        conv.title.ifBlank { stringResource(UiR.string.new_chat) },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    historyViewModel.delete(conv.id)
                    deleteFor = null
                }) { Text(stringResource(UiR.string.delete), color = DeepSeekColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text(stringResource(UiR.string.cancel)) } },
        )
    }

    if (bulkDelete) {
        AlertDialog(
            onDismissRequest = { bulkDelete = false },
            title = { Text(stringResource(UiR.string.delete_chat)) },
            text = { Text(stringResource(UiR.string.delete_selected_chats, selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    historyViewModel.deleteMany(selectedIds)
                    bulkDelete = false
                    exitSelect()
                }) { Text(stringResource(UiR.string.delete), color = DeepSeekColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { bulkDelete = false }) { Text(stringResource(UiR.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conv: ConversationSummaryRow,
    active: Boolean,
    selecting: Boolean,
    checked: Boolean,
    menuOpen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onMultiSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val highlight = active || menuOpen
    val titleColor = if (highlight) DeepSeekColors.BrandBlue else MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .background(
                    if (highlight) DeepSeekColors.BrandBlue.copy(alpha = 0.16f) else Color.Transparent,
                    RoundedCornerShape(14.dp),
                )
                .padding(start = 12.dp, end = if (selecting) 12.dp else 4.dp, top = 2.dp, bottom = 2.dp),
        ) {
            if (selecting) {
                SelectDot(selected = checked)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                conv.title.ifBlank { stringResource(UiR.string.new_chat) },
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            )
            if (!selecting) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .clickable(onClick = onOpenMenu),
                    ) {
                        DsIcon(
                            UiR.drawable.ic_ellipsis_outline,
                            contentDescription = stringResource(UiR.string.more),
                            tint = if (highlight) DeepSeekColors.BrandBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onDismissMenu,
            offset = DpOffset(x = 36.dp, y = (-4).dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(min = 156.dp),
        ) {
            SessionMenuItem(
                icon = UiR.drawable.ic_edit_outline_20,
                label = stringResource(UiR.string.rename_chat),
                onClick = onRename,
            )
            SessionMenuDivider()
            SessionMenuItem(
                icon = if (conv.pinned) UiR.drawable.ic_unpin_outline_20 else UiR.drawable.ic_pin_outline_20,
                label = if (conv.pinned) stringResource(UiR.string.unpin) else stringResource(UiR.string.pin),
                onClick = onPin,
            )
            SessionMenuDivider()
            SessionMenuItem(
                icon = UiR.drawable.ic_checklist_outline_24,
                label = stringResource(UiR.string.multiselect),
                onClick = onMultiSelect,
            )
            SessionMenuDivider()
            SessionMenuItem(
                icon = UiR.drawable.ic_trash_outline_20,
                label = stringResource(UiR.string.delete),
                onClick = onDelete,
                danger = true,
            )
        }
    }
}

@Composable
private fun SessionMenuItem(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val color = if (danger) DeepSeekColors.Danger else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        DsIcon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
private fun SessionMenuDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp),
        ) {
            DsIcon(
                UiR.drawable.ic_ds_search_outline_16,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        stringResource(UiR.string.search_conversations),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SelectDot(selected: Boolean) {
    val border = if (selected) DeepSeekColors.BrandBlue else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .border(1.5.dp, border, CircleShape)
            .background(
                if (selected) DeepSeekColors.BrandBlue else Color.Transparent,
                CircleShape,
            ),
    ) {
        if (selected) {
            DsIcon(
                UiR.drawable.ic_check_outline_20,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
