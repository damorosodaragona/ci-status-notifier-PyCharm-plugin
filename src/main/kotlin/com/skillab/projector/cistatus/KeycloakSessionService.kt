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
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

@Service(Service.Level.PROJECT)
class KeycloakSessionService(private val project: Project) : Disposable {
    private val settings = CiStatusSettings.getInstance(project)
    private var browser: JBCefBrowser? = null
    @Volatile private var autoLoginInProgress = false

    fun isAvailable(): Boolean = JBCefApp.isSupported() && settings.experimentalKeycloakInteractiveFallback

    fun ensureLoggedIn(baseUrl: String): Boolean {
        log("interactive-login requested base=$baseUrl available=${isAvailable()}")
        if (!isAvailable()) return false
        val browser = ensureBrowser(baseUrl) ?: return false
        return openLoginDialog(baseUrl, browser)
    }

    fun attemptAutoLoginInBackground(baseUrl: String): Boolean {
        val enabled = settings.experimentalKeycloakAutoLogin
        log("auto-login requested base=$baseUrl available=${isAvailable()} enabled=$enabled inProgress=$autoLoginInProgress")
        if (!isAvailable() || !enabled || autoLoginInProgress) return false
        val username = settings.keycloakWebUsername.trim()
        val password = settings.getKeycloakWebPassword()
        log("auto-login credentials usernamePresent=${username.isNotBlank()} passwordPresent=${password.isNotBlank()}")
        if (username.isBlank() || password.isBlank()) return false

        autoLoginInProgress = true
        val browserHolder = arrayOfNulls<JBCefBrowser>(1)
        val timerHolder = arrayOfNulls<Timer>(1)
        try {
            val finished = CountDownLatch(1)
            val result = booleanArrayOf(false)
            ApplicationManager.getApplication().invokeAndWait {
                val b = JBCefBrowser(baseUrl)
                browserHolder[0] = b
                log("auto-login loading hidden browser base=$baseUrl")
                timerHolder[0] = installBackgroundAutomation(b, baseUrl, username, password, result, finished)
                b.loadURL(baseUrl)
            }
            val completed = finished.await(35, TimeUnit.SECONDS)
            if (!completed) {
                log("auto-login timeout waiting for API probe success")
            }
            log("auto-login finished completed=$completed result=${result[0]}")
            return completed && result[0]
        } finally {
            val toDispose = browserHolder[0]
            val timer = timerHolder[0]
            ApplicationManager.getApplication().invokeLater({
                timer?.stop()
                if (toDispose != null) Disposer.dispose(toDispose)
            }, ModalityState.any())
            autoLoginInProgress = false
        }
    }

    private fun ensureBrowser(baseUrl: String): JBCefBrowser? {
        if (!JBCefApp.isSupported()) return null
        var instance = browser
        ApplicationManager.getApplication().invokeAndWait {
            if (browser == null) {
                log("interactive-login creating reusable browser base=$baseUrl")
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
            val username = settings.keycloakWebUsername.trim()
            val password = settings.getKeycloakWebPassword()
            log("interactive-login opening dialog base=$baseUrl usernamePresent=${username.isNotBlank()} passwordPresent=${password.isNotBlank()}")
            val autofillTimer = installInteractiveAutofill(browser, username, password)
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

            val accepted = dialog.showAndGet()
            autofillTimer?.stop()
            val finalUrl = browser.cefBrowser.url.orEmpty()
            result[0] = accepted && finalUrl.startsWith(baseUrl) && !looksLikeLogin(finalUrl)
            log("interactive-login closed accepted=$accepted finalUrl=$finalUrl result=${result[0]}")
            finished.countDown()
        }

        finished.await(1, TimeUnit.MINUTES)
        return result[0]
    }

    private fun installBackgroundAutomation(
        browser: JBCefBrowser,
        baseUrl: String,
        username: String,
        password: String,
        result: BooleanArray,
        finished: CountDownLatch,
    ): Timer {
        val successQuery = JBCefJSQuery.create(browser)
        val failureQuery = JBCefJSQuery.create(browser)
        val logQuery = JBCefJSQuery.create(browser)

        successQuery.addHandler { payload ->
            if (finished.count > 0L) {
                log("auto-login probe success payload=${payload.take(200)}")
                result[0] = true
                finished.countDown()
            } else {
                log("auto-login probe success ignored after finish payload=${payload.take(200)}")
            }
            null
        }
        failureQuery.addHandler { payload ->
            log("auto-login probe/fill failure payload=${payload.take(500)}")
            null
        }
        logQuery.addHandler { payload ->
            log("auto-login js $payload")
            null
        }

        browser.cefBrowser.client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browserRef: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain != true || finished.count == 0L) return
                val url = browserRef?.url.orEmpty()
                log("auto-login onLoadEnd status=$httpStatusCode url=$url")
                executeBackgroundScript(browser, baseUrl, username, password, successQuery, failureQuery, logQuery, "load")
            }
        })

        var tick = 0
        val timer = Timer(1000) { event ->
            if (finished.count == 0L) {
                log("auto-login timer stopping because finished")
                (event.source as? Timer)?.stop()
                return@Timer
            }
            tick += 1
            val currentUrl = runCatching { browser.cefBrowser.url.orEmpty() }.getOrDefault("")
            log("auto-login timer tick=$tick currentUrl=$currentUrl")
            executeBackgroundScript(browser, baseUrl, username, password, successQuery, failureQuery, logQuery, "timer-$tick")
            if (tick >= 34) {
                log("auto-login timer reached max ticks=$tick without success")
                (event.source as? Timer)?.stop()
            }
        }
        timer.initialDelay = 500
        timer.start()
        return timer
    }

    private fun executeBackgroundScript(
        browser: JBCefBrowser,
        baseUrl: String,
        username: String,
        password: String,
        successQuery: JBCefJSQuery,
        failureQuery: JBCefJSQuery,
        logQuery: JBCefJSQuery,
        reason: String,
    ) {
        val url = browser.cefBrowser.url.orEmpty()
        log("auto-login execute script reason=$reason url=$url")
        browser.cefBrowser.executeJavaScript(
            backgroundScript(baseUrl, username, password, successQuery, failureQuery, logQuery, reason),
            url,
            0,
        )
    }

    private fun installInteractiveAutofill(browser: JBCefBrowser, username: String, password: String): Timer? {
        log("interactive-autofill install usernamePresent=${username.isNotBlank()} passwordPresent=${password.isNotBlank()}")
        if (username.isBlank() || password.isBlank()) {
            log("interactive-autofill skipped missing credentials")
            return null
        }

        val logQuery = JBCefJSQuery.create(browser)
        logQuery.addHandler { payload ->
            log("interactive-autofill js $payload")
            null
        }

        browser.cefBrowser.client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browserRef: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain != true) return
                val url = browserRef?.url.orEmpty()
                log("interactive-autofill onLoadEnd status=$httpStatusCode url=$url")
                executeInteractiveAutofill(browser, username, password, logQuery, "load")
            }
        })

        var tick = 0
        val timer = Timer(800) { event ->
            tick += 1
            val currentUrl = runCatching { browser.cefBrowser.url.orEmpty() }.getOrDefault("")
            log("interactive-autofill timer tick=$tick currentUrl=$currentUrl")
            executeInteractiveAutofill(browser, username, password, logQuery, "timer-$tick")
            if (tick >= 90) {
                log("interactive-autofill timer reached max ticks=$tick")
                (event.source as? Timer)?.stop()
            }
        }
        timer.initialDelay = 300
        timer.start()
        return timer
    }

    private fun executeInteractiveAutofill(
        browser: JBCefBrowser,
        username: String,
        password: String,
        logQuery: JBCefJSQuery,
        reason: String,
    ) {
        val url = browser.cefBrowser.url.orEmpty()
        log("interactive-autofill execute script reason=$reason url=$url")
        browser.cefBrowser.executeJavaScript(
            interactiveAutofillScript(username, password, logQuery, reason),
            url,
            0,
        )
    }

    private fun backgroundScript(
        baseUrl: String,
        username: String,
        password: String,
        successQuery: JBCefJSQuery,
        failureQuery: JBCefJSQuery,
        logQuery: JBCefJSQuery,
        reason: String,
    ): String = """
        (function() {
          function log(msg) { try { ${logQuery.inject("msg")} } catch (e) {} }
          function ok(msg) { try { ${successQuery.inject("msg")} } catch (e) {} }
          function fail(msg) { try { ${failureQuery.inject("msg")} } catch (e) {} }
          var current = window.location.href || '';
          var form = document.querySelector('#kc-form-login');
          var user = document.querySelector('#username');
          var pass = document.querySelector('#password');
          var btn = document.querySelector('#kc-login');
          var alreadySubmitted = window.__ciStatusKeycloakSubmitted === true;
          log('reason=${jsString(reason)} url=' + current + ' ready=' + document.readyState + ' form=' + !!form + ' user=' + !!user + ' pass=' + !!pass + ' btn=' + !!btn + ' submitted=' + alreadySubmitted);

          if (form && user && pass && !alreadySubmitted) {
            window.__ciStatusKeycloakSubmitted = true;
            try {
              user.focus();
              user.value = '${jsString(username)}';
              user.dispatchEvent(new Event('input', { bubbles: true }));
              user.dispatchEvent(new Event('change', { bubbles: true }));
              pass.focus();
              pass.value = '${jsString(password)}';
              pass.dispatchEvent(new Event('input', { bubbles: true }));
              pass.dispatchEvent(new Event('change', { bubbles: true }));
              log('form filled; scheduling submit');
              setTimeout(function() {
                try {
                  if (btn) { btn.click(); log('clicked #kc-login'); }
                  else { form.submit(); log('submitted form'); }
                } catch (e) { fail('submit-error=' + e); }
              }, 250);
            } catch (e) {
              fail('fill-error=' + e);
            }
            return;
          }

          if (form && alreadySubmitted) {
            log('form still present but already submitted; waiting for redirect');
            return;
          }

          if (current.startsWith('${jsString(baseUrl)}') && current.indexOf('commenceLogin') === -1) {
            log('on Jenkins origin; starting API probe');
            fetch('${jsString(baseUrl)}/whoAmI/api/json', { credentials: 'include', cache: 'no-store' })
              .then(function(r) {
                log('probe status=' + r.status + ' url=' + r.url);
                if (r.ok) ok('status=' + r.status + ' url=' + r.url);
                else fail('status=' + r.status + ' url=' + r.url);
              })
              .catch(function(e) { fail('probe-error=' + e); });
          } else {
            log('not Jenkins origin or login-like URL; waiting');
          }
        })();
    """.trimIndent()

    private fun interactiveAutofillScript(username: String, password: String, logQuery: JBCefJSQuery, reason: String): String = """
        (function() {
          function log(msg) { try { ${logQuery.inject("msg")} } catch (e) {} }
          var current = window.location.href || '';
          var form = document.querySelector('#kc-form-login');
          var user = document.querySelector('#username');
          var pass = document.querySelector('#password');
          var filled = window.__ciStatusKeycloakInteractiveFilled === true;
          log('reason=${jsString(reason)} url=' + current + ' ready=' + document.readyState + ' form=' + !!form + ' user=' + !!user + ' pass=' + !!pass + ' filled=' + filled);
          if (form && user && pass && !filled) {
            window.__ciStatusKeycloakInteractiveFilled = true;
            try {
              user.focus();
              user.value = '${jsString(username)}';
              user.dispatchEvent(new Event('input', { bubbles: true }));
              user.dispatchEvent(new Event('change', { bubbles: true }));
              pass.focus();
              pass.value = '${jsString(password)}';
              pass.dispatchEvent(new Event('input', { bubbles: true }));
              pass.dispatchEvent(new Event('change', { bubbles: true }));
              log('credentials filled; waiting for user submit');
            } catch (e) {
              log('fill-error=' + e);
            }
          }
        })();
    """.trimIndent()

    private fun looksLikeLogin(url: String): Boolean =
        url.contains("commenceLogin", ignoreCase = true) ||
            url.contains("keycloak", ignoreCase = true) ||
            url.contains("login-actions", ignoreCase = true)

    private fun jsString(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")

    private fun log(message: String) {
        CiStatusDebugLog.keycloak(project, message)
    }

    override fun dispose() {
        browser?.let { Disposer.dispose(it) }
        browser = null
    }

    companion object {
        fun getInstance(project: Project): KeycloakSessionService = project.getService(KeycloakSessionService::class.java)
    }
}
