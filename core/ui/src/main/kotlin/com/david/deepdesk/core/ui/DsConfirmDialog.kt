package com.david.deepdesk.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 官方确认弹框：标题和说明居中，下面是通栏胶囊按钮。
 * 危险操作用红色，其余用品牌蓝。颜色取自主题，浅色深色都可读。
 * [dismissLabel] 为空时只显示确认按钮（例如「知道了」）。
 */
@Composable
fun DsConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String? = null,
) {
    val pill = if (destructive) DeepSeekColors.PillDanger else DeepSeekColors.PillBlue
    val card = if (LocalDeepSeekDark.current) MaterialTheme.colorScheme.surfaceContainer else Color.White
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = card,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = 24.dp,
                        end = 24.dp,
                        top = 28.dp,
                        bottom = if (dismissLabel == null) 24.dp else 8.dp,
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = message,
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = if (LocalDeepSeekDark.current) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                Color(0xFF9A9A9E)
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(22.dp))
                    DsPillButton(
                        text = confirmLabel,
                        onClick = onConfirm,
                        containerColor = pill,
                    )
                    if (dismissLabel != null) {
                        Text(
                            text = dismissLabel,
                            color = if (LocalDeepSeekDark.current) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                Color(0xFF8E8E93)
                            },
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onDismiss,
                                )
                                .padding(vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
