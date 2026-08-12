package dev.imian.herald.status

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ListenerRuntimeStatus(
    val isConnected: Boolean,
    val lastConnectedAt: Long?,
    val lastEventAt: Long?,
    val lastError: String?,
)

class ListenerStatusStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val _status = MutableStateFlow(read(isConnected = false))

    val status: StateFlow<ListenerRuntimeStatus> = _status.asStateFlow()

    fun markConnected(now: Long = System.currentTimeMillis()) {
        preferences.edit {
            putLong(KEY_LAST_CONNECTED_AT, now)
            remove(KEY_LAST_ERROR)
        }
        _status.value = read(isConnected = true)
    }

    fun markDisconnected() {
        _status.value = read(isConnected = false)
    }

    fun markEvent(now: Long = System.currentTimeMillis()) {
        preferences.edit {
            putLong(KEY_LAST_EVENT_AT, now)
            remove(KEY_LAST_ERROR)
        }
        _status.value = read(isConnected = _status.value.isConnected)
    }

    fun markError(error: String) {
        preferences.edit { putString(KEY_LAST_ERROR, error.take(MAX_ERROR_LENGTH)) }
        _status.value = read(isConnected = _status.value.isConnected)
    }

    private fun read(isConnected: Boolean): ListenerRuntimeStatus = ListenerRuntimeStatus(
        isConnected = isConnected,
        lastConnectedAt = preferences.getLong(KEY_LAST_CONNECTED_AT, 0L).takeIf { it > 0L },
        lastEventAt = preferences.getLong(KEY_LAST_EVENT_AT, 0L).takeIf { it > 0L },
        lastError = preferences.getString(KEY_LAST_ERROR, null),
    )

    private companion object {
        const val PREFERENCES_NAME = "herald-listener-status"
        const val KEY_LAST_CONNECTED_AT = "last-connected-at"
        const val KEY_LAST_EVENT_AT = "last-event-at"
        const val KEY_LAST_ERROR = "last-error"
        const val MAX_ERROR_LENGTH = 160
    }
}
