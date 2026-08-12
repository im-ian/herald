package dev.imian.herald.notification

import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.imian.herald.HeraldApplication
import dev.imian.herald.parser.MessageParser
import dev.imian.herald.settings.ReadSettingsResult
import kotlinx.coroutines.launch

class HeraldNotificationListener : NotificationListenerService() {
    private val container get() = (application as HeraldApplication).container

    override fun onListenerConnected() {
        super.onListenerConnected()
        container.listenerStatusStore.markConnected()
        container.applicationScope.launch {
            container.deliveryScheduler.schedule(container.eventRepository.pendingIds())
        }
    }

    override fun onListenerDisconnected() {
        container.listenerStatusStore.markDisconnected()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification?) {
        val posted = statusBarNotification ?: return

        // Check the allowlist before reading any notification extras.
        if (!container.settingsStore.isPackageAllowed(posted.packageName)) return

        val snapshot = try {
            NotificationSnapshotFactory.create(posted)
        } catch (error: RuntimeException) {
            container.listenerStatusStore.markError(
                "알림 해석 실패 (${error.javaClass.simpleName})",
            )
            return
        }
        val messages = MessageParser.parse(snapshot)
        if (messages.isEmpty()) return

        val sourceLabel = applicationLabel(posted.packageName)
        val capturedAt = System.currentTimeMillis()
        container.applicationScope.launch {
            try {
                val settings = container.settingsStore.read()
                val deliveryRouteId = when (settings) {
                    is ReadSettingsResult.Success -> settings.settings.deliveryRouteId
                    is ReadSettingsResult.Error -> {
                        container.listenerStatusStore.markError(settings.message)
                        null
                    }
                }
                val insertedIds = container.eventRepository.record(
                    messages = messages,
                    sourceLabel = sourceLabel,
                    deliveryRouteId = deliveryRouteId,
                    capturedAt = capturedAt,
                )
                if (insertedIds.isNotEmpty()) {
                    container.listenerStatusStore.markEvent(capturedAt)
                    if (deliveryRouteId != null) {
                        container.deliveryScheduler.schedule(insertedIds)
                    }
                }
            } catch (error: RuntimeException) {
                container.listenerStatusStore.markError(
                    "알림 저장 실패 (${error.javaClass.simpleName})",
                )
            }
        }
    }

    private fun applicationLabel(packageName: String): String = try {
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(applicationInfo).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }
}
