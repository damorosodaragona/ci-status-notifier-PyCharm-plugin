package com.damorosodaragona.jenkinsnotifier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthNotificationCoordinatorTest {
    @Test
    fun `does not show notification when auto-login recovers authentication`() {
        var notificationShown = false
        val logs = mutableListOf<String>()

        val decision = AuthNotificationCoordinator.notifyOnlyAfterAutoLoginFailure(
            source = "test",
            attemptAutoLogin = { true },
            showNotification = { notificationShown = true },
            log = logs::add,
        )

        assertEquals(AuthNotificationCoordinator.Decision.SkippedBecauseRecovered, decision)
        assertFalse(notificationShown)
        assertEquals(
            listOf(
                "auth-notify CHECK source=test",
                "auth-notify SKIP: auto-login/recover succeeded source=test",
            ),
            logs,
        )
    }

    @Test
    fun `shows notification only when auto-login fails`() {
        var notificationShown = false
        val logs = mutableListOf<String>()

        val decision = AuthNotificationCoordinator.notifyOnlyAfterAutoLoginFailure(
            source = "test",
            attemptAutoLogin = { false },
            showNotification = { notificationShown = true },
            log = logs::add,
        )

        assertEquals(AuthNotificationCoordinator.Decision.NotificationShown, decision)
        assertTrue(notificationShown)
        assertEquals(
            listOf(
                "auth-notify CHECK source=test",
                "auth-notify SHOW: auto-login/recover failed source=test",
            ),
            logs,
        )
    }

    @Test
    fun `waits for running auto-login through attempt callback before deciding`() {
        var autoLoginCompleted = false
        var notificationShown = false
        val logs = mutableListOf<String>()

        val decision = AuthNotificationCoordinator.notifyOnlyAfterAutoLoginFailure(
            source = "test",
            attemptAutoLogin = {
                autoLoginCompleted = true
                true
            },
            showNotification = { notificationShown = true },
            log = logs::add,
        )

        assertEquals(AuthNotificationCoordinator.Decision.SkippedBecauseRecovered, decision)
        assertTrue(autoLoginCompleted)
        assertFalse(notificationShown)
        assertEquals(
            listOf(
                "auth-notify CHECK source=test",
                "auth-notify SKIP: auto-login/recover succeeded source=test",
            ),
            logs,
        )
    }

    @Test
    fun `notification click retries auth with interactive fallback and runs recovery callback on success`() {
        var interactiveFallbackCalled = false
        var recoveredCallbackCalled = false
        val logs = mutableListOf<String>()

        val action = AuthNotificationCoordinator.loginAction(
            source = "test",
            recoverWithInteractiveFallback = {
                interactiveFallbackCalled = true
                true
            },
            onRecovered = { recoveredCallbackCalled = true },
            log = logs::add,
        )

        action()

        assertTrue(interactiveFallbackCalled)
        assertTrue(recoveredCallbackCalled)
        assertEquals(
            listOf(
                "auth-notify CLICK: retry auth with interactive fallback source=test",
                "auth-notify CLICK result=true source=test",
            ),
            logs,
        )
    }

    @Test
    fun `notification click does not run recovery callback when interactive fallback fails`() {
        var interactiveFallbackCalled = false
        var recoveredCallbackCalled = false
        val logs = mutableListOf<String>()

        val action = AuthNotificationCoordinator.loginAction(
            source = "test",
            recoverWithInteractiveFallback = {
                interactiveFallbackCalled = true
                false
            },
            onRecovered = { recoveredCallbackCalled = true },
            log = logs::add,
        )

        action()

        assertTrue(interactiveFallbackCalled)
        assertFalse(recoveredCallbackCalled)
        assertEquals(
            listOf(
                "auth-notify CLICK: retry auth with interactive fallback source=test",
                "auth-notify CLICK result=false source=test",
            ),
            logs,
        )
    }
}
