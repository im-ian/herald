package dev.imian.herald.delivery

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.util.concurrent.TimeUnit

class DeliveryScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun schedule(eventIds: Collection<String>) {
        eventIds.distinct().forEach(::schedule)
    }

    fun schedule(eventId: String) {
        val work = OneTimeWorkRequestBuilder<WebhookDeliveryWorker>()
            .setInputData(Data.Builder().putString(WebhookDeliveryWorker.EVENT_ID, eventId).build())
            .setConstraints(NETWORK_CONSTRAINTS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
            .addTag(DELIVERY_TAG)
            .build()
        workManager.enqueueUniqueWork(
            "$DELIVERY_WORK_PREFIX$eventId",
            ExistingWorkPolicy.KEEP,
            work,
        )
    }

    fun ensurePeriodicRecovery() {
        val work = PeriodicWorkRequestBuilder<DeliveryRecoveryWorker>(
            15,
            TimeUnit.MINUTES,
        ).build()
        workManager.enqueueUniquePeriodicWork(
            RECOVERY_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            work,
        )
    }

    fun cancelAllDeliveries() {
        workManager.cancelAllWorkByTag(DELIVERY_TAG)
    }

    private companion object {
        const val DELIVERY_TAG = "herald-webhook-delivery"
        const val DELIVERY_WORK_PREFIX = "herald-delivery-"
        const val RECOVERY_WORK_NAME = "herald-delivery-recovery"

        val NETWORK_CONSTRAINTS = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
