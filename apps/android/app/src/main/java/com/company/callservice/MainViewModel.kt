package com.company.callservice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.company.callservice.data.SnapshotInfo
import com.company.callservice.data.SyncOutcome
import com.company.callservice.data.AutoSyncPolicy
import com.company.callservice.network.DirectoryEndpoint
import com.company.callservice.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val apiBaseUrl: String = "",
    val defaultCountryCallingCode: String = "82",
    val callerIdEnabled: Boolean = true,
    val tokenConfigured: Boolean = false,
    val snapshotInfo: SnapshotInfo? = null,
    val isBusy: Boolean = false,
    val message: String? = null,
)

class MainViewModel(private val graph: AppGraph) : ViewModel() {
    private val initialSettings = graph.settingsStore.read()
    private val mutableState = MutableStateFlow(
        MainUiState(
            apiBaseUrl = initialSettings.apiBaseUrl,
            defaultCountryCallingCode = initialSettings.defaultCountryCallingCode,
            callerIdEnabled = initialSettings.callerIdEnabled,
            tokenConfigured = graph.secretStore.hasToken(),
            snapshotInfo = graph.directoryRepository.snapshotInfo(),
        ),
    )
    val uiState: StateFlow<MainUiState> = mutableState.asStateFlow()

    fun updateApiBaseUrl(value: String) {
        mutableState.update { it.copy(apiBaseUrl = value, message = null) }
    }

    fun updateCountryCallingCode(value: String) {
        mutableState.update { it.copy(defaultCountryCallingCode = value, message = null) }
    }

    fun updateCallerIdEnabled(value: Boolean) {
        mutableState.update { it.copy(callerIdEnabled = value, message = null) }
    }

    fun saveSettings() = runOperation {
        graph.settingsStore.save(validatedSettings(requireApiUrl = false))
        "설정을 저장했습니다."
    }

    fun saveToken(token: String) = runOperation {
        graph.secretStore.saveToken(token)
        "Bearer 토큰을 Android Keystore로 암호화해 저장했습니다."
    }

    fun clearToken() = runOperation {
        graph.secretStore.clearToken()
        graph.directoryRepository.clearSnapshot()
        "저장된 Bearer 토큰과 로컬 디렉터리 snapshot을 삭제했습니다."
    }

    fun syncDirectory() = runOperation {
        graph.settingsStore.save(validatedSettings(requireApiUrl = true))
        when (val outcome = graph.directoryRepository.sync()) {
            is SyncOutcome.Updated ->
                "동기화 완료: ${outcome.entryCount}건, 중복 ${outcome.duplicateCount}건 통합 (버전 ${outcome.version})"

            is SyncOutcome.NotModified ->
                "변경 없음(304): ${outcome.entryCount}건 (버전 ${outcome.version})"
        }
    }

    fun autoSyncDirectory(nowEpochMillis: Long = System.currentTimeMillis()) {
        if (mutableState.value.isBusy) return
        val persistedSettings = graph.settingsStore.read()
        if (persistedSettings.apiBaseUrl.isBlank() || !graph.secretStore.hasToken()) return

        val shouldAttempt = AutoSyncPolicy.shouldAttempt(
            nowEpochMillis = nowEpochMillis,
            lastSuccessfulCheckEpochMillis = graph.directoryRepository
                .snapshotInfo()
                ?.checkedAtEpochMillis
                ?: 0L,
            lastAutoAttemptEpochMillis = graph.settingsStore.lastAutoSyncAttemptEpochMillis(),
        )
        if (!shouldAttempt) return

        graph.settingsStore.markAutoSyncAttempt(nowEpochMillis)
        runOperation(
            showSuccessMessage = false,
            failurePrefix = "자동 동기화 실패: ",
        ) {
            when (val outcome = graph.directoryRepository.sync()) {
                is SyncOutcome.Updated -> "자동 동기화 ${outcome.entryCount}건 완료"
                is SyncOutcome.NotModified -> "자동 동기화 변경 없음"
            }
        }
    }

    fun importBundledSample() = runOperation {
        graph.settingsStore.save(validatedSettings(requireApiUrl = false))
        val outcome = graph.directoryRepository.importBundledSample()
        "내장 샘플 ${outcome.entryCount}건을 로컬 snapshot으로 가져왔습니다."
    }

    fun clearSnapshot() = runOperation {
        graph.directoryRepository.clearSnapshot()
        "로컬 디렉터리 snapshot을 삭제했습니다."
    }

    fun dismissMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun validatedSettings(requireApiUrl: Boolean): AppSettings {
        val state = mutableState.value
        val countryCode = state.defaultCountryCallingCode.trim().removePrefix("+")
        require(countryCode.matches(Regex("[1-9][0-9]{0,2}"))) {
            "기본 국가번호는 + 없이 1~3자리 ASCII 숫자로 입력하세요."
        }

        val apiBaseUrl = state.apiBaseUrl.trim()
        if (requireApiUrl && apiBaseUrl.isBlank()) {
            throw IllegalArgumentException("API 기본 URL을 입력하세요.")
        }
        if (apiBaseUrl.isNotBlank()) DirectoryEndpoint.build(apiBaseUrl)

        return AppSettings(
            apiBaseUrl = apiBaseUrl,
            defaultCountryCallingCode = countryCode,
            callerIdEnabled = state.callerIdEnabled,
        )
    }

    private fun runOperation(
        showSuccessMessage: Boolean = true,
        failurePrefix: String = "",
        operation: suspend () -> String,
    ) {
        if (mutableState.value.isBusy) return
        mutableState.update { it.copy(isBusy = true, message = null) }
        viewModelScope.launch {
            val result = runCatching { operation() }
            mutableState.update {
                it.copy(
                    isBusy = false,
                    message = result.fold(
                        onSuccess = { message -> message.takeIf { showSuccessMessage } },
                        onFailure = { error ->
                            failurePrefix + (error.message ?: "작업을 완료하지 못했습니다.")
                        },
                    ),
                    tokenConfigured = graph.secretStore.hasToken(),
                    snapshotInfo = graph.directoryRepository.snapshotInfo(),
                )
            }
        }
    }

    companion object {
        fun factory(graph: AppGraph): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MainViewModel::class.java))
                    return MainViewModel(graph) as T
                }
            }
    }
}
