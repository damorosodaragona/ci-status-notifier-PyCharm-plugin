package com.damorosodaragona.jenkinsnotifier

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeycloakSessionServiceReflectionTest {
    @Test
    fun `looksLikeLogin detects Jenkins and Keycloak login URLs`() {
        val service = KeycloakSessionService(project = projectWithServiceStub())

        assertTrue(service.looksLikeLoginForTest("https://jenkins.example.org/securityRealm/commenceLogin"))
        assertTrue(service.looksLikeLoginForTest("https://id.example.org/keycloak/realms/test/protocol/openid-connect/auth"))
        assertTrue(service.looksLikeLoginForTest("https://id.example.org/realms/test/login-actions/authenticate"))
        assertFalse(service.looksLikeLoginForTest("https://jenkins.example.org/job/projector/lastBuild/api/json"))
    }

    @Test
    fun `jsString escapes values before injecting them in browser scripts`() {
        val service = KeycloakSessionService(project = projectWithServiceStub())

        assertTrue(service.jsStringForTest("a'b").contains("\\'"))
        assertTrue(service.jsStringForTest("a\\b").contains("\\\\"))
        assertTrue(service.jsStringForTest("a\nb").contains("\\n"))
        assertFalse(service.jsStringForTest("a\rb").contains("\r"))
    }

    @Test
    fun `background automation fills credentials submits login and probes Jenkins API`() {
        val script = KeycloakAutofillScripts.backgroundScript(
            baseUrl = "https://jenkins.example.org/",
            username = "robot'user",
            password = "secret\\pw",
            successInject = "ok(msg)",
            failureInject = "fail(msg)",
            logInject = "log(msg)",
            reason = "timer-1",
        )

        assertTrue("user.value = 'robot\\'user'" in script)
        assertTrue("pass.value = 'secret\\\\pw'" in script)
        assertTrue("btn.click()" in script)
        assertTrue("fetch('https://jenkins.example.org/whoAmI/api/json'" in script)
        assertTrue("credentials: 'include'" in script)
        assertTrue("ok(msg)" in script)
        assertTrue("fail(msg)" in script)
    }

    @Test
    fun `interactive autofill fills credentials but leaves submit to user`() {
        val script = KeycloakAutofillScripts.interactiveAutofillScript(
            username = "robot",
            password = "secret",
            logInject = "log(msg)",
            reason = "load",
        )

        assertTrue("user.value = 'robot'" in script)
        assertTrue("pass.value = 'secret'" in script)
        assertTrue("dispatchEvent(new Event('input'" in script)
        assertTrue("dispatchEvent(new Event('change'" in script)
        assertTrue("credentials filled; waiting for user submit" in script)
        assertFalse("btn.click()" in script)
        assertFalse("form.submit()" in script)
    }

    private fun projectWithServiceStub(settings: CiStatusSettings = CiStatusSettings()): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getService" -> if (args?.firstOrNull() == CiStatusSettings::class.java) settings else null
                "getName" -> "test-project"
                "isDisposed" -> false
                "isOpen" -> true
                "toString" -> "Project(test-project)"
                else -> null
            }
        } as Project
    }

    private fun KeycloakSessionService.looksLikeLoginForTest(url: String): Boolean {
        val method = KeycloakSessionService::class.java.getDeclaredMethod("looksLikeLogin", String::class.java)
        method.isAccessible = true
        return method.invoke(this, url) as Boolean
    }

    private fun KeycloakSessionService.jsStringForTest(value: String): String {
        val method = KeycloakSessionService::class.java.getDeclaredMethod("jsString", String::class.java)
        method.isAccessible = true
        return method.invoke(this, value) as String
    }
}
