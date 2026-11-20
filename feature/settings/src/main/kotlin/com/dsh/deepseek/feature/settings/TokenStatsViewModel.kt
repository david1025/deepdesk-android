package com.dsh.deepseek.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.deepseek.core.database.DailyUsageRow
import com.dsh.deepseek.core.database.UsageDao
import com.dsh.deepseek.core.datastore.BalanceRepository
import com.dsh.deepseek.core.datastore.CachedBalance
import com.dsh.deepseek.core.datastore.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TokenStatsViewModel(
    private val balanceRepository: BalanceRepository,
    usageDao: UsageDao,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val balance: StateFlow<CachedBalance> = balanceRepository.cached
        .stateIn(viewModelScope, SharingStarted.Eagerly, CachedBalance())

    val daily: StateFlow<List<DailyUsageRow>> = usageDao.observeDaily()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val showSessionUsageChip: StateFlow<Boolean> = settingsStore.settings
        .map { it.showSessionUsageChip }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { balanceRepository.refresh() }
            _refreshing.value = false
        }
    }

    fun setShowSessionUsageChip(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setShowSessionUsageChip(enabled) }
    }
}
