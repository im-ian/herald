package dev.imian.herald.notification

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.imian.herald.HeraldApplication
import dev.imian.herald.data.ExtractionMethod
import dev.imian.herald.settings.SettingsInput
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationListenerEndToEndTest {
    private val application: HeraldApplication = ApplicationProvider.getApplicationContext()
    private val container get() = application.container

    @Before
    fun prepare() {
        runBlocking {
        container.eventRepository.clear()
        container.settingsStore.save(
            SettingsInput(
                webhookUrl = "",
                bearerToken = "",
                allowedPackages = SHELL_PACKAGE,
                allowInsecureLocalHttp = false,
            ),
        )
        }
    }

    @After
    fun cleanUp() = runBlocking {
        executeShell("cmd notification disallow_listener $LISTENER_COMPONENT")
        container.settingsStore.save(
            SettingsInput(
                webhookUrl = "",
                bearerToken = "",
                allowedPackages = "com.kakao.talk",
                allowInsecureLocalHttp = false,
            ),
        )
        container.eventRepository.clear()
    }

    @Test
    fun allowedPlatformNotificationFlowsThroughListenerParserAndDatabase() {
        executeShell("cmd notification allow_listener $LISTENER_COMPONENT")
        assertTrue(await { container.listenerStatusStore.status.value.isConnected })

        executeShell(
            "cmd notification post -t Herald-fixture herald-fixture hello-from-shell",
        )
        assertTrue(await { container.eventRepository.recentEvents.value.size == 1 })

        val event = container.eventRepository.recentEvents.value.single()
        assertEquals(SHELL_PACKAGE, event.sourcePackage)
        assertEquals("Herald-fixture", event.conversation)
        assertNull(event.sender)
        assertEquals("hello-from-shell", event.text)
        assertEquals(ExtractionMethod.TEXT, event.extractionMethod)
    }

    private fun await(timeoutMillis: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(50L)
        }
        return condition()
    }

    private fun executeShell(command: String) {
        val descriptor: ParcelFileDescriptor = InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        descriptor.use {
            FileInputStream(it.fileDescriptor).use(FileInputStream::readBytes)
        }
    }

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        const val LISTENER_COMPONENT =
            "dev.imian.herald/dev.imian.herald.notification.HeraldNotificationListener"
    }
}
