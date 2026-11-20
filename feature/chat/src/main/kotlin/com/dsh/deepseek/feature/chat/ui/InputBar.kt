package com.dsh.deepseek.feature.chat.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dsh.deepseek.core.model.Attachment
import com.dsh.deepseek.core.model.TokenAccounting
import com.dsh.deepseek.core.model.TokenPricing
import com.dsh.deepseek.core.model.TokenUsageTotals
import com.dsh.deepseek.core.model.UploadState
import com.dsh.deepseek.core.model.UsageInfo
import com.dsh.deepseek.core.model.formatCny
import com.dsh.deepseek.core.model.formatPercent
import com.dsh.deepseek.core.model.formatTokenCount
import com.dsh.deepseek.core.ui.DeepSeekColors
import com.dsh.deepseek.core.ui.DsIcon
import com.dsh.deepseek.core.ui.R as UiR
import com.dsh.deepseek.feature.attachments.AttachmentTypeResolver
import com.dsh.deepseek.feature.voice.recording.RecordingController
import java.io.File

/**
 * 底部输入区：官方语音优先「按住说话」胶囊。
 * 录音 HUD 由 ChatScreen 全屏叠在输入条之上，本组件保持指针捕获。
 */
@Composable
fun InputBar(
    draftText: String,
    pendingAttachments: List<Attachment>,
    thinkingEnabled: Boolean,
    webSearchEnabled: Boolean,
    streaming: Boolean,
    sessionUsage: TokenUsageTotals = TokenUsageTotals(),
    liveUsage: UsageInfo? = null,
    recordingState: RecordingController.State,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onTakePhoto: () -> Unit,
    onStartRecording: () -> Unit,
    onFinishRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onRecordPermissionGranted: () -> Unit = {},
    onSetThinking: (Boolean) -> Unit,
    onSetWebSearch: (Boolean) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onExtractText: (String) -> Unit,
    onRetryAsr: () -> Unit,
    ocrBusy: Boolean,
    onCancelArmedChange: (Boolean) -> Unit = {},
    hasMessages: Boolean = false,
    showSessionUsage: Boolean = true,
) {
    val holding = recordingState is RecordingController.State.Recording
    val displayUsage = remember(sessionUsage, liveUsage) { sessionUsage.plusLive(liveUsage) }
    val showChip = showSessionUsage && !displayUsage.isEmpty
    var showUsageDialog by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            AttachmentChipsRow(
                attachments = pendingAttachments,
                onRemove = onRemoveAttachment,
                onRetry = onRetryAttachment,
                onExtractText = onExtractText,
                ocrBusy = ocrBusy,
            )

            InputRow(
                draftText = draftText,
                thinkingEnabled = thinkingEnabled,
                webSearchEnabled = webSearchEnabled,
                streaming = streaming,
                holdingMic = holding,
                canSend = draftText.isNotBlank() || pendingAttachments.any { it.uploadState != UploadState.UPLOADING },
                onDraftChange = onDraftChange,
                onSend = onSend,
                onStop = onStop,
                onPickImage = onPickImage,
                onPickFile = onPickFile,
                onTakePhoto = onTakePhoto,
                onMic = onStartRecording,
                onFinishRecording = onFinishRecording,
                onCancelRecording = onCancelRecording,
                onRecordPermissionGranted = onRecordPermissionGranted,
                onCancelArmedChange = onCancelArmedChange,
                onSetThinking = onSetThinking,
                onSetWebSearch = onSetWebSearch,
                hasMessages = hasMessages,
            )
            if (showChip) {
                SessionUsageChipRow(
                    usage = displayUsage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showUsageDialog = true }
                        .padding(top = 8.dp, bottom = 0.dp),
                )
            }
            Text(
                stringResource(UiR.string.ai_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (showChip) 4.dp else 8.dp, bottom = 2.dp),
            )
        }
    }
    if (showUsageDialog && showChip) {
        SessionUsageDialog(usage = displayUsage, onDismiss = { showUsageDialog = false })
    }
}

private fun TokenUsageTotals.plusLive(live: UsageInfo?): TokenUsageTotals {
    if (live == null) return this
    val extra = TokenAccounting.from(live)
    if (extra.isEmpty) return this
    val now = System.currentTimeMillis()
    val peakNow = TokenPricing.isPeak(now)
    return copy(
        cacheHitTokens = cacheHitTokens + extra.cacheHitTokens,
        cacheMissTokens = cacheMissTokens + extra.cacheMissTokens,
        outputTokens = outputTokens + extra.outputTokens,
        webSearchRequests = webSearchRequests + extra.webSearchRequests,
        estimatedCostCnyMicros = estimatedCostCnyMicros + TokenPricing.cnyToMicros(
            TokenPricing.estimateCny(extra, null, now),
        ),
        hasPeak = hasPeak || peakNow,
        hasOffPeak = hasOffPeak || !peakNow,
    )
}

@Composable
private fun SessionUsageChipRow(usage: TokenUsageTotals, modifier: Modifier = Modifier) {
    val chipColor = MaterialTheme.colorScheme.onSurfaceVariant
    val chipStyle = MaterialTheme.typography.labelSmall
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (usage.hasPeak) PeriodTag(peak = true)
        if (usage.hasOffPeak) PeriodTag(peak = false)
        Text(
            text = formatTokenCount(usage.totalTokens) + " tokens",
            style = chipStyle,
            color = chipColor,
        )
        if (usage.inputTokens > 0 && usage.cacheHitTokens > 0) {
            ChipDot(chipColor)
            Text(
                text = stringResource(UiR.string.cache_hit_rate, formatPercent(usage.cacheHitRate)),
                style = chipStyle,
                color = chipColor,
            )
        }
        ChipDot(chipColor)
        Text(stringResource(UiR.string.approx_cost, formatCny(usage.estimatedCostCny)), style = chipStyle, color = chipColor)
    }
}

@Composable
private fun ChipDot(color: Color) {
    Text("·", style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.7f))
}

@Composable
private fun PeriodTag(peak: Boolean) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val background: Color
    val foreground: Color
    if (peak) {
        background = if (dark) Color(0xFF4A2E14) else Color(0xFFFFE8D1)
        foreground = if (dark) Color(0xFFFFC48A) else Color(0xFF9A4A00)
    } else {
        background = if (dark) Color(0xFF163528) else Color(0xFFDFF3E8)
        foreground = if (dark) Color(0xFF8FDCB0) else Color(0xFF1B6B43)
    }
    Text(
        text = stringResource(if (peak) UiR.string.period_peak else UiR.string.period_off_peak),
        color = foreground,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            lineHeight = 12.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
private fun SessionUsageDialog(usage: TokenUsageTotals, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(UiR.string.session_usage_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageDialogRow(stringResource(UiR.string.usage_input_uncached), formatTokenCount(usage.cacheMissTokens))
                UsageDialogRow(stringResource(UiR.string.usage_cache_hit), formatTokenCount(usage.cacheHitTokens))
                UsageDialogRow(stringResource(UiR.string.usage_output), formatTokenCount(usage.outputTokens))
                UsageDialogRow(stringResource(UiR.string.usage_total), formatTokenCount(usage.totalTokens))
                if (usage.inputTokens > 0) {
                    UsageDialogRow(stringResource(UiR.string.usage_cache_hit_rate), formatPercent(usage.cacheHitRate))
                }
                if (usage.webSearchRequests > 0) {
                    UsageDialogRow(
                        stringResource(UiR.string.usage_web_search),
                        stringResource(UiR.string.usage_web_search_count, usage.webSearchRequests),
                    )
                }
                UsageDialogRow(stringResource(UiR.string.usage_estimated_cost), formatCny(usage.estimatedCostCny))
                if (usage.hasPeak || usage.hasOffPeak) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(UiR.string.usage_period), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (usage.hasPeak) PeriodTag(peak = true)
                        if (usage.hasOffPeak) PeriodTag(peak = false)
                    }
                }
                Text(
                    stringResource(UiR.string.pricing_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.got_it)) } },
    )
}

@Composable
private fun UsageDialogRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InputRow(
    draftText: String,
    thinkingEnabled: Boolean,
    webSearchEnabled: Boolean,
    streaming: Boolean,
    holdingMic: Boolean,
    canSend: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onTakePhoto: () -> Unit,
    onMic: () -> Unit,
    onFinishRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onRecordPermissionGranted: () -> Unit = {},
    onCancelArmedChange: (Boolean) -> Unit,
    onSetThinking: (Boolean) -> Unit,
    onSetWebSearch: (Boolean) -> Unit,
    hasMessages: Boolean,
) {
    var micDenied by remember { mutableStateOf(false) }
    var voiceModeOverride by remember { mutableStateOf<Boolean?>(null) }
    var fieldFocused by remember { mutableStateOf(false) }
    val voiceMode = voiceModeOverride ?: !hasMessages
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val cancelSlopPx = with(LocalDensity.current) { 72.dp.toPx() }
    val latestOnMic by rememberUpdatedState(onMic)
    val latestOnFinish by rememberUpdatedState(onFinishRecording)
    val latestOnCancel by rememberUpdatedState(onCancelRecording)
    val latestOnArmed by rememberUpdatedState(onCancelArmedChange)
    val latestOnPermissionGranted by rememberUpdatedState(onRecordPermissionGranted)
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        if (granted) latestOnPermissionGranted()
    }
    suspend fun AwaitPointerEventScope.beginHoldRecording(down: PointerInputChange) {
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    change.consume()
                    break
                }
            }
            return
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        latestOnArmed(false)
        latestOnMic()
        var cancelled = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val nowCancelled = (change.position.y - down.position.y) < -cancelSlopPx
            if (nowCancelled != cancelled) {
                cancelled = nowCancelled
                latestOnArmed(cancelled)
            }
            if (!change.pressed) {
                change.consume()
                latestOnArmed(false)
                if (cancelled) latestOnCancel() else latestOnFinish()
                break
            }
        }
    }
    val holdModifier = Modifier.pointerInput(cancelSlopPx) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            beginHoldRecording(down)
        }
    }
    val idleHoldToTalk = !streaming && !fieldFocused && draftText.isEmpty()
    val idleHoldModifier = Modifier.pointerInput(cancelSlopPx, idleHoldToTalk) {
        if (!idleHoldToTalk) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val longPress = awaitLongPressOrCancellation(down.id)
            if (longPress == null) {
                focusRequester.requestFocus()
                keyboardController?.show()
            } else {
                beginHoldRecording(down)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)) {
            val inputFieldModifier = Modifier
                .fillMaxWidth()
                .heightIn(min = InputFieldMinHeight)
            if (voiceMode && !streaming) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = inputFieldModifier.then(holdModifier),
                ) {
                    Text(
                        stringResource(UiR.string.hold_to_talk),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = inputFieldModifier.padding(horizontal = 4.dp),
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = draftText,
                        onValueChange = onDraftChange,
                        enabled = !holdingMic,
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { fieldFocused = it.isFocused },
                        decorationBox = { inner ->
                            Box {
                                if (draftText.isEmpty()) {
                                    Text(
                                        stringResource(
                                            if (fieldFocused) {
                                                UiR.string.input_placeholder
                                            } else {
                                                UiR.string.input_placeholder_idle
                                            },
                                        ),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    if (idleHoldToTalk) {
                        Box(Modifier.matchParentSize().then(idleHoldModifier))
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    ToggleChip(
                        icon = {
                            DsIcon(UiR.drawable.ic_think_outline_16, null, modifier = Modifier.size(16.dp))
                        },
                        label = stringResource(UiR.string.thinking_toggle),
                        checked = thinkingEnabled,
                        onChange = onSetThinking,
                    )
                    ToggleChip(
                        icon = {
                            DsIcon(UiR.drawable.ic_globe_language_outline_16, null, modifier = Modifier.size(16.dp))
                        },
                        label = stringResource(UiR.string.web_search_toggle),
                        checked = webSearchEnabled,
                        onChange = onSetWebSearch,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    AttachmentMenu(onPickImage, onPickFile, onTakePhoto)
                    when {
                        streaming -> FilledTrailIcon(
                            icon = UiR.drawable.ic_stop_fill_20,
                            contentDescription = stringResource(UiR.string.stop_generate),
                            onClick = onStop,
                        )
                        voiceMode -> OutlineTrailIcon(
                            icon = UiR.drawable.ic_keyboard_outline_26,
                            contentDescription = stringResource(UiR.string.keyboard_input),
                            onClick = { voiceModeOverride = false },
                        )
                        canSend -> FilledTrailIcon(
                            icon = UiR.drawable.ic_send_fill_20,
                            contentDescription = stringResource(UiR.string.send),
                            onClick = onSend,
                        )
                        else -> OutlineTrailIcon(
                            icon = UiR.drawable.ic_voice_outline_26,
                            contentDescription = stringResource(UiR.string.voice_input),
                            onClick = { voiceModeOverride = true },
                        )
                    }
                }
            }
            if (micDenied) {
                Text(
                    stringResource(UiR.string.mic_denied),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private val InputFieldMinHeight = 52.dp
private val TrailBtnSize = 26.dp

@Composable
private fun OutlineTrailIcon(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .requiredSize(TrailBtnSize)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
        ) {
            DsIcon(
                id = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(TrailBtnSize),
            )
        }
    }
}

@Composable
private fun FilledTrailIcon(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .requiredSize(TrailBtnSize)
                .clip(CircleShape)
                .background(DeepSeekColors.BrandBlue)
                .clickable(onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
        ) {
            DsIcon(
                id = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun AttachmentMenu(onPickImage: () -> Unit, onPickFile: () -> Unit, onTakePhoto: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlineTrailIcon(
            icon = UiR.drawable.ic_add_outline_26,
            contentDescription = stringResource(UiR.string.add_attachment),
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(UiR.string.album)) },
                leadingIcon = { DsIcon(UiR.drawable.ic_photo_outline_20, null) },
                onClick = { expanded = false; onPickImage() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(UiR.string.take_photo)) },
                leadingIcon = { DsIcon(UiR.drawable.ic_camera_outline_20, null) },
                onClick = { expanded = false; onTakePhoto() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(UiR.string.file)) },
                leadingIcon = { DsIcon(UiR.drawable.ic_folder_outline_20, null) },
                onClick = { expanded = false; onPickFile() },
            )
        }
    }
}

@Composable
private fun ToggleChip(
    icon: @Composable () -> Unit,
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val accent = DeepSeekColors.BrandBlue
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (checked) accent.copy(alpha = 0.14f) else Color.Transparent,
        border = BorderStroke(1.dp, if (checked) accent else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clickable { onChange(!checked) },
    ) {
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides
                if (checked) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = androidx.compose.material3.LocalContentColor.current,
                )
            }
        }
    }
}

@Composable
private fun AttachmentChipsRow(
    attachments: List<Attachment>,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
    onExtractText: (String) -> Unit,
    ocrBusy: Boolean,
) {
    if (attachments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        attachments.forEach { att ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    val path = att.localPath
                    if (att.isImage && path != null && File(path).exists()) {
                        AsyncImage(
                            model = File(path),
                            contentDescription = att.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    val label = when (att.uploadState) {
                        UploadState.UPLOADING -> "${att.displayName} · ${formatBytes(att.sizeBytes)}（上传中 ${att.progressPercent}%）"
                        UploadState.FAILED -> "${att.displayName} · ${formatBytes(att.sizeBytes)}（上传失败）"
                        UploadState.UPLOADED -> "${att.displayName} · ${formatBytes(att.sizeBytes)}"
                        UploadState.LOCAL -> "${att.displayName} · ${formatBytes(att.sizeBytes)}"
                    }
                    val ocrSupported = AttachmentTypeResolver.classify(att.displayName, att.mimeType).ocrSupported
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (ocrSupported && att.localPath != null) {
                        TextButton(onClick = { onExtractText(att.id) }, enabled = !ocrBusy) {
                            Text(if (ocrBusy) "提取中" else "提取文字")
                        }
                    }
                    if (att.uploadState == UploadState.FAILED) {
                        TextButton(onClick = { onRetry(att.id) }) { Text(stringResource(UiR.string.retry)) }
                    }
                    IconButton(onClick = { onRemove(att.id) }, modifier = Modifier.size(24.dp)) {
                        DsIcon(UiR.drawable.ic_close_outline_16, contentDescription = stringResource(UiR.string.remove), modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun RecordingOverlay(
    amplitude: Float,
    cancelArmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val bottom = if (cancelArmed) Color(0xFFB3261E) else Color(0xFF1A6DFF)
    val mid = if (cancelArmed) Color(0xCCB3261E) else Color(0xCC1A6DFF)
    // 无 clickable / pointerInput，按住说话的指针仍由 InputBar 持有。
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.48f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.18f to Color.Transparent,
                        0.42f to mid.copy(alpha = 0.35f),
                        0.68f to mid,
                        1f to bottom,
                    ),
                ),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 36.dp),
        ) {
            Text(
                if (cancelArmed) stringResource(UiR.string.release_to_cancel) else stringResource(UiR.string.release_to_send),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.96f),
            )
            Spacer(Modifier.height(40.dp))
            Waveform(
                amplitude = amplitude,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .height(14.dp),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}

@Composable
private fun Waveform(
    amplitude: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "wavePulse",
    )
    Canvas(modifier) {
        val barWidth = 2.4.dp.toPx()
        val gap = 4.2.dp.toPx()
        val count = ((size.width + gap) / (barWidth + gap)).toInt().coerceAtLeast(12)
        val total = count * barWidth + (count - 1) * gap
        val startX = (size.width - total) / 2f
        val midY = size.height / 2f
        val baseH = 8.dp.toPx()
        val live = (0.75f + amplitude.coerceIn(0f, 1f) * 0.45f) * pulse
        for (i in 0 until count) {
            val t = if (count == 1) 0.5f else i / (count - 1).toFloat()
            val edge = minOf(t, 1f - t)
            val fade = (edge / 0.12f).coerceIn(0.12f, 1f)
            val wobble = 0.88f + 0.12f * ((i * 13) % 7) / 6f
            val h = baseH * live * wobble
            val x = startX + i * (barWidth + gap)
            drawRoundRect(
                color = color.copy(alpha = 0.92f * fade),
                topLeft = Offset(x, midY - h / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
