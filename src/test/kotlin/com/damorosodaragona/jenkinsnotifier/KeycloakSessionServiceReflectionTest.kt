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

    private fun projectWithServiceStub(): Project {
        val settings = CiStatusSettings()
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
