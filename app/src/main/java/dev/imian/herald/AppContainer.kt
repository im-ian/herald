package dev.imian.herald

import android.content.Context
import dev.imian.herald.data.EventRepository
import dev.imian.herald.delivery.DeliveryScheduler
import dev.imian.herald.settings.SettingsStore
import dev.imian.herald.status.ListenerStatusStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val eventRepository = EventRepository(applicationContext)
    val settingsStore = SettingsStore(applicationContext)
    val listenerStatusStore = ListenerStatusStore(applicationContext)
    val deliveryScheduler = DeliveryScheduler(applicationContext)

    fun initialize() {
        deliveryScheduler.ensurePeriodicRecovery()
        applicationScope.launch {
            eventRepository.refresh()
            deliveryScheduler.schedule(eventRepository.pendingIds())
        }
    }
}
