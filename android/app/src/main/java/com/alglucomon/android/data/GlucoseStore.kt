package com.alglucomon.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GlucoseUiState(
    val readings: List<GlucoseReading> = emptyList(),
    val isLoading: Boolean = false,
    val errorMsg: String? = null,
    val lastUpdatedMs: Long = 0L,
)

private data class PollKey(
    val signedIn: Boolean,
    val region: DexcomRegion,
    val username: String,
    val password: String,
    val pollIntervalSec: Int,
    val graphHours: Int,
)

object GlucoseStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(GlucoseUiState())
    val state: StateFlow<GlucoseUiState> = _state.asStateFlow()

    private var started = false
    private var pollJob: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            SettingsRepository.settings
                .map {
                    PollKey(
                        signedIn = it.isSignedIn,
                        region = it.region,
                        username = it.username.trim(),
                        password = it.password,
                        pollIntervalSec = it.pollIntervalSec,
                        graphHours = it.graphHours,
                    )
                }
                .distinctUntilChanged()
                .collect { key ->
                    restartPoller(key)
                }
        }
    }

    fun refreshNow() {
        val s = SettingsRepository.settings.value
        if (!s.isSignedIn) return
        scope.launch { fetchOnce(s) }
    }

    private fun restartPoller(key: PollKey) {
        pollJob?.cancel()
        if (!key.signedIn) {
            DexcomClient.clearSession()
            _state.value = GlucoseUiState()
            return
        }
        pollJob = scope.launch {
            var initial = true
            while (isActive) {
                val s = SettingsRepository.settings.value
                if (initial) {
                    _state.value = _state.value.copy(isLoading = true)
                }
                fetchOnce(s)
                initial = false
                delay(s.pollIntervalSec.coerceAtLeast(30) * 1000L)
            }
        }
    }

    private suspend fun fetchOnce(s: AppSettings) {
        try {
            val minutes = s.graphHours * 60 + 30
            val data = DexcomClient.fetchReadings(
                region = s.region,
                username = s.username,
                password = s.password,
                minutes = minutes,
                maxCount = 90,
            )
            _state.value = GlucoseUiState(
                readings = data,
                isLoading = false,
                errorMsg = null,
                lastUpdatedMs = System.currentTimeMillis(),
            )
        } catch (t: Throwable) {
            _state.value = _state.value.copy(
                isLoading = false,
                errorMsg = t.message ?: "Sync failed",
            )
        }
    }
}
