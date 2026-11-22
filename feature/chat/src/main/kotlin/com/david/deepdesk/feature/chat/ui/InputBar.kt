package com.david.deepdesk.feature.chat.ui

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.res.painterResource
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
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import com.david.deepdesk.core.model.Attachment
import com.david.deepdesk.core.model.ChatModels
import com.david.deepdesk.core.model.ListedModel
import com.david.deepdesk.core.model.TokenAccounting
import com.david.deepdesk.core.model.TokenPricing
import com.david.deepdesk.core.model.TokenUsageTotals
import com.david.deepdesk.core.model.UploadState
import com.david.deepdesk.core.model.UsageInfo
import com.david.deepdesk.core.model.formatCny
import com.david.deepdesk.core.model.formatPercent
import com.david.deepdesk.core.model.formatTokenCount
import com.david.deepdesk.core.ui.DeepSeekColors
import com.david.deepdesk.core.ui.DsIcon
import com.david.deepdesk.core.ui.R as UiR
import com.david.deepdesk.feature.voice.recording.RecordingController
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
    onSendPrompt: (String) -> Unit = {},
    onAddUri: (Uri) -> Unit = {},
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onExtractText: (String) -> Unit,
    onRetryAsr: () -> Unit,
    ocrBusy: Boolean,
    onCancelArmedChange: (Boolean) -> Unit = {},
    showSessionUsage: Boolean = true,
    imagesEnabled: Boolean = true,
    pricingModel: String? = null,
    professionalMode: Boolean = false,
    modelId: String = ChatModels.FLASH,
    reasoningEffort: String = ChatModels.EFFORT_HIGH,
    onSetThinkingLevel: (Int) -> Unit = {},
    onSetModel: (String) -> Unit = {},
    catalogModels: List<ListedModel> = emptyList(),
    catalogLoading: Boolean = false,
    catalogError: String? = null,
    onLoadModels: () -> Unit = {},
) {
    val holding = recordingState is RecordingController.State.Recording
    val displayUsage = remember(sessionUsage, liveUsage, pricingModel) {
        sessionUsage.plusLive(liveUsage, pricingModel)
    }
    val showChip = showSessionUsage && !displayUsage.isEmpty
    var showUsageDialog by remember { mutableStateOf(false) }
    var attachSheetOpen by remember { mutableStateOf(false) }
    var levelOpen by remember { mutableStateOf(false) }
    LaunchedEffect(professionalMode) {
        if (!professionalMode) levelOpen = false
    }
    var photoPrompted by remember { mutableStateOf(false) }
    val photoContext = LocalContext.current
    val photoDensity = LocalDensity.current
    var photoAccessState by remember { mutableStateOf(photoAccess(photoContext)) }
    var recentPhotoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var recentPhotoBitmaps by remember { mutableStateOf<Map<Uri, ImageBitmap>>(emptyMap()) }
    var photosReady by remember { mutableStateOf(photoAccessState == PhotoAccess.NONE) }
    LaunchedEffect(photoAccessState) {
        if (photoAccessState == PhotoAccess.NONE) {
            recentPhotoUris = emptyList()
            recentPhotoBitmaps = emptyMap()
            photosReady = true
            return@LaunchedEffect
        }
        val uris = withContext(Dispatchers.IO) { recentPhotos(photoContext) }
        recentPhotoUris = uris
        photosReady = true
        val px = with(photoDensity) { 72.dp.roundToPx() }
        val decoded = coroutineScope {
            uris.map { uri ->
                async(Dispatchers.IO) { uri to decodeRecentPhoto(photoContext, uri, px) }
            }.awaitAll()
        }
        recentPhotoBitmaps = decoded.mapNotNull { (uri, image) -> image?.let { uri to it } }.toMap()
    }
    LaunchedEffect(streaming) {
        if (streaming) attachSheetOpen = false
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            InputRow(
                draftText = draftText,
                thinkingEnabled = thinkingEnabled,
                professionalMode = professionalMode,
                reasoningEffort = reasoningEffort,
                levelOpen = levelOpen,
                modelId = modelId,
                onSetThinkingLevel = onSetThinkingLevel,
                onSetModel = onSetModel,
                catalogModels = catalogModels,
                catalogLoading = catalogLoading,
                catalogError = catalogError,
                onLoadModels = onLoadModels,
                onDismissThinkingLevel = { levelOpen = false },
                onOpenThinkingLevel = { levelOpen = true },
                webSearchEnabled = webSearchEnabled,
                streaming = streaming,
                holdingMic = holding,
                canSend = draftText.isNotBlank() || pendingAttachments.any { it.uploadState != UploadState.UPLOADING },
                attachSheetOpen = attachSheetOpen,
                onToggleAttachSheet = { attachSheetOpen = !attachSheetOpen },
                onDraftChange = onDraftChange,
                onSend = {
                    attachSheetOpen = false
                    levelOpen = false
                    onSend()
                },
                onStop = onStop,
                onMic = onStartRecording,
                onFinishRecording = onFinishRecording,
                onCancelRecording = onCancelRecording,
                onRecordPermissionGranted = onRecordPermissionGranted,
                onCancelArmedChange = onCancelArmedChange,
                onSetThinking = onSetThinking,
                onSetWebSearch = onSetWebSearch,
                pendingAttachments = pendingAttachments,
                onRemoveAttachment = onRemoveAttachment,
                onRetryAttachment = onRetryAttachment,
                onSendPrompt = { prompt ->
                    attachSheetOpen = false
                    onSendPrompt(prompt)
                },
            )
            if (attachSheetOpen && !streaming) {
                AttachSheet(
                    pending = pendingAttachments,
                    photos = recentPhotoUris,
                    photoBitmaps = recentPhotoBitmaps,
                    photosReady = photosReady,
                    access = photoAccessState,
                    onAccessChanged = { photoAccessState = it },
                    photoPrompted = photoPrompted,
                    onPhotoPrompted = { photoPrompted = true },
                    imagesEnabled = imagesEnabled,
                    onPickImage = onPickImage,
                    onPickFile = onPickFile,
                    onTakePhoto = onTakePhoto,
                    onAddUri = onAddUri,
                    onRemove = onRemoveAttachment,
                )
            }
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

private fun TokenUsageTotals.plusLive(live: UsageInfo?, model: String?): TokenUsageTotals {
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
            TokenPricing.estimateCny(extra, model, now),
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
    professionalMode: Boolean,
    reasoningEffort: String,
    levelOpen: Boolean,
    modelId: String,
    onSetThinkingLevel: (Int) -> Unit,
    onSetModel: (String) -> Unit,
    catalogModels: List<ListedModel>,
    catalogLoading: Boolean,
    catalogError: String?,
    onLoadModels: () -> Unit,
    onDismissThinkingLevel: () -> Unit,
    onOpenThinkingLevel: () -> Unit,
    webSearchEnabled: Boolean,
    streaming: Boolean,
    holdingMic: Boolean,
    canSend: Boolean,
    attachSheetOpen: Boolean,
    onToggleAttachSheet: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMic: () -> Unit,
    onFinishRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onRecordPermissionGranted: () -> Unit = {},
    onCancelArmedChange: (Boolean) -> Unit,
    onSetThinking: (Boolean) -> Unit,
    onSetWebSearch: (Boolean) -> Unit,
    pendingAttachments: List<Attachment>,
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onSendPrompt: (String) -> Unit,
) {
    var micDenied by remember { mutableStateOf(false) }
    var voiceModeOverride by remember { mutableStateOf<Boolean?>(null) }
    var fieldFocused by remember { mutableStateOf(false) }
    val voiceMode = voiceModeOverride == true
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
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp)) {
            PendingAttachments(
                attachments = pendingAttachments,
                enabled = !streaming,
                onRemove = onRemoveAttachment,
                onRetry = onRetryAttachment,
                onSendPrompt = onSendPrompt,
            )
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
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
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
            }

            ThinkingLevelAnchor(
                open = levelOpen && professionalMode,
                level = ChatModels.thinkingLevel(thinkingEnabled, reasoningEffort),
                modelId = modelId,
                catalogModels = catalogModels,
                catalogLoading = catalogLoading,
                catalogError = catalogError,
                onLevel = onSetThinkingLevel,
                onModel = onSetModel,
                onLoadModels = onLoadModels,
                onDismiss = onDismissThinkingLevel,
            )

            val threeTrail = attachSheetOpen && canSend && !streaming && !voiceMode
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clipToBounds(),
                ) {
                    ToggleChip(
                        icon = {
                            DsIcon(UiR.drawable.ic_think_outline_16, null, modifier = Modifier.size(16.dp))
                        },
                        label = if (professionalMode) {
                            val level = ChatModels.thinkingLevel(thinkingEnabled, reasoningEffort)
                            val name = when (level) {
                                0 -> stringResource(UiR.string.thinking_stop_off)
                                1 -> stringResource(UiR.string.effort_low)
                                3 -> stringResource(UiR.string.thinking_stop_max)
                                else -> stringResource(UiR.string.effort_high)
                            }
                            stringResource(UiR.string.thinking_chip, name)
                        } else {
                            stringResource(UiR.string.thinking_toggle)
                        },
                        checked = thinkingEnabled,
                        onChange = if (professionalMode) {
                            { onOpenThinkingLevel() }
                        } else {
                            onSetThinking
                        },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ToggleChip(
                        icon = {
                            DsIcon(UiR.drawable.ic_globe_language_outline_16, null, modifier = Modifier.size(16.dp))
                        },
                        label = stringResource(UiR.string.web_search_toggle),
                        checked = webSearchEnabled,
                        onChange = onSetWebSearch,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(if (threeTrail) 6.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = if (threeTrail) 6.dp else 12.dp),
                ) {
                    OutlineTrailIcon(
                        icon = if (attachSheetOpen) UiR.drawable.ic_close_outline_20 else UiR.drawable.ic_add_outline_26,
                        contentDescription = stringResource(
                            if (attachSheetOpen) UiR.string.cancel else UiR.string.add_attachment,
                        ),
                        onClick = onToggleAttachSheet,
                    )
                    if (attachSheetOpen && canSend && !streaming && !voiceMode) {
                        OutlineTrailIcon(
                            icon = UiR.drawable.ic_voice_outline_26,
                            contentDescription = stringResource(UiR.string.voice_input),
                            onClick = { voiceModeOverride = true },
                        )
                    }
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
                    modifier = Modifier.padding(start = 12.dp, end = 8.dp, bottom = 6.dp),
                )
            }
        }
    }
}

private val InputFieldMinHeight = 52.dp
private val TrailBtnSize = 26.dp
private val FilledTrailBtnSize = 32.dp

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
                .requiredSize(FilledTrailBtnSize)
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
private fun AttachSheet(
    pending: List<Attachment>,
    photos: List<Uri>,
    photoBitmaps: Map<Uri, ImageBitmap>,
    photosReady: Boolean,
    access: PhotoAccess,
    onAccessChanged: (PhotoAccess) -> Unit,
    photoPrompted: Boolean,
    onPhotoPrompted: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    imagesEnabled: Boolean,
    onTakePhoto: () -> Unit,
    onAddUri: (Uri) -> Unit,
    onRemove: (String) -> Unit,
) {
    val context = LocalContext.current
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onAccessChanged(photoAccess(context))
    }
    LaunchedEffect(access, imagesEnabled) {
        if (!imagesEnabled) return@LaunchedEffect
        if (!photoPrompted && access == PhotoAccess.NONE) {
            onPhotoPrompted()
            askPermission.launch(photoPermissionRequest())
        }
    }
    val showPhotoRow = imagesEnabled && (access != PhotoAccess.FULL || photos.isNotEmpty() || !photosReady)
    val photoPx = with(LocalDensity.current) { 72.dp.roundToPx() }
    Column(Modifier.padding(top = 8.dp)) {
        if (showPhotoRow) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            photos.forEach { uri ->
                val selected = pending.firstOrNull { it.localUri == uri.toString() }
                val bitmap = photoBitmaps[uri]
                Box(Modifier.size(72.dp)) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (selected == null) onAddUri(uri) else onRemove(selected.id)
                                },
                        )
                    } else {
                        AsyncImage(
                            model = recentPhotoRequest(context, uri, photoPx),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable {
                                    if (selected == null) onAddUri(uri) else onRemove(selected.id)
                                },
                        )
                    }
                    PhotoCheck(
                        selected = selected != null,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    )
                }
            }
            if (access != PhotoAccess.FULL) {
                Surface(
                    onClick = { askPermission.launch(photoPermissionRequest()) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.height(72.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        Text("+", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(UiR.string.grant_more_photos),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
        }
        if (!imagesEnabled) {
            Text(
                stringResource(UiR.string.model_no_vision_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            if (imagesEnabled) {
                AttachAction(UiR.drawable.ic_camera_outline_20, stringResource(UiR.string.take_photo), Modifier.weight(1f), onTakePhoto)
                AttachAction(UiR.drawable.ic_photo_outline_20, stringResource(UiR.string.album), Modifier.weight(1f), onPickImage)
            }
            AttachAction(UiR.drawable.ic_folder_outline_20, stringResource(UiR.string.file), Modifier.weight(1f), onPickFile)
        }
    }
}

@Composable
private fun AttachAction(
    @DrawableRes icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(84.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            DsIcon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private enum class PhotoAccess { NONE, PARTIAL, FULL }

private fun photoAccess(context: android.content.Context): PhotoAccess {
    fun ok(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 34 -> when {
            ok(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
            ok(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.PARTIAL
            else -> PhotoAccess.NONE
        }
        Build.VERSION.SDK_INT >= 33 ->
            if (ok(Manifest.permission.READ_MEDIA_IMAGES)) PhotoAccess.FULL else PhotoAccess.NONE
        else ->
            if (ok(Manifest.permission.READ_EXTERNAL_STORAGE)) PhotoAccess.FULL else PhotoAccess.NONE
    }
}

/** 图片：13 起读图片，14 起同时申请「仅选中的照片」。12 及以下用存储权限。 */
private fun photoPermissionRequest(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
private fun PhotoCheck(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(1.dp, Color.Black.copy(alpha = 0.35f), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(if (selected) DeepSeekColors.BrandBlue else Color.Transparent)
                .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                DsIcon(
                    UiR.drawable.ic_check_outline_20,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

private fun recentPhotoRequest(context: android.content.Context, uri: Uri, sizePx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(uri)
        .size(sizePx)
        .allowHardware(false)
        .bitmapConfig(Bitmap.Config.ARGB_8888)
        .memoryCacheKey(uri.toString())
        .crossfade(false)
        .build()

/** 面板打开前就把缩略图解到内存，点加号时第一帧就能画出来。 */
private suspend fun decodeRecentPhoto(
    context: android.content.Context,
    uri: Uri,
    sizePx: Int,
): ImageBitmap? = try {
    val result = context.imageLoader.execute(recentPhotoRequest(context, uri, sizePx))
    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
    bitmap.copy(Bitmap.Config.ARGB_8888, false)?.asImageBitmap()
} catch (_: Exception) {
    null
}

private fun recentPhotos(context: android.content.Context, limit: Int = 24): List<Uri> {
    val collection = if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val list = mutableListOf<Uri>()
    try {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && list.size < limit) {
                list += ContentUris.withAppendedId(collection, cursor.getLong(idCol))
            }
        }
    } catch (_: SecurityException) {
        return emptyList()
    }
    return list
}

@Composable
private fun ToggleChip(
    icon: @Composable () -> Unit,
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = DeepSeekColors.BrandBlue
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = if (checked) accent.copy(alpha = 0.14f) else Color.Transparent,
        border = BorderStroke(1.dp, if (checked) accent else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .clip(shape)
            .clickable { onChange(!checked) },
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
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PendingAttachments(
    attachments: List<Attachment>,
    enabled: Boolean,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
    onSendPrompt: (String) -> Unit,
) {
    if (attachments.isEmpty()) return
    val imagePreview = LocalImagePreview.current
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            attachments.forEach { att ->
                val path = att.localPath
                val image = att.isImage && path != null && File(path).exists()
                if (image) {
                    PendingImageCard(
                        path = path,
                        onPreview = { imagePreview?.open(path) },
                        onRemove = { onRemove(att.id) },
                    )
                } else {
                    PendingFileCard(
                        att = att,
                        onRetry = { onRetry(att.id) },
                        onRemove = { onRemove(att.id) },
                    )
                }
            }
        }
        if (enabled && attachments.none { it.uploadState == UploadState.UPLOADING }) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(top = 10.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                listOf(
                    UiR.string.attach_prompt_what,
                    UiR.string.attach_prompt_analyze,
                    UiR.string.attach_prompt_how,
                ).forEach { id ->
                    val label = stringResource(id)
                    Surface(
                        onClick = { onSendPrompt(label) },
                        shape = RoundedCornerShape(18.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    ) {
                        Text(
                            text = "$label →",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingImageCard(path: String, onPreview: () -> Unit, onRemove: () -> Unit) {
    Box(Modifier.size(76.dp)) {
        AsyncImage(
            model = File(path),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .sharedPreviewImage(path)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onPreview),
        )
        RemoveBadge(onRemove, Modifier.align(Alignment.TopEnd).padding(4.dp))
    }
}

@Composable
private fun PendingFileCard(att: Attachment, onRetry: () -> Unit, onRemove: () -> Unit) {
    val pdf = att.displayName.substringAfterLast('.', "").equals("pdf", true) ||
        att.mimeType.equals("application/pdf", true)
    val detail = when (att.uploadState) {
        UploadState.UPLOADING -> stringResource(UiR.string.uploading_percent, att.progressPercent)
        UploadState.FAILED -> stringResource(UiR.string.retry)
        else -> formatCardSize(att.sizeBytes)
    }
    Box(Modifier.width(176.dp).height(76.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .then(if (att.uploadState == UploadState.FAILED) Modifier.clickable(onClick = onRetry) else Modifier)
                .padding(horizontal = 10.dp),
        ) {
            if (pdf) {
                Icon(
                    painter = painterResource(UiR.drawable.ic_file_pdf),
                    contentDescription = stringResource(UiR.string.file_pdf),
                    tint = Color.Unspecified,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                DsIcon(
                    UiR.drawable.ic_file_generic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(Modifier.padding(start = 8.dp, end = 16.dp)) {
                Text(
                    att.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        RemoveBadge(onRemove, Modifier.align(Alignment.TopEnd).padding(4.dp))
    }
}

@Composable
private fun RemoveBadge(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(Color(0xCC3A3A3C))
            .clickable(onClick = onRemove),
        contentAlignment = Alignment.Center,
    ) {
        DsIcon(
            UiR.drawable.ic_close_outline_16,
            contentDescription = stringResource(UiR.string.remove),
            tint = Color.White,
            modifier = Modifier.size(10.dp),
        )
    }
}

private fun formatCardSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        mb >= 1024 -> String.format(java.util.Locale.US, "%.2fGB", mb / 1024.0)
        mb >= 1 -> String.format(java.util.Locale.US, if (mb >= 10) "%.1fMB" else "%.2fMB", mb)
        bytes >= 1024 -> String.format(java.util.Locale.US, "%.0fKB", bytes / 1024.0)
        else -> "${bytes}B"
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
