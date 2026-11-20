package com.dsh.deepseek

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DismissibleDrawerSheet
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dsh.deepseek.core.ui.R as UiR
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dsh.deepseek.feature.chat.ChatViewModel
import com.dsh.deepseek.feature.chat.ui.ChatScreen
import com.dsh.deepseek.feature.history.HistoryBackupButtons
import com.dsh.deepseek.feature.history.HistoryBackupHost
import com.dsh.deepseek.feature.history.HistoryViewModel
import com.dsh.deepseek.feature.history.SessionsDrawer
import com.dsh.deepseek.core.datastore.formatBalanceAmount
import com.dsh.deepseek.feature.settings.SettingsScreen
import com.dsh.deepseek.feature.settings.SettingsViewModel
import com.dsh.deepseek.feature.settings.TokenStatsScreen
import com.dsh.deepseek.feature.settings.TokenStatsViewModel
import kotlinx.coroutines.launch

/**
 * 导航图：聊天主页（抽屉）↔ 设置。单 Activity（§3.1）。
 */
@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 历史抽屉 VM 与聊天 VM
    val historyFactory = remember {
        viewModelFactory {
            initializer { HistoryViewModel(container.chatRepository, container.historyImporterExporter) }
        }
    }
    val historyViewModel: HistoryViewModel = viewModel(factory = historyFactory, key = "history")

    val chatFactory = remember {
        viewModelFactory {
            initializer { ChatViewModel(container.chatServices) }
        }
    }
    val usageFactory = remember {
        viewModelFactory {
            initializer {
                TokenStatsViewModel(
                    balanceRepository = container.balanceRepository,
                    usageDao = container.database.usageDao(),
                    settingsStore = container.settingsStore,
                )
            }
        }
    }
    val usageViewModel: TokenStatsViewModel = viewModel(factory = usageFactory, key = "usage")
    val cachedBalance by usageViewModel.balance.collectAsState()
    val balanceLabel = cachedBalance.total?.let { formatBalanceAmount(cachedBalance.currency, it) }
        ?: cachedBalance.error
        ?: if (!container.apiKeyStore.isConfigured()) stringResource(UiR.string.not_configured) else "—"

    fun openUsage() {
        scope.launch { drawerState.close() }
        navController.navigate("usage") { launchSingleTop = true }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = "chat/new",
        ) {
            composable(
                route = "chat/{sessionId}",
                enterTransition = {
                    slideInHorizontally(animationSpec = tween(300)) { -it / 4 }
                },
                exitTransition = {
                    slideOutHorizontally(animationSpec = tween(300)) { -it / 4 }
                },
                popEnterTransition = {
                    slideInHorizontally(animationSpec = tween(300)) { -it / 4 }
                },
                popExitTransition = {
                    slideOutHorizontally(animationSpec = tween(300)) { it }
                },
            ) { entry ->
                val sessionId = entry.arguments?.getString("sessionId") ?: "new"
                // Activity 级单例 VM：生成/录音任务跨导航连续（§3.3），会话切换由 loadSession 承担
                val owner = LocalContext.current as ViewModelStoreOwner
                val chatViewModel: ChatViewModel = viewModel(
                    viewModelStoreOwner = owner,
                    key = "chat_main",
                    factory = chatFactory,
                )
                // 抽屉高亮以 VM 中真实会话 ID 为准：发出首条消息后路由仍是 chat/new，
                // 只有 VM 里的 sessionId 才是实际落库的会话，避免高亮丢失。
                val chatUiState by chatViewModel.ui.collectAsState()
                val activeSessionId = chatUiState.sessionId ?: sessionId.takeIf { it != "new" }
                LaunchedEffect(drawerState.isOpen) {
                    if (drawerState.isOpen) usageViewModel.refresh()
                }

                val screenWidthDp = LocalConfiguration.current.screenWidthDp
                DismissibleNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        DismissibleDrawerSheet(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width((screenWidthDp - 48).dp),
                            drawerShape = RectangleShape,
                            drawerContainerColor = MaterialTheme.colorScheme.background,
                            drawerTonalElevation = 0.dp,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            SessionsDrawer(
                                historyViewModel = historyViewModel,
                                activeSessionId = activeSessionId,
                                onSelectSession = { id ->
                                    scope.launch { drawerState.close() }
                                    navController.navigate("chat/$id") {
                                        popUpTo("chat/{sessionId}") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                                onOpenSettings = {
                                    scope.launch { drawerState.close() }
                                    navController.navigate("settings")
                                },
                                balanceLabel = balanceLabel,
                                onOpenUsage = { openUsage() },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        ChatScreen(
                            viewModel = chatViewModel,
                            sessionId = sessionId,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenSettings = {
                                scope.launch { drawerState.close() }
                                navController.navigate("settings")
                            },
                            onNewChat = {
                                chatViewModel.newSession()
                                navController.navigate("chat/new") {
                                    popUpTo("chat/{sessionId}") { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                        )
                        if (drawerState.isOpen) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                    ) { scope.launch { drawerState.close() } },
                            )
                        }
                    }
                }
            }

            composable(
                route = "settings",
                enterTransition = {
                    slideInHorizontally(animationSpec = tween(300)) { it }
                },
                exitTransition = {
                    slideOutHorizontally(animationSpec = tween(300)) { it }
                },
                popEnterTransition = {
                    slideInHorizontally(animationSpec = tween(300)) { -it / 4 }
                },
                popExitTransition = {
                    slideOutHorizontally(animationSpec = tween(300)) { it }
                },
            ) {
                val settingsFactory = remember {
                    viewModelFactory {
                        initializer {
                            SettingsViewModel(
                                appContext = container.appContext,
                                apiKeyStore = container.apiKeyStore,
                                settingsStore = container.settingsStore,
                                restApi = container.restApi,
                                clearAllDataAction = { container.clearAllData() },
                            )
                        }
                    }
                }
                val settingsViewModel: SettingsViewModel = viewModel(factory = settingsFactory, key = "settings")
                val owner = LocalContext.current as ViewModelStoreOwner
                val chatViewModel: ChatViewModel = viewModel(
                    viewModelStoreOwner = owner,
                    key = "chat_main",
                    factory = chatFactory,
                )
                SettingsScreen(
                    viewModel = settingsViewModel,
                    usageViewModel = usageViewModel,
                    onBack = { navController.popBackStack() },
                    backupSection = {
                        HistoryBackupHost(
                            historyViewModel = historyViewModel,
                            onImported = { chatViewModel.newSession() },
                        ) { onImport, onExport, busy ->
                            HistoryBackupButtons(
                                onImport = onImport,
                                onExport = onExport,
                                busy = busy,
                            )
                        }
                    },
                )
            }

            composable(
                route = "usage",
                enterTransition = {
                    slideInHorizontally(animationSpec = tween(300)) { it }
                },
                exitTransition = {
                    slideOutHorizontally(animationSpec = tween(300)) { it }
                },
                popEnterTransition = {
                    slideInHorizontally(animationSpec = tween(300)) { -it / 4 }
                },
                popExitTransition = {
                    slideOutHorizontally(animationSpec = tween(300)) { it }
                },
            ) {
                TokenStatsScreen(
                    viewModel = usageViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
