package com.damorosodaragona.jenkinsnotifier

object AuthNotificationCoordinator {
    enum class Decision {
        SkippedBecauseRecovered,
        NotificationShown,
    }

    fun notifyOnlyAfterAutoLoginFailure(
        source: String,
        attemptAutoLogin: () -> Boolean,
        showNotification: () -> Unit,
        log: (String) -> Unit,
    ): Decision {
        log("auth-notify CHECK source=$source")
        val recovered = attemptAutoLogin()
        return if (recovered) {
            log("auth-notify SKIP: auto-login/recover succeeded source=$source")
            Decision.SkippedBecauseRecovered
        } else {
            log("auth-notify SHOW: auto-login/recover failed source=$source")
            showNotification()
            Decision.NotificationShown
        }
    }

    fun loginAction(
        source: String,
        recoverWithInteractiveFallback: () -> Boolean,
        onRecovered: () -> Unit,
        log: (String) -> Unit,
    ): () -> Unit = {
        log("auth-notify CLICK: retry auth with interactive fallback source=$source")
        val recovered = recoverWithInteractiveFallback()
        log("auth-notify CLICK result=$recovered source=$source")
        if (recovered) {
            onRecovered()
        }
    }
}
