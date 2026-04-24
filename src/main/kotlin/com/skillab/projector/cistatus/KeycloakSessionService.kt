package com.skillab.projector.cistatus

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.Timer

@Service(Service.Level.PROJECT)
class KeycloakSessionService(private val project: Project) : Disposable {
    private val settings = CiStatusSettings.getInstance(project)
    private var browser: JBCefBrowser? = null
    @Volatile private var autoLoginInProgress = false

    fun isAvailable(): Boolean = JBCefApp.isSupported() && settings.experimentalKeycloakInteractiveFallback

    fun ensureLoggedIn(baseUrl: String): Boolean {
        if (!isAvailable()) return false
        val browser = ensureBrowser(baseUrl) ?: return false
        return openLoginDialog(baseUrl, browser)
    }

    /**
     * Silent Keycloak recovery.
     *
     * JCEF may not start navigating reliably if a browser component is never realized. For this reason the
     * silent browser is attached to a tiny off-screen JWindow. Nothing is shown to the user, but the native
     * browser component is mounted and can execute the Keycloak redirect/form flow.
     *
     * Success is not inferred from the URL anymore. The login is considered recovered only when a real
     * Jenkins API probe from the same browser session returns 2xx.
     */
    fun attemptAutoLoginInBackground(baseUrl: String): Boolean {
        if (!isAvailable() || !settings.experimentalKeycloakAutoLogin || autoLoginInProgress) return false
        val username = settings.keycloakWebUsername.trim()
        val password = settings.getKeycloakWebPassword()
        if (username.isBlank() || password.isBlank()) return false

        autoLoginInProgress = true
        val browserHolder = arrayOfNulls<JBCefBrowser>(1)
        val windowHolder = arrayOfNulls<JWindow>(1)
        val timerHolder = arrayOfNulls<Timer>(1)
        val finished = CountDownLatch(1)
        val result = AtomicBoolean(false)
        val completed = AtomicBoolean(false)

        try {
            ApplicationManager.getApplication().invokeAndWait {
                val hiddenBrowser = JBCefBrowser("about:blank")
                browserHolder[0] = hiddenBrowser

                val hiddenWindow = JWindow().apply {
                    contentPane.layout = BorderLayout()
                    contentPane.add(hiddenBrowser.component, BorderLayout.CENTER)
                    size = Dimension(2, 2)
                    location = java.awt.Point(-10_000, -10_000)
                    isAlwaysOnTop = false
                    focusableWindowState = false
                    isVisible = true
                }
                windowHolder[0] = hiddenWindow

                val successQuery = JBCefJSQuery.create(hiddenBrowser)
                successQuery.addHandler {
                    if (completed.compareAndSet(false, true)) {
                        result.set(true)
                        timerHolder[0]?.stop()
                        finished.countDown()
                    }
                    null
                }

                val failureQuery = JBCefJSQuery.create(hiddenBrowser)
                failureQuery.addHandler {
                    null
                }

                val script = autoLoginAndProbeScript(baseUrl, username, password, successQuery, failureQuery)

                fun executeScript(reason: String, browserRef: CefBrowser? = hiddenBrowser.cefBrowser) {
                    if (completed.get()) return
                    val target = browserRef ?: hiddenBrowser.cefBrowser
                    target.executeJavaScript(script, target.url.orEmpty(), 0)
                }

                hiddenBrowser.cefBrowser.client.addLoadHandler(object : CefLoadHandlerAdapter() {
                    override fun onLoadEnd(browserRef: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                        if (frame?.isMain == true) {
                            executeScript("load-end", browserRef)
                        }
                    }
                })

                var ticks = 0
                timerHolder[0] = Timer(800) {
                    ticks += 1
                    executeScript("timer-$ticks")
                    if (ticks >= 40 && completed.compareAndSet(false, true)) {
                        finished.countDown()
                    }
                }.apply {
                    initialDelay = 600
                    start()
                }

                hiddenBrowser.loadURL(baseUrl)
            }

            finished.await(35, TimeUnit.SECONDS)
            return result.get()
        } finally {
            val timer = timerHolder[0]
            val hiddenWindow = windowHolder[0]
            val hiddenBrowser = browserHolder[0]
            ApplicationManager.getApplication().invokeLater({
                timer?.stop()
                hiddenWindow?.isVisible = false
                hiddenWindow?.dispose()
                hiddenBrowser?.let { Disposer.dispose(it) }
            }, ModalityState.any())
            autoLoginInProgress = false
        }
    }

    private fun ensureBrowser(baseUrl: String): JBCefBrowser? {
        if (!JBCefApp.isSupported()) return null
        var instance = browser
        ApplicationManager.getApplication().invokeAndWait {
            if (browser == null) {
                browser = JBCefBrowser(baseUrl)
            }
            instance = browser
        }
        return instance
    }

    private fun openLoginDialog(baseUrl: String, browser: JBCefBrowser): Boolean {
        val finished = CountDownLatch(1)
        val result = booleanArrayOf(false)

        ApplicationManager.getApplication().invokeAndWait {
            browser.loadURL(baseUrl)
            val dialog = object : DialogWrapper(project) {
                init {
                    title = "Jenkins Login"
                    init()
                }

                override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
                    preferredSize = Dimension(980, 720)
                    add(
                        JBLabel("Complete the Jenkins / Keycloak login. The plugin will reuse this session for Jenkins API requests."),
                        BorderLayout.NORTH,
                    )
                    add(browser.component, BorderLayout.CENTER)
                }
            }

            installAutoLogin(browser, baseUrl, dialog)
            result[0] = dialog.showAndGet() && browser.cefBrowser.url.orEmpty().startsWith(baseUrl) && !looksLikeLogin(browser.cefBrowser.url.orEmpty())
            finished.countDown()
        }

        finished.await(1, TimeUnit.MINUTES)
        return result[0]
    }

    private fun installAutoLogin(browser: JBCefBrowser, baseUrl: String, dialog: DialogWrapper) {
        if (!settings.experimentalKeycloakAutoLogin) return
        val username = settings.keycloakWebUsername.trim()
        val password = settings.getKeycloakWebPassword()
        if (username.isBlank() || password.isBlank()) return

        val script = autoLoginScript(baseUrl, username, password)

        browser.cefBrowser.client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browserRef: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    browserRef?.executeJavaScript(script, browserRef.url, 0)
                    val currentUrl = browserRef?.url.orEmpty()
                    if (currentUrl.startsWith(baseUrl) && !looksLikeLogin(currentUrl)) {
                        ApplicationManager.getApplication().invokeLater {
                            if (!dialog.isDisposed) {
                                dialog.close(DialogWrapper.OK_EXIT_CODE)
                            }
                        }
                    }
                }
            }
        })
    }

    private fun autoLoginScript(baseUrl: String, username: String, password: String): String = """
        (function() {
          var current = window.location.href || '';
          var form = document.querySelector('#kc-form-login');
          var user = document.querySelector('#username');
          var pass = document.querySelector('#password');
          if (form && user && pass) {
            if (!user.value) user.value = '${jsString(username)}';
            if (!pass.value) pass.value = '${jsString(password)}';
            var btn = document.querySelector('#kc-login');
            setTimeout(function() {
              if (btn) { btn.click(); } else { form.submit(); }
            }, 150);
            return;
          }
          if (current.indexOf('commenceLogin') === -1 && current.indexOf('keycloak') === -1 && current.indexOf('login-actions') === -1 && current.startsWith('${jsString(baseUrl)}')) {
            window.__skillabAutoLoginDone = true;
          }
        })();
    """.trimIndent()

    private fun autoLoginAndProbeScript(
        baseUrl: String,
        username: String,
        password: String,
        successQuery: JBCefJSQuery,
        failureQuery: JBCefJSQuery,
    ): String {
        val probeUrl = baseUrl.trimEnd('/') + "/whoAmI/api/json"
        return """
            (function() {
              try {
                var form = document.querySelector('#kc-form-login');
                var user = document.querySelector('#username');
                var pass = document.querySelector('#password');
                if (form && user && pass && !window.__skillabLoginSubmitted) {
                  window.__skillabLoginSubmitted = true;
                  user.value = '${jsString(username)}';
                  pass.value = '${jsString(password)}';
                  var btn = document.querySelector('#kc-login');
                  setTimeout(function() {
                    if (btn) { btn.click(); } else { form.submit(); }
                  }, 150);
                  return;
                }

                if (window.__skillabProbeRunning) return;
                window.__skillabProbeRunning = true;
                fetch('${jsString(probeUrl)}', {
                  credentials: 'include',
                  cache: 'no-store',
                  headers: { 'Accept': 'application/json' }
                }).then(function(response) {
                  window.__skillabProbeRunning = false;
                  if (response && response.status >= 200 && response.status < 300) {
                    ${successQuery.inject("'probe-ok'")};
                  }
                }).catch(function(error) {
                  window.__skillabProbeRunning = false;
                  ${failureQuery.inject("String(error && error.message ? error.message : error)")};
                });
              } catch (error) {
                ${failureQuery.inject("String(error && error.message ? error.message : error)")};
              }
            })();
        """.trimIndent()
    }

    private fun looksLikeLogin(url: String): Boolean =
        url.contains("commenceLogin", ignoreCase = true) ||
            url.contains("keycloak", ignoreCase = true) ||
            url.contains("login-actions", ignoreCase = true)

    private fun jsString(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")

    override fun dispose() {
        browser?.let { Disposer.dispose(it) }
        browser = null
    }

    companion object {
        fun getInstance(project: Project): KeycloakSessionService = project.getService(KeycloakSessionService::class.java)
    }
}
