package com.dsh.deepseek.core.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** 聊天页中央提示（和「已复制」「未识别到内容」同一组件）。未提供时调用无效果。 */
val LocalCenterHint = staticCompositionLocalOf<(String) -> Unit> { {} }
