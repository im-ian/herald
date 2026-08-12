package dev.imian.herald.ui

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.imian.herald.HeraldApplication
import dev.imian.herald.data.DeliveryState
import dev.imian.herald.settings.HeraldSettings
import dev.imian.herald.settings.ReadSettingsResult
import dev.imian.herald.settings.SaveSettingsResult
import dev.imian.herald.settings.SettingsInput
import dev.imian.herald.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsDraft(
    val webhookUrl: String = "",
    val bearerToken: String = "",
    val hasStoredToken: Boolean = false,
    val clearStoredToken: Boolean = false,
    val routingLoadFailed: Boolean = false,
    val allowedPackages: String = "com.kakao.talk",
    val allowInsecureLocalHttp: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val heraldApplication = application as HeraldApplication
    private val container = heraldApplication.container

    val events = container.eventRepository.recentEvents
    val listenerStatus = container.listenerStatusStore.status

    private val _settingsDraft = MutableStateFlow(SettingsDraft())
    val settingsDraft: StateFlow<SettingsDraft> = _settingsDraft.asStateFlow()

    private val _isAccessGranted = MutableStateFlow(false)
    val isAccessGranted: StateFlow<Boolean> = _isAccessGranted.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) { container.settingsStore.read() }) {
                is ReadSettingsResult.Success -> _settingsDraft.value = result.settings.toDraft()
                is ReadSettingsResult.Error -> {
                    _settingsDraft.value = SettingsDraft(
                        routingLoadFailed = true,
                        allowedPackages = SettingsStore.packagesAsText(result.allowedPackages),
                    )
                    _notice.value = result.message
                }
            }
            refresh()
        }
    }

    fun refresh() {
        _isAccessGranted.value = NotificationManagerCompat
            .getEnabledListenerPackages(getApplication())
            .contains(getApplication<Application>().packageName)
        viewModelScope.launch { container.eventRepository.refresh() }
    }

    fun updateWebhookUrl(value: String) = updateDraft { copy(webhookUrl = value) }

    fun updateBearerToken(value: String) = updateDraft {
        copy(bearerToken = value, clearStoredToken = false)
    }

    fun clearBearerToken() = updateDraft {
        copy(bearerToken = "", hasStoredToken = false, clearStoredToken = true)
    }

    fun updateAllowedPackages(value: String) = updateDraft { copy(allowedPackages = value) }

    fun updateAllowInsecureLocalHttp(value: Boolean) =
        updateDraft { copy(allowInsecureLocalHttp = value) }

    fun saveSettings() {
        if (_isBusy.value) return
        _isBusy.value = true
        _notice.value = null
        val draft = _settingsDraft.value
        viewModelScope.launch {
            val tokenUpdate = when {
                draft.bearerToken.isNotEmpty() -> draft.bearerToken
                draft.clearStoredToken || draft.routingLoadFailed -> ""
                else -> null
            }
            val result = withContext(Dispatchers.IO) {
                container.settingsStore.save(
                    SettingsInput(
                        webhookUrl = draft.webhookUrl,
                        bearerToken = tokenUpdate,
                        allowedPackages = draft.allowedPackages,
                        allowInsecureLocalHttp = draft.allowInsecureLocalHttp,
                    ),
                )
            }
            when (result) {
                is SaveSettingsResult.Error -> _notice.value = result.message
                is SaveSettingsResult.Success -> {
                    _settingsDraft.value = result.settings.toDraft()
                    _notice.value = if (result.settings.deliveryEndpoint == null) {
                        "저장했습니다. 새 메시지는 기기에만 보관합니다."
                    } else {
                        "저장했습니다. 새 메시지부터 웹훅으로 전달합니다."
                    }
                }
            }
            _isBusy.value = false
        }
    }

    fun retryFailed() {
        viewModelScope.launch {
            val settings = when (val read = withContext(Dispatchers.IO) {
                container.settingsStore.read()
            }) {
                is ReadSettingsResult.Success -> read.settings
                is ReadSettingsResult.Error -> {
                    _notice.value = read.message
                    return@launch
                }
            }
            val routeId = settings.deliveryRouteId
            if (routeId == null) {
                _notice.value = "먼저 웹훅 주소를 저장해 주세요."
                return@launch
            }
            val ids = container.eventRepository.retryFailed(routeId)
            container.deliveryScheduler.schedule(ids)
            _notice.value = if (ids.isEmpty()) "재시도할 항목이 없습니다." else "현재 웹훅으로 재전달을 예약했습니다."
        }
    }

    fun clearEvents() {
        viewModelScope.launch {
            container.deliveryScheduler.cancelAllDeliveries()
            container.eventRepository.clear()
            _notice.value = "대기 작업을 취소하고 기기에 저장된 기록을 삭제했습니다."
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun hasFailedEvents(): Boolean = events.value.any { it.deliveryState == DeliveryState.FAILED }

    private fun updateDraft(update: SettingsDraft.() -> SettingsDraft) {
        _settingsDraft.value = _settingsDraft.value.update()
        _notice.value = null
    }

    private fun HeraldSettings.toDraft(): SettingsDraft = SettingsDraft(
        webhookUrl = webhookUrl,
        bearerToken = "",
        hasStoredToken = bearerToken.isNotEmpty(),
        clearStoredToken = false,
        routingLoadFailed = false,
        allowedPackages = SettingsStore.packagesAsText(allowedPackages),
        allowInsecureLocalHttp = allowInsecureLocalHttp,
    )
}
