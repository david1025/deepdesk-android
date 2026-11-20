package com.david.deepdesk.core.datastore

import com.david.deepdesk.core.common.AppLog
import com.david.deepdesk.core.network.DeepSeekApiException
import com.david.deepdesk.core.network.DeepSeekRestApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 拉取并缓存 GET /user/balance。侧栏与用量页共用同一份快照。
 */
class BalanceRepository(
    private val restApi: DeepSeekRestApi,
    private val settingsStore: SettingsStore,
    private val hasApiKey: () -> Boolean,
) {
    val cached: Flow<CachedBalance> = settingsStore.settings
        .map { it.cachedBalance }
        .distinctUntilChanged()

    suspend fun refresh(): CachedBalance {
        if (!hasApiKey()) {
            val empty = CachedBalance(error = "未配置 API Key", fetchedAt = System.currentTimeMillis())
            settingsStore.setCachedBalance(empty)
            return empty
        }
        return try {
            val resp = restApi.getBalance()
            val info = resp.balanceInfos.firstOrNull { it.currency.equals("CNY", ignoreCase = true) }
                ?: resp.balanceInfos.firstOrNull()
            val snap = CachedBalance(
                available = resp.isAvailable == true,
                currency = info?.currency ?: "CNY",
                total = info?.totalBalance,
                granted = info?.grantedBalance,
                toppedUp = info?.toppedUpBalance,
                fetchedAt = System.currentTimeMillis(),
            )
            settingsStore.setCachedBalance(snap)
            snap
        } catch (e: DeepSeekApiException) {
            AppLog.w(TAG, "余额拉取失败: ${e.httpStatus}")
            val snap = CachedBalance(
                error = e.message ?: "余额获取失败",
                fetchedAt = System.currentTimeMillis(),
            )
            settingsStore.setCachedBalance(mergeKeepAmount(snap))
            snap
        } catch (e: Exception) {
            AppLog.w(TAG, "余额拉取失败: ${e.message}")
            val snap = CachedBalance(
                error = e.message ?: "网络错误",
                fetchedAt = System.currentTimeMillis(),
            )
            settingsStore.setCachedBalance(mergeKeepAmount(snap))
            snap
        }
    }

    private suspend fun mergeKeepAmount(errorSnap: CachedBalance): CachedBalance {
        val prev = settingsStore.settings.first().cachedBalance
        return if (prev.hasAmount) {
            prev.copy(error = errorSnap.error, fetchedAt = errorSnap.fetchedAt)
        } else {
            errorSnap
        }
    }

    companion object {
        private const val TAG = "Balance"
    }
}

fun formatBalanceAmount(currency: String, amount: String): String {
    val symbol = if (currency.equals("USD", ignoreCase = true)) "$" else "¥"
    val n = amount.toDoubleOrNull()
    return if (n != null) "$symbol${String.format("%.2f", n)}" else "$symbol$amount"
}
