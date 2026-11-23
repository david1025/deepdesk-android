package com.david.deepdesk.feature.chat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarResult
import com.david.deepdesk.core.common.AppError
import com.david.deepdesk.core.model.ReplyStatus
import com.david.deepdesk.core.ui.DsIcon
import com.david.deepdesk.core.ui.LocalCenterHint
import com.david.deepdesk.core.ui.R as UiR
import com.david.deepdesk.feature.chat.ChatUiState
import com.david.deepdesk.feature.chat.ChatViewModel
import com.david.deepdesk.feature.voice.recording.RecordingController
import com.david.deepdesk.feature.voice.tts.TtsPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 聊天详情页（§3.3）：
 * - 消息流默认跟随末尾；用户上滑后停止追尾并显示“回到底部”
 * - 会话切换时恢复草稿与滚动锚点
 * - 抽屉由 app 层的 ModalNavigationDrawer 装配
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    sessionId: String?,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val recording by viewModel.recording.collectAsState()
    val tts by viewModel.tts.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showRename by remember { mutableStateOf(false) }
    var cancelArmed by remember { mutableStateOf(false) }
    var copiedHint by remember { mutableStateOf<String?>(null) }
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var editFocusTick by remember { mutableIntStateOf(0) }

    fun beginEdit(messageId: String, text: String) {
        if (ui.isStreaming || text.isBlank()) return
        editingMessageId = messageId
        editFocusTick++
    }

    fun cancelEdit() {
        editingMessageId = null
    }

    LaunchedEffect(ui.sessionId) {
        editingMessageId = null
    }

    LaunchedEffect(sessionId) {
        viewModel.refreshApiKey()
        if (sessionId == null || sessionId == "new") {
            val current = viewModel.ui.value
            val keep = current.sessionId != null ||
                current.messages.isNotEmpty() ||
                current.draftText.isNotBlank() ||
                current.pendingAttachments.isNotEmpty() ||
                current.isStreaming
            if (!keep) viewModel.newSession()
            else current.sessionId?.let { viewModel.loadSession(it) }
        } else {
            viewModel.loadSession(sessionId)
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.cancelRecording() }
    }

    LaunchedEffect(Unit) {
        val granted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.warmupMic()
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.addAttachment(uri)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.addAttachment(uri)
        }
    }
    val cameraUri = remember { mutableStateOf<android.net.Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri.value
        if (ok && uri != null) viewModel.addAttachment(uri)
    }
    fun launchCamera() {
        val uri = cameraOutputUri(context)
        cameraUri.value = uri
        takePhoto.launch(uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }

    val imagePreview = remember { ImagePreviewController() }
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalImagePreview provides imagePreview,
            LocalSharedTransitionScope provides this,
        ) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (!ui.sessionId.isNullOrBlank() || ui.messages.isNotEmpty()) {
                        Text(
                            ui.conversationTitle.ifBlank { stringResource(UiR.string.new_chat) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.clickable(enabled = ui.sessionId != null) {
                                showRename = true
                            },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        DsIcon(UiR.drawable.ic_menu_outline_24, contentDescription = stringResource(UiR.string.open_history))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.setAutoRead(!ui.autoReadEnabled) }) {
                        DsIcon(
                            id = if (ui.autoReadEnabled) UiR.drawable.ic_play_media_outline_20 else UiR.drawable.ic_mute_audio_outline_24,
                            contentDescription = stringResource(
                                if (ui.autoReadEnabled) UiR.string.auto_read_on else UiR.string.auto_read_off,
                            ),
                        )
                    }
                    IconButton(onClick = onNewChat) {
                        DsIcon(UiR.drawable.ic_new_chat_outline_24, contentDescription = stringResource(UiR.string.new_chat_action))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            CompositionLocalProvider(LocalCenterHint provides { copiedHint = it }) {
            Column(Modifier.fillMaxSize()) {
                val routeIsNew = sessionId == null || sessionId == "new"
                val sessionSynced = routeIsNew || ui.sessionId == sessionId
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (!sessionSynced) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else {
                        androidx.compose.runtime.key(ui.sessionId) {
                            MessageList(
                                modifier = Modifier.fillMaxSize(),
                                ui = ui,
                                isSpeakingId = (tts as? TtsPlayer.State.Playing)?.messageId,
                                onRegenerate = viewModel::regenerate,
                                onSwitchVersion = viewModel::switchVersion,
                                onBeginEdit = ::beginEdit,
                                onToggleTts = viewModel::toggleTts,
                                onSaveAnchor = viewModel::saveScrollAnchor,
                                onCopied = { copiedHint = context.getString(UiR.string.copied) },
                                onOpenSettings = onOpenSettings,
                                onContinue = viewModel::continueGeneration,
                            )
                        }
                    }
                }

                InputBar(
                    draftText = ui.draftText,
                    pendingAttachments = ui.pendingAttachments,
                    thinkingEnabled = ui.thinkingEnabled,
                    webSearchEnabled = ui.webSearchEnabled,
                    streaming = ui.isStreaming,
                    sessionUsage = ui.sessionUsage,
                    liveUsage = ui.streamingDraft?.usage,
                    showSessionUsage = ui.showSessionUsageChip,
                    imagesEnabled = ui.imagesEnabled,
                    pricingModel = if (ui.professionalMode) ui.modelId else com.david.deepdesk.feature.chat.engine.RequestFactory.MODEL,
                    professionalMode = ui.professionalMode,
                    modelId = ui.modelId,
                    reasoningEffort = ui.reasoningEffort,
                    onSetThinkingLevel = viewModel::setThinkingLevel,
                    onSetModel = viewModel::setChatModel,
                    catalogModels = ui.catalogModels,
                    catalogLoading = ui.catalogLoading,
                    catalogError = ui.catalogError,
                    onLoadModels = viewModel::loadCatalogModels,
                    recordingState = recording,
                    onDraftChange = viewModel::onDraftChange,
                    editing = editingMessageId != null,
                    onCancelEdit = ::cancelEdit,
                    editFocusTick = editFocusTick,
                    onSend = {
                        val editingId = editingMessageId
                        if (editingId != null) {
                            val text = ui.draftText
                            editingMessageId = null
                            viewModel.onDraftChange("")
                            viewModel.editMessage(editingId, text)
                        } else {
                            viewModel.send()
                        }
                    },
                    onStop = viewModel::stopGeneration,
                    onPickImage = {
                        if (ui.imagesEnabled) {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    },
                    onPickFile = { pickFile.launch(arrayOf("*/*")) },
                    onTakePhoto = {
                        if (ui.imagesEnabled) {
                            val granted = context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (granted) launchCamera() else cameraPermission.launch(android.Manifest.permission.CAMERA)
                        }
                    },
                    onStartRecording = {
                        viewModel.onMicClick(context, hasPermission = true)
                    },
                    onRecordPermissionGranted = viewModel::warmupMic,
                    onFinishRecording = { viewModel.finishRecording() },
                    onCancelRecording = viewModel::cancelRecording,
                    onSetThinking = viewModel::setThinking,
                    onSetWebSearch = viewModel::setWebSearch,
                    onSendPrompt = viewModel::sendSuggestion,
                    onAddUri = viewModel::addAttachment,
                    onRemoveAttachment = viewModel::removeAttachment,
                    onRetryAttachment = viewModel::retryAttachmentUpload,
                    onExtractText = viewModel::extractText,
                    onRetryAsr = viewModel::retryAsr,
                    ocrBusy = ui.ocrBusy,
                    onCancelArmedChange = { cancelArmed = it },
                )
            }
            }

            val overlayRecording = recording as? RecordingController.State.Recording
            if (overlayRecording != null) {
                RecordingOverlay(
                    amplitude = overlayRecording.amplitude,
                    cancelArmed = cancelArmed,
                )
            }
            val centerHint = ui.voiceHint ?: ui.toastHint ?: copiedHint
            centerHint?.let { hint ->
                CenterHintToast(
                    text = hint,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
            if (imagePreview.path != null) {
                ImageExpandPreview(imagePreview)
            }
        }
    }

    LaunchedEffect(ui.voiceHint, ui.toastHint, copiedHint) {
        if (ui.voiceHint == null && ui.toastHint == null && copiedHint == null) return@LaunchedEffect
        delay(1600)
        viewModel.dismissVoiceHint()
        copiedHint = null
    }

    LaunchedEffect(ui.error) {
        val err = ui.error ?: return@LaunchedEffect
        if (err.kind == AppError.Kind.AUTH) {
            val result = snackbar.showSnackbar(err.message, actionLabel = context.getString(UiR.string.go_to_settings))
            if (result == SnackbarResult.ActionPerformed) onOpenSettings()
        } else {
            snackbar.showSnackbar(err.message)
        }
        viewModel.dismissError()
    }

    if (showRename && ui.sessionId != null) {
        var newName by remember(ui.sessionId, ui.conversationTitle) {
            mutableStateOf(ui.conversationTitle)
        }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(UiR.string.rename_chat)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) viewModel.renameSession(newName)
                    showRename = false
                }) { Text(stringResource(UiR.string.save)) }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text(stringResource(UiR.string.cancel)) } },
        )
    }

    ui.ocrResult?.let { ocr ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = viewModel::dismissOcr,
            title = { Text(stringResource(UiR.string.extract_text, ocr.fileName)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (ocr.pages > 1) {
                        Text(stringResource(UiR.string.page_count, ocr.pages), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(ocr.text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissOcr) { Text(stringResource(UiR.string.done)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(ocr.text))
                    copiedHint = context.getString(UiR.string.copied)
                }) { Text(stringResource(UiR.string.copy)) }
            },
        )
    }
}

@Composable
private fun CenterHintToast(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xE63A3A3C),
        modifier = modifier,
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun MessageList(
    modifier: Modifier = Modifier,
    ui: ChatUiState,
    isSpeakingId: String?,
    onRegenerate: (String) -> Unit,
    onSwitchVersion: (String, Int) -> Unit,
    onBeginEdit: (String, String) -> Unit,
    onToggleTts: (String) -> Unit,
    onSaveAnchor: (String?, Int) -> Unit,
    onCopied: () -> Unit,
    onOpenSettings: () -> Unit,
    onContinue: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var appliedAnchorEpoch by remember { mutableLongStateOf(-1L) }
    // 贴在底部时，输出变长就继续滚到末尾。手指拖动后松开，只有末尾仍在视口里才继续跟。
    var stickToBottom by remember { mutableStateOf(true) }
    var suppressStickUpdate by remember { mutableIntStateOf(0) }
    val scrollJob = remember { mutableStateOf<Job?>(null) }
    var scrollGeneration by remember { mutableIntStateOf(0) }
    val programmaticScroll = remember { mutableStateOf(false) }

    fun atEnd(): Boolean {
        val info = listState.layoutInfo
        if (info.totalItemsCount == 0) return true
        val last = info.visibleItemsInfo.lastOrNull() ?: return false
        return last.index == info.totalItemsCount - 1 &&
            last.offset + last.size <= info.viewportEndOffset + 48
    }

    fun distanceDuration(px: Float): Int = (px / 2.2f).toInt().coerceIn(220, 900)

    suspend fun scrollToEnd() {
        val count = listState.layoutInfo.totalItemsCount
        if (count <= 0) return
        suppressStickUpdate++
        listState.scrollToItem(count - 1)
        listState.scroll { scrollBy(1_000_000f) }
    }

    /** 回到底部按钮：滚到列表末尾。流式跟随仍走 [scrollToEnd]，避免动画和输出抢滚动。 */
    fun animateToEnd() {
        val token = ++scrollGeneration
        programmaticScroll.value = true
        stickToBottom = true
        scrollJob.value?.cancel()
        scrollJob.value = scope.launch {
            try {
                val count = listState.layoutInfo.totalItemsCount
                if (count <= 0) return@launch
                val info = listState.layoutInfo
                val tail = info.visibleItemsInfo.lastOrNull()
                if (tail == null || tail.index != count - 1) {
                    listState.animateScrollToItem(count - 1)
                }
                val after = listState.layoutInfo
                val last = after.visibleItemsInfo.lastOrNull()
                if (last != null && last.index == count - 1) {
                    val extra = (last.offset + last.size - after.viewportEndOffset).toFloat()
                    if (extra > 1f) {
                        listState.animateScrollBy(
                            extra,
                            tween(durationMillis = distanceDuration(extra), easing = FastOutSlowInEasing),
                        )
                    }
                }
                if (token == scrollGeneration) stickToBottom = true
            } finally {
                if (token == scrollGeneration) programmaticScroll.value = false
            }
        }
    }

    // 展开那一刻的列表位置。收起要回到这里，不能改成把这条消息顶到视口顶。
    val expandAnchor = remember { HashMap<String, Pair<Int, Int>>() }

    fun restoreExpandScroll(index: Int, offset: Int) {
        val token = ++scrollGeneration
        stickToBottom = false
        programmaticScroll.value = true
        suppressStickUpdate++
        scrollJob.value?.cancel()
        scrollJob.value = scope.launch {
            try {
                val count = listState.layoutInfo.totalItemsCount
                if (count <= 0) return@launch
                val target = index.coerceIn(0, count - 1)
                val targetOffset = offset.coerceAtLeast(0)
                val already = listState.firstVisibleItemIndex == target &&
                    listState.firstVisibleItemScrollOffset == targetOffset
                // 高度收起是 300ms。滚动和它一起走，结束后再钉一次，避免高度动画把视口带走。
                val deadline = System.nanoTime() + 320_000_000L
                if (!already) {
                    listState.animateScrollToItem(target, targetOffset)
                }
                while (token == scrollGeneration && System.nanoTime() < deadline) {
                    val n = listState.layoutInfo.totalItemsCount
                    if (n <= 0) break
                    val i = target.coerceIn(0, n - 1)
                    if (
                        listState.firstVisibleItemIndex != i ||
                        listState.firstVisibleItemScrollOffset != targetOffset
                    ) {
                        listState.scrollToItem(i, targetOffset)
                    }
                    withFrameNanos { }
                }
                if (token == scrollGeneration) {
                    val n = listState.layoutInfo.totalItemsCount
                    if (n > 0) {
                        val i = target.coerceIn(0, n - 1)
                        if (
                            listState.firstVisibleItemIndex != i ||
                            listState.firstVisibleItemScrollOffset != targetOffset
                        ) {
                            listState.scrollToItem(i, targetOffset)
                        }
                    }
                }
            } finally {
                if (token == scrollGeneration) {
                    programmaticScroll.value = false
                    suppressStickUpdate = 0
                }
            }
        }
    }

    fun onUserFold(messageId: String, expanding: Boolean) {
        if (expanding) {
            expandAnchor[messageId] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            stickToBottom = false
            scrollGeneration++
            scrollJob.value?.cancel()
            programmaticScroll.value = false
            suppressStickUpdate = 0
        } else {
            val saved = expandAnchor.remove(messageId) ?: return
            restoreExpandScroll(saved.first, saved.second)
        }
    }

    LaunchedEffect(ui.isStreaming) {
        if (ui.isStreaming) stickToBottom = true
    }

    val renderItems = buildRenderItems(ui)
    val emptyHome = renderItems.isEmpty() && !ui.isStreaming
    val anchorEpoch = ui.pendingScrollAnchorEpoch
    val anchorId = ui.pendingScrollAnchorId
    val anchorOffset = ui.pendingScrollAnchorOffset
    val draftTick = ui.streamingDraft?.blocks?.hashCode() ?: 0

    LaunchedEffect(anchorEpoch, anchorId) {
        if (anchorEpoch > 0 && anchorEpoch != appliedAnchorEpoch && anchorId != null && renderItems.isNotEmpty()) {
            val index = renderItems.indexOfFirst { it.id == anchorId }
            appliedAnchorEpoch = anchorEpoch
            if (index >= 0) {
                stickToBottom = false
                suppressStickUpdate++
                listState.scrollToItem(index + 1, anchorOffset)
            }
        }
    }

    LaunchedEffect(renderItems.size, draftTick, stickToBottom) {
        if (emptyHome || !stickToBottom || programmaticScroll.value || renderItems.isEmpty()) return@LaunchedEffect
        scrollToEnd()
    }

    // 同一条消息变高时（流式正文），条目数不变，靠最后一项高度再滚一次。
    LaunchedEffect(stickToBottom) {
        if (!stickToBottom) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount to (last?.size ?: 0)
        }.collect {
            if (stickToBottom && !programmaticScroll.value && !atEnd()) scrollToEnd()
        }
    }

    LaunchedEffect(listState) {
        var seenScroll = false
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) {
                    seenScroll = true
                    return@collect
                }
                if (!seenScroll) return@collect
                if (programmaticScroll.value) return@collect
                if (suppressStickUpdate > 0) {
                    suppressStickUpdate = 0
                    return@collect
                }
                stickToBottom = atEnd()
            }
    }

    var userScrolled by remember { mutableStateOf(false) }
    val currentItems by rememberUpdatedState(renderItems)
    val saveAnchor by rememberUpdatedState(onSaveAnchor)

    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) {
                    userScrolled = true
                } else if (userScrolled) {
                    val renderIndex = listState.firstVisibleItemIndex - 1
                    val offset = listState.firstVisibleItemScrollOffset
                    val anchorId = currentItems.getOrNull(renderIndex)?.id
                    saveAnchor(anchorId, offset)
                }
            }
    }

    Box(modifier.fillMaxWidth()) {
        if (emptyHome) {
            WelcomePlaceholder(
                hasApiKey = ui.hasApiKey,
                onOpenSettings = onOpenSettings,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val dragged = event.changes.any {
                                    it.pressed && it.previousPressed && it.positionChange() != androidx.compose.ui.geometry.Offset.Zero
                                }
                                if (dragged) {
                                    stickToBottom = false
                                    scrollJob.value?.cancel()
                                }
                            }
                        }
                    },
            ) {
                item(key = "header") { Spacer(Modifier.height(8.dp)) }
                itemsIndexed(renderItems, key = { _, item -> item.id }) { index, item ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        when (item) {
                            is RenderItem.Message -> MessageRow(
                                item = item.uiMessage,
                                streaming = ui.streamingDraft?.assistantMessageId == item.uiMessage.message.id,
                                isSpeaking = isSpeakingId == item.uiMessage.message.id,
                                onRegenerate = { onRegenerate(item.uiMessage.message.id) },
                                onSwitchVersion = { direction -> onSwitchVersion(item.uiMessage.message.id, direction) },
                                versionsEnabled = !ui.isStreaming,
                                onRequestEdit = { text -> onBeginEdit(item.uiMessage.message.id, text) },
                                onUserFold = { expanding -> onUserFold(item.uiMessage.message.id, expanding) },
                                onToggleTts = { onToggleTts(item.uiMessage.message.id) },
                                onCopied = onCopied,
                                autoCollapseThinking = ui.autoCollapseThinking,
                                onContinue = if (
                                    item.uiMessage.message.status == ReplyStatus.STOPPED &&
                                    item.uiMessage.message.id == renderItems.lastOrNull()?.id
                                ) {
                                    { onContinue(item.uiMessage.message.id) }
                                } else {
                                    null
                                },
                            )
                            is RenderItem.Stream -> StreamPlaceholder(
                                draft = item.draft,
                                autoCollapseThinking = ui.autoCollapseThinking,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                item(key = "footer") { Spacer(Modifier.height(8.dp)) }
            }

            if (!atEnd() && renderItems.isNotEmpty()) {
                Surface(
                    onClick = { animateToEnd() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp)
                        .size(34.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DsIcon(
                            UiR.drawable.ic_chevron_down_outline_20,
                            contentDescription = stringResource(UiR.string.scroll_to_bottom),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

private sealed interface RenderItem {
    val id: String

    data class Message(val uiMessage: com.david.deepdesk.feature.chat.UiMessage) : RenderItem {
        override val id: String get() = uiMessage.message.id
    }

    data class Stream(val draft: com.david.deepdesk.feature.chat.engine.ChatOrchestrator.DraftUi) : RenderItem {
        override val id: String get() = draft.assistantMessageId
    }
}

private fun buildRenderItems(ui: ChatUiState): List<RenderItem> {
    val items = mutableListOf<RenderItem>()
    val draft = ui.streamingDraft
    ui.messages.forEach { msg ->
        if (draft != null && draft.assistantMessageId == msg.message.id) {
            items.add(RenderItem.Message(msg.copy(blocks = draft.blocks)))
        } else {
            items.add(RenderItem.Message(msg))
        }
    }
    if (draft != null && ui.messages.none { it.message.id == draft.assistantMessageId }) {
        items.add(RenderItem.Stream(draft))
    }
    return items
}

@Composable
private fun StreamPlaceholder(
    draft: com.david.deepdesk.feature.chat.engine.ChatOrchestrator.DraftUi,
    autoCollapseThinking: Boolean,
) {
    ReplyBlocks(blocks = draft.blocks, streaming = true, autoCollapseThinking = autoCollapseThinking)
}

@Composable
private fun WelcomePlaceholder(
    hasApiKey: Boolean,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(UiR.drawable.ic_brand_mark),
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp)),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(UiR.string.welcome_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (!hasApiKey) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(UiR.string.welcome_need_key),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(UiR.string.welcome_fill_key))
            }
            Text(
                text = "也可在设置中导入官网导出的对话 ZIP。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun cameraOutputUri(context: android.content.Context): android.net.Uri {
    val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
    val file = java.io.File(dir, "photo_${System.currentTimeMillis()}.jpg")
    return androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}
