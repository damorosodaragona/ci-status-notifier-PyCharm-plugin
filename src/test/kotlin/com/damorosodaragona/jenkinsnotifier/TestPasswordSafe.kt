package com.damorosodaragona.jenkinsnotifier

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

internal class TestPasswordSafe : PasswordSafe() {
    private val store = linkedMapOf<String, Credentials?>()

    override var isRememberPasswordByDefault: Boolean = false

    override val isMemoryOnly: Boolean = true

    override fun set(attributes: CredentialAttributes, credentials: Credentials?) {
        store[attributes.serviceName] = credentials
    }

    override fun set(attributes: CredentialAttributes, credentials: Credentials?, memoryOnly: Boolean) {
        set(attributes, credentials)
    }

    override fun get(attributes: CredentialAttributes): Credentials? = store[attributes.serviceName]

    override fun getAsync(attributes: CredentialAttributes): Promise<Credentials?> =
        AsyncPromise<Credentials?>().apply { setResult(get(attributes)) }

    override fun isPasswordStoredOnlyInMemory(attributes: CredentialAttributes, credentials: Credentials): Boolean = true
}

internal inline fun <T> withTestPasswordSafe(block: (TestPasswordSafe) -> T): T {
    val disposable: Disposable = Disposer.newDisposable("test-password-safe")
    val app = ApplicationManager.getApplication() ?: createTestApplication(disposable)
    val passwordSafe = TestPasswordSafe()
    app.javaClass.getMethod(
        "replaceServiceInstance",
        Class::class.java,
        Any::class.java,
        Disposable::class.java,
    ).invoke(app, PasswordSafe::class.java, passwordSafe, disposable)
    return try {
        block(passwordSafe)
    } finally {
        Disposer.dispose(disposable)
    }
}

private fun createTestApplication(disposable: Disposable): Any {
    val appClass = Class.forName("com.intellij.openapi.application.impl.ApplicationImpl")
    val appRef = AtomicReference<Any>()
    SwingUtilities.invokeAndWait {
        appRef.set(appClass.getConstructor(Boolean::class.javaPrimitiveType).newInstance(true))
    }
    val app = appRef.get()
    ApplicationManager::class.java
        .getMethod(
            "setApplication",
            Class.forName("com.intellij.openapi.application.Application"),
            Disposable::class.java,
        )
        .invoke(null, app, disposable)
    return app
}
