package com.dsh.deepseek.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsh.deepseek.core.ui.R as UiR
import com.dsh.deepseek.core.database.DailyUsageRow
import com.dsh.deepseek.core.datastore.CachedBalance
import com.dsh.deepseek.core.datastore.formatBalanceAmount
import com.dsh.deepseek.core.model.formatCny
import com.dsh.deepseek.core.model.formatPercent
import com.dsh.deepseek.core.model.formatTokenCount
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TokenStatsScreen(
    viewModel: TokenStatsViewModel,
    onBack: () -> Unit,
) {
    val balance by viewModel.balance.collectAsState()
    val days by viewModel.daily.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val showSessionUsage by viewModel.showSessionUsageChip.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    SettingsPageScaffold(title = stringResource(UiR.string.usage_stats), onBack = onBack) {
        BalanceCard(
            balance = balance,
            refreshing = refreshing,
            onRefresh = viewModel::refresh,
        )
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            SwitchRow(
                title = stringResource(UiR.string.show_session_usage),
                checked = showSessionUsage,
                onChange = viewModel::setShowSessionUsageChip,
                subtitle = stringResource(UiR.string.show_session_usage_sub),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(UiR.string.daily_usage),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 8.dp),
        )
        if (days.isEmpty()) {
            SettingsCard {
                Text(
                    stringResource(UiR.string.daily_usage_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            SettingsCard {
                days.forEachIndexed { index, row ->
                    if (index > 0) GroupDivider()
                    DailyUsageItem(row)
                }
            }
        }
        Text(
            stringResource(UiR.string.pricing_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun BalanceCard(
    balance: CachedBalance,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    SettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(UiR.string.deepseek_balance),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefresh, enabled = !refreshing) {
                    Text(stringResource(if (refreshing) UiR.string.refreshing else UiR.string.refresh))
                }
            }
            val amount = balance.total
            Text(
                text = when {
                    amount != null -> formatBalanceAmount(balance.currency, amount)
                    balance.error != null -> "—"
                    else -> "—"
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
            )
            if (amount != null) {
                val granted = balance.granted
                val topped = balance.toppedUp
                val extras = buildList {
                    if (!granted.isNullOrBlank() && granted != "0" && granted != "0.00") {
                        add(stringResource(UiR.string.granted_amount, formatBalanceAmount(balance.currency, granted)))
                    }
                    if (!topped.isNullOrBlank()) {
                        add(stringResource(UiR.string.topped_up_amount, formatBalanceAmount(balance.currency, topped)))
                    }
                    if (!balance.available) add(stringResource(UiR.string.balance_unavailable))
                }
                if (extras.isNotEmpty()) {
                    Text(
                        extras.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            balance.error?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DailyUsageItem(row: DailyUsageRow) {
    val input = row.cacheHitTokens + row.cacheMissTokens
    val total = input + row.outputTokens
    val hitRate = if (input == 0L) 0.0 else row.cacheHitTokens.toDouble() / input
    val costCny = row.estimatedCostCnyMicros / 1_000_000.0
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatDayLabel(row.dayKey),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatCny(costCny),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            buildString {
                append(stringResource(UiR.string.usage_requests, row.requestCount, formatTokenCount(total)))
                if (input > 0 && row.cacheHitTokens > 0) {
                    append("  ·  ")
                    append(stringResource(UiR.string.cache_hit_rate, formatPercent(hitRate)))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                UiR.string.usage_breakdown,
                formatTokenCount(row.cacheMissTokens),
                formatTokenCount(row.cacheHitTokens),
                formatTokenCount(row.outputTokens),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun formatDayLabel(dayKey: String): String {
    val date = runCatching { LocalDate.parse(dayKey) }.getOrNull() ?: return dayKey
    val today = LocalDate.now()
    val pattern = stringResource(UiR.string.date_md_eee)
    return when (date) {
        today -> stringResource(UiR.string.date_today)
        today.minusDays(1) -> stringResource(UiR.string.date_yesterday)
        else -> date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }
}
