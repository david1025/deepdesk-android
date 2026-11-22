package com.david.deepdesk.feature.settings

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.david.deepdesk.core.ui.DeepSeekColors
import com.david.deepdesk.core.ui.DsMenuCard
import com.david.deepdesk.core.ui.DsPillButton
import com.david.deepdesk.core.ui.DsSwitch
import com.david.deepdesk.core.ui.LocalDeepSeekDark
import com.david.deepdesk.core.ui.settingsCardColor
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.david.deepdesk.core.datastore.Language
import com.david.deepdesk.core.datastore.ThemeMode
import com.david.deepdesk.core.ui.DsConfirmDialog
import com.david.deepdesk.core.ui.DsIcon
import com.david.deepdesk.core.ui.R as UiR

internal object SettingsRoutes {
    const val Home = "home"
    const val ApiKey = "api_key"
    const val Data = "data"
    const val Appearance = "appearance"
    const val Language = "language"
    const val Voice = "voice"
    const val Personalize = "personalize"
    const val Usage = "usage"
    const val About = "about"
}

/**
 * 设置页：分组卡片 + 行尾箭头，对齐官方 App 设置风格。
 * 账号管理对应本机 API Key（不登录官方账号）。
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    usageViewModel: TokenStatsViewModel,
    onBack: () -> Unit,
    backupSection: @Composable () -> Unit = {},
    startRoute: String = SettingsRoutes.Home,
    onPreviewVoice: (String) -> Unit = {},
) {
    val keyState by viewModel.keyState.collectAsState()
    val settings by viewModel.settings.collectAsState()

    val navController = rememberNavController()
    var keyInput by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showClearDataConfirm by remember { mutableStateOf(false) }

    fun open(route: String) {
        navController.navigate(route) { launchSingleTop = true }
    }

    val pageBg = if (LocalDeepSeekDark.current) MaterialTheme.colorScheme.background else Color(0xFFF2F2F2)
    Box(
        Modifier
            .fillMaxSize()
            .background(pageBg),
    ) {
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { slideInHorizontally(animationSpec = tween(300)) { it } },
            exitTransition = { slideOutHorizontally(animationSpec = tween(300)) { -it / 4 } },
            popEnterTransition = { slideInHorizontally(animationSpec = tween(300)) { -it / 4 } },
            popExitTransition = { slideOutHorizontally(animationSpec = tween(300)) { it } },
        ) {
            composable(SettingsRoutes.Home) {
                SettingsPageScaffold(title = stringResource(UiR.string.settings), onBack = onBack) {
                    SettingsHome(
                        keyConfigured = keyState.configured,
                        languageLabel = languageLabel(settings.language),
                        themeLabel = themeLabel(settings.themeMode),
                        voiceLabel = voiceLabel(settings.voiceId),
                        professionalOn = settings.professionalMode,
                        onOpen = { open(it) },
                    )
                }
            }
            composable(SettingsRoutes.ApiKey) {
                SettingsPageScaffold(title = stringResource(UiR.string.api_key), onBack = { navController.popBackStack() }) {
                    ApiKeyPage(
                        keyState = keyState,
                        keyInput = keyInput,
                        onKeyInput = { keyInput = it },
                        onSave = { viewModel.saveKey(keyInput) },
                        onSaveAndTest = {
                            viewModel.saveKey(keyInput)
                            viewModel.testKey()
                        },
                        onTest = viewModel::testKey,
                        onDelete = { showDeleteConfirm = true },
                    )
                }
            }
            composable(SettingsRoutes.Data) {
                SettingsPageScaffold(title = stringResource(UiR.string.data_management), onBack = { navController.popBackStack() }) {
                    DataPage(
                        backupSection = backupSection,
                        onClearAll = { showClearDataConfirm = true },
                    )
                }
            }
            composable(SettingsRoutes.Appearance) {
                SettingsPageScaffold(title = stringResource(UiR.string.appearance), onBack = { navController.popBackStack() }) {
                    OptionPage(
                        options = listOf(
                            stringResource(UiR.string.theme_follow_system) to ThemeMode.SYSTEM,
                            stringResource(UiR.string.theme_light) to ThemeMode.LIGHT,
                            stringResource(UiR.string.theme_dark) to ThemeMode.DARK,
                        ),
                        selected = settings.themeMode,
                        onSelect = viewModel::setTheme,
                    )
                }
            }
            composable(SettingsRoutes.Language) {
                SettingsPageScaffold(title = stringResource(UiR.string.language), onBack = { navController.popBackStack() }) {
                    OptionPage(
                        options = listOf(
                            stringResource(UiR.string.language_system) to Language.SYSTEM,
                            stringResource(UiR.string.language_zh) to Language.ZH,
                            stringResource(UiR.string.language_en) to Language.EN,
                        ),
                        selected = settings.language,
                        onSelect = viewModel::setLanguage,
                    )
                }
            }
            composable(SettingsRoutes.Voice) {
                SettingsPageScaffold(title = stringResource(UiR.string.voice_tone), onBack = { navController.popBackStack() }) {
                    VoicePickerPage(
                        selectedId = settings.voiceId,
                        canPreview = keyState.configured,
                        onPreview = onPreviewVoice,
                        onConfirm = { id ->
                            viewModel.setVoice(id)
                            navController.popBackStack()
                        },
                    )
                }
            }
            composable(SettingsRoutes.Personalize) {
                SettingsPageScaffold(title = stringResource(UiR.string.personalize), onBack = { navController.popBackStack() }) {
                    PersonalizePage(
                        thinking = settings.thinkingEnabledByDefault,
                        webSearch = settings.webSearchEnabledByDefault,
                        autoCollapse = settings.autoCollapseThinking,
                        professional = settings.professionalMode,
                        onThinking = viewModel::setThinkingDefault,
                        onWebSearch = viewModel::setSearchDefault,
                        onAutoCollapse = viewModel::setAutoCollapseThinking,
                        onProfessional = viewModel::setProfessionalMode,
                    )
                }
            }
            composable(SettingsRoutes.Usage) {
                TokenStatsScreen(
                    viewModel = usageViewModel,
                    onBack = {
                        if (!navController.popBackStack()) onBack()
                    },
                )
            }
            composable(SettingsRoutes.About) {
                SettingsPageScaffold(title = stringResource(UiR.string.about), onBack = { navController.popBackStack() }) {
                    AboutPage()
                }
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = stringResource(UiR.string.delete_api_key_title),
            message = stringResource(UiR.string.delete_api_key_message),
            confirmLabel = stringResource(UiR.string.delete),
            onConfirm = {
                viewModel.deleteKey()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
    if (showClearDataConfirm) {
        ConfirmDialog(
            title = stringResource(UiR.string.delete_all_sessions_title),
            message = stringResource(UiR.string.delete_all_sessions_message),
            confirmLabel = stringResource(UiR.string.delete_all),
            onConfirm = {
                viewModel.clearAllData(onDone = {})
                showClearDataConfirm = false
            },
            onDismiss = { showClearDataConfirm = false },
        )
    }
}

@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pageBg = if (LocalDeepSeekDark.current) MaterialTheme.colorScheme.background else Color(0xFFF2F2F2)
    Column(
        Modifier
            .fillMaxSize()
            .background(pageBg)
            .systemBarsPadding(),
    ) {
        SettingsTopBar(title = title, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsHome(
    keyConfigured: Boolean,
    languageLabel: String,
    themeLabel: String,
    voiceLabel: String,
    professionalOn: Boolean,
    onOpen: (String) -> Unit,
) {
    SettingsGroup(stringResource(UiR.string.group_account)) {
        SettingsRow(
            icon = UiR.drawable.ic_user_regular,
            title = stringResource(UiR.string.api_key),
            value = stringResource(if (keyConfigured) UiR.string.api_key_configured else UiR.string.api_key_missing),
            onClick = { onOpen(SettingsRoutes.ApiKey) },
        )
        GroupDivider()
        SettingsRow(
            icon = UiR.drawable.ic_data_regular,
            title = stringResource(UiR.string.data_management),
            onClick = { onOpen(SettingsRoutes.Data) },
        )
        GroupDivider()
        SettingsRow(
            icon = UiR.drawable.ic_chart_outline_20,
            title = stringResource(UiR.string.usage_stats),
            onClick = { onOpen(SettingsRoutes.Usage) },
        )
    }
    SettingsGroup(stringResource(UiR.string.group_app)) {
        SettingsRow(
            icon = UiR.drawable.ic_globe_language_outline_20,
            title = stringResource(UiR.string.language),
            value = languageLabel,
            onClick = { onOpen(SettingsRoutes.Language) },
        )
        GroupDivider()
        SettingsRow(
            icon = UiR.drawable.ic_moon_regular,
            title = stringResource(UiR.string.appearance),
            value = themeLabel,
            onClick = { onOpen(SettingsRoutes.Appearance) },
        )
        GroupDivider()
        SettingsRow(
            icon = UiR.drawable.ic_think_outline_16,
            title = stringResource(UiR.string.personalize),
            value = if (professionalOn) stringResource(UiR.string.professional_on) else null,
            onClick = { onOpen(SettingsRoutes.Personalize) },
        )
    }
    SettingsGroup(stringResource(UiR.string.group_voice)) {
        SettingsRow(
            icon = UiR.drawable.ic_play_media_outline_20,
            title = stringResource(UiR.string.voice_tone),
            value = voiceLabel,
            onClick = { onOpen(SettingsRoutes.Voice) },
        )
    }
    SettingsGroup(stringResource(UiR.string.group_about)) {
        SettingsRow(
            icon = UiR.drawable.ic_info_outline,
            title = stringResource(UiR.string.about),
            value = stringResource(UiR.string.app_version_short),
            onClick = { onOpen(SettingsRoutes.About) },
        )
    }
}

@Composable
private fun ApiKeyPage(
    keyState: SettingsViewModel.KeyState,
    keyInput: String,
    onKeyInput: (String) -> Unit,
    onSave: () -> Unit,
    onSaveAndTest: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    Text(
        stringResource(UiR.string.api_key_intro),
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
    )
    SettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!keyState.configured) {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = onKeyInput,
                    label = { Text("sk-…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(stringResource(UiR.string.api_key_saved_hidden), style = MaterialTheme.typography.bodyMedium)
            }
            keyState.testResult?.let { result ->
                Text(
                    result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (keyState.testOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    if (!keyState.configured) {
        DsPillButton(
            text = stringResource(UiR.string.save),
            onClick = onSave,
            enabled = keyInput.isNotBlank(),
        )
        Spacer(Modifier.height(12.dp))
        DsPillButton(
            text = stringResource(UiR.string.save_and_verify),
            onClick = onSaveAndTest,
            enabled = keyInput.isNotBlank(),
        )
    } else {
        DsPillButton(
            text = stringResource(if (keyState.testing) UiR.string.verifying else UiR.string.verify_connectivity),
            onClick = onTest,
            enabled = !keyState.testing,
        )
        Spacer(Modifier.height(12.dp))
        DsMenuCard(
            title = stringResource(UiR.string.delete_key),
            onClick = onDelete,
            titleColor = DeepSeekColors.PillDanger,
        )
    }
}

@Composable
private fun DataPage(
    backupSection: @Composable () -> Unit,
    onClearAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        backupSection()
        Text(
            stringResource(UiR.string.data_intro),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        DsMenuCard(
            title = stringResource(UiR.string.delete_all_sessions),
            onClick = onClearAll,
            titleColor = DeepSeekColors.PillDanger,
        )
    }
}

@Composable
private fun <T> OptionPage(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        options.forEach { (label, value) ->
            val chosen = selected == value
            DsMenuCard(
                title = label,
                onClick = { onSelect(value) },
                trailing = if (chosen) {
                    {
                        DsIcon(
                            UiR.drawable.ic_check_outline_20,
                            contentDescription = null,
                            tint = DeepSeekColors.PillBlue,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun PersonalizePage(
    thinking: Boolean,
    webSearch: Boolean,
    autoCollapse: Boolean,
    professional: Boolean,
    onThinking: (Boolean) -> Unit,
    onWebSearch: (Boolean) -> Unit,
    onAutoCollapse: (Boolean) -> Unit,
    onProfessional: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SwitchRow(
            title = stringResource(UiR.string.professional_mode),
            checked = professional,
            onChange = onProfessional,
            subtitle = stringResource(UiR.string.professional_mode_sub),
        )
        if (!professional) {
            SwitchRow(stringResource(UiR.string.default_thinking), thinking, onThinking)
        }
        SwitchRow(stringResource(UiR.string.default_search), webSearch, onWebSearch)
        SwitchRow(
            title = stringResource(UiR.string.auto_collapse_thinking),
            checked = autoCollapse,
            onChange = onAutoCollapse,
            subtitle = stringResource(UiR.string.auto_collapse_thinking_sub),
        )
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        DsMenuCard(
            title = title,
            onClick = { onChange(!checked) },
            trailing = { DsSwitch(checked = checked, onCheckedChange = onChange) },
        )
        if (subtitle != null) {
            Text(
                subtitle,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp),
            )
        }
    }
}

@Composable
private fun AboutPage() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Image(
                painter = painterResource(UiR.drawable.ic_brand_mark),
                contentDescription = stringResource(UiR.string.brand_name),
                modifier = Modifier
                    .size(96.dp)
                    .padding(10.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(UiR.string.brand_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(UiR.string.brand_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(UiR.string.app_version),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        SettingsCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(UiR.string.about_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(UiR.string.about_license),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(48.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = settingsCardColor(),
            modifier = Modifier
                .size(40.dp)
                .align(Alignment.CenterStart)
                .clickable(onClick = onBack),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                DsIcon(
                    UiR.drawable.ic_chevron_left_outline_20,
                    contentDescription = stringResource(UiR.string.back),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun SettingsGroup(label: String, content: @Composable () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
    )
    SettingsCard(content)
}

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = settingsCardColor(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

/** 设置菜单图标：矢量视口不一（16/20/24），按视口校正后放进同一 20.dp 槽。 */
@Composable
private fun SettingsMenuIcon(@androidx.annotation.DrawableRes icon: Int) {
    val visual = when (icon) {
        UiR.drawable.ic_user_regular,
        UiR.drawable.ic_data_regular,
        UiR.drawable.ic_moon_regular,
        UiR.drawable.ic_info_outline,
        -> 24.dp
        UiR.drawable.ic_think_outline_16 -> 18.dp
        else -> 20.dp
    }
    Box(
        modifier = Modifier.size(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        DsIcon(icon, contentDescription = null, modifier = Modifier.size(visual))
    }
}

@Composable
private fun SettingsRow(
    @androidx.annotation.DrawableRes icon: Int,
    title: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        SettingsMenuIcon(icon)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
        }
        DsIcon(
            UiR.drawable.ic_chevron_right_outline_20,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 52.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}

@Composable
private fun themeLabel(mode: ThemeMode) = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> UiR.string.theme_system
        ThemeMode.LIGHT -> UiR.string.theme_light
        ThemeMode.DARK -> UiR.string.theme_dark
    },
)

@Composable
private fun languageLabel(language: Language) = stringResource(
    when (language) {
        Language.SYSTEM -> UiR.string.language_system
        Language.ZH -> UiR.string.language_zh
        Language.EN -> UiR.string.language_en
    },
)

@Composable
private fun voiceLabel(id: String) = stringResource(
    if (id == "tide") UiR.string.voice_tide else UiR.string.voice_stella,
)

private data class VoiceChoice(
    val apiId: String,
    val nameRes: Int,
    val descRes: Int,
    val color: Color,
)

/** 公开 TTS 接口只有这两个 voice_id。 */
private val VoiceChoices = listOf(
    VoiceChoice("stella", UiR.string.voice_stella, UiR.string.voice_stella_desc, Color(0xFFFFB6C9)),
    VoiceChoice("tide", UiR.string.voice_tide, UiR.string.voice_tide_desc, Color(0xFF9AADC9)),
)

@Composable
private fun VoicePickerPage(
    selectedId: String,
    canPreview: Boolean,
    onPreview: (String) -> Unit,
    onConfirm: (String) -> Unit,
) {
    val initial = VoiceChoices.indexOfFirst { it.apiId == selectedId }.let { if (it < 0) 0 else it }
    val pagerState = rememberPagerState(initialPage = initial, pageCount = { VoiceChoices.size })
    val scope = rememberCoroutineScope()
    LaunchedEffect(pagerState, canPreview) {
        var first = true
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (first) {
                first = false
                return@collect
            }
            val apiId = VoiceChoices.getOrNull(page)?.apiId ?: return@collect
            if (canPreview) onPreview(apiId)
        }
    }
    val page = pagerState.currentPage.coerceIn(0, VoiceChoices.lastIndex)
    val current = VoiceChoices[page]
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 48.dp),
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
        ) { index ->
            VoiceCard(VoiceChoices[index])
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        ) {
            VoiceChoices.indices.forEach { index ->
                val selected = index == page
                Box(
                    Modifier
                        .size(if (selected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (selected) DeepSeekColors.BrandBlue else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                        .clickable { scope.launch { pagerState.animateScrollToPage(index) } },
                )
            }
        }
        DsPillButton(
            text = stringResource(UiR.string.voice_confirm),
            onClick = { onConfirm(current.apiId) },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun VoiceCard(voice: VoiceChoice) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 168.dp, height = 210.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(
                    Brush.radialGradient(
                        listOf(voice.color, voice.color.copy(alpha = 0.45f), voice.color.copy(alpha = 0.12f)),
                    ),
                ),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(voice.nameRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(voice.descRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DsConfirmDialog(
        title = title,
        message = message,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        dismissLabel = stringResource(UiR.string.cancel),
    )
}
