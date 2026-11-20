package com.david.deepdesk

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.david.deepdesk.core.ui.AppLocales
import com.david.deepdesk.core.ui.DeepSeekTheme
import com.david.deepdesk.feature.voice.recording.RecordingController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 单 Activity + Compose Navigation（§1.2）。
 * AppCompatActivity 以支持按应用语言持久化（AppCompat per-app locales）。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as DeepSeekApplication).container

        // 语言持久化走 AppCompat per-app locales；DataStore 仅记录偏好用于 UI 选中态
        lifecycleScope.launch {
            container.settingsStore.settings
                .map { it.language }
                .distinctUntilChanged()
                .collect { language ->
                    AppLocales.apply(language)
                }
        }

        setContent {
            val settings by container.settingsStore.settings.collectAsState(
                initial = com.david.deepdesk.core.datastore.AppSettings(),
            )
            DeepSeekTheme(themeMode = settings.themeMode) {
                AppNavHost(container = container)
            }
        }
    }

    /** 切后台立即释放麦克风并取消进行中的 ASR（§7.2 / AT-10） */
    override fun onStop() {
        val container = (application as DeepSeekApplication).container
        val recording = container.recordingController.state.value
        if (recording is RecordingController.State.Recording ||
            recording is RecordingController.State.Processing
        ) {
            container.asrExecutor.cancelActive()
            container.recordingController.cancel()
        }
        super.onStop()
    }
}
