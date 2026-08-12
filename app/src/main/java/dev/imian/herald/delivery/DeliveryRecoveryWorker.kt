package dev.imian.herald.delivery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.imian.herald.HeraldApplication

class DeliveryRecoveryWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as HeraldApplication).container
        return try {
            container.eventRepository.pruneExpired()
            container.deliveryScheduler.schedule(container.eventRepository.pendingIds())
            Result.success()
        } catch (_: RuntimeException) {
            Result.retry()
        }
    }
}
