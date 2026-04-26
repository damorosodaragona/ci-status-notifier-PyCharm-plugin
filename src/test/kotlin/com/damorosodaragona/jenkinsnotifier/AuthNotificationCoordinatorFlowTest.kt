package com.damorosodaragona.jenkinsnotifier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthNotificationCoordinatorFlowTest {
    @Test
    fun `polling auth failure does not notify when notification recovery succeeds`() {
        val events = mutableListOf<String>()
        var notificationShown = false

        val decision = AuthNotificationCoordinator.notifyOnlyAfterAutoLoginFailure(
            source = "startup-polling",
            attemptAutoLogin = {
                events += "attempt-recover"
                true
            },
            showNotification = {
                events += "show-notification"
                notificationShown = true
            },
            log = { events += it },
        )

        assertEquals(AuthNotificationCoordinator.Decision.SkippedBecauseRecovered, decision)
        assertFalse(notificationShown)
        assertEquals(
            listOf(
                "auth-notify CHECK source=startup-polling",
                "attempt-recover",
                "auth-notify SKIP: auto-login/recover succeeded source=startup-polling",
            ),
            events,
        )
    }

    @Test
    fun `polling auth failure notifies only after notification recovery fails`() {
        val events = mutableListOf<String>()
        var notificationShown = false

        val decision = AuthNotificationCoordinator.notifyOnlyAfterAutoLoginFailure(
            source = "startup-polling",
            attemptAutoLogin = {
                events += "attempt-recover"
                false
            },
            showNotification = {
                events += "show-notification"
                notificationShown = true
            },
            log = { events += it },
        )

        assertEquals(AuthNotificationCoordinator.Decision.NotificationShown, decision)
        assertTrue(notificationShown)
        assertEquals(
            listOf(
                "auth-notify CHECK source=startup-polling",
                "attempt-recover",
                "auth-notify SHOW: auto-login/recover failed source=startup-polling",
                "show-notification",
            ),
            events,
        )
    }

    @Test
    fun `notification is never emitted before recovery attempt finishes`() {
        val events = mutableListOf<String>()

        AuthNotificationCoordinator.notifyOnlyAfterAutoLoginFailure(
            source = "tool-window",
            attemptAutoLogin = {
                events += "attempt-start"
                events += "attempt-end"
                false
            },
            showNotification = { events += "show-notification" },
            log = { events += it },
        )

        assertEquals(
            listOf(
                "auth-notify CHECK source=tool-window",
                "attempt-start",
                "attempt-end",
                "auth-notify SHOW: auto-login/recover failed source=tool-window",
                "show-notification",
            ),
            events,
        )
    }

    @Test
    fun `notification click can recover and restart polling`() {
        val events = mutableListOf<String>()

        val click = AuthNotificationCoordinator.loginAction(
            source = "startup-polling",
            recoverWithInteractiveFallback = {
                events += "interactive-recover"
                true
            },
            onRecovered = { events += "restart-polling" },
            log = { events += it },
        )

        click()

        assertEquals(
            listOf(
                "auth-notify CLICK: retry auth with interactive fallback source=startup-polling",
                "interactive-recover",
                "auth-notify CLICK result=true source=startup-polling",
                "restart-polling",
            ),
            events,
        )
    }
}
