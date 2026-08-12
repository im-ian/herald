package dev.imian.herald.delivery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.imian.herald.HeraldApplication
import dev.imian.herald.data.DeliveryState
import dev.imian.herald.settings.ReadSettingsResult
import dev.imian.herald.settings.WebhookValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebhookDeliveryWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val eventId = inputData.getString(EVENT_ID) ?: return Result.failure()
        val container = (applicationContext as HeraldApplication).container
        val repository = container.eventRepository
        val event = repository.eventById(eventId) ?: return Result.success()
        if (event.deliveryState != DeliveryState.PENDING) return Result.success()

        val settings = when (val read = container.settingsStore.read()) {
            is ReadSettingsResult.Success -> read.settings
            is ReadSettingsResult.Error -> {
                repository.markFailure(eventId, read.message, terminal = true)
                return Result.failure()
            }
        }
        val endpoint = settings.deliveryEndpoint
        if (endpoint == null) {
            repository.markFailure(eventId, "웹훅이 설정되어 있지 않습니다.", terminal = true)
            return Result.failure()
        }
        if (event.deliveryRouteId != settings.deliveryRouteId) {
            repository.markFailure(
                eventId,
                "웹훅 주소가 변경되었습니다. 확인 후 수동으로 재시도해 주세요.",
                terminal = true,
            )
            return Result.failure()
        }
        val validation = WebhookValidator.validate(endpoint, settings.allowInsecureLocalHttp)
        if (!validation.isValid || validation.normalizedUrl == null) {
            repository.markFailure(eventId, "웹훅 설정이 올바르지 않습니다.", terminal = true)
            return Result.failure()
        }

        // A clear/cancel may have removed the row while this worker was preparing.
        val stillPending = repository.eventById(eventId)?.deliveryState == DeliveryState.PENDING
        if (!stillPending) return Result.success()

        val deliveryResult = withContext(Dispatchers.IO) {
            WebhookClient().deliver(
                endpoint = validation.normalizedUrl,
                bearerToken = settings.bearerToken,
                event = event,
            )
        }
        return when (val result = deliveryResult) {
            DeliveryResult.Success -> {
                repository.markDelivered(eventId)
                Result.success()
            }
            is DeliveryResult.TerminalFailure -> {
                repository.markFailure(eventId, result.reason, terminal = true)
                Result.failure()
            }
            is DeliveryResult.RetryableFailure -> {
                val terminal = runAttemptCount >= MAX_RETRY_ATTEMPTS - 1
                repository.markFailure(eventId, result.reason, terminal)
                if (terminal) Result.failure() else Result.retry()
            }
        }
    }

    companion object {
        const val EVENT_ID = "event-id"
        private const val MAX_RETRY_ATTEMPTS = 10
    }
}
