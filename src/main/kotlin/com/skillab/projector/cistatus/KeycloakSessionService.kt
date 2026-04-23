package com.skillab.projector.cistatus

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JPanel

@Service(Service.Level.PROJECT)
class KeycloakSessionService(private val project: Project) : Disposable {
    private val settings = CiStatusSettings.getInstance(project)
    private var browser: JBCefBrowser? = null

    fun isAvailable(): Boolean = JBCefApp.isSupported() && settings.experimentalKeycloakInteractiveFallback

    fun ensureLoggedIn(baseUrl: String): Boolean {
        if (!isAvailable()) return false
        val browser = ensureBrowser(baseUrl) ?: return false
        return openLoginDialog(baseUrl, browser)
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

        val script = """
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
