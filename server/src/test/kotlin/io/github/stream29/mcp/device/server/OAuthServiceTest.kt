package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.InstanceId
import io.ktor.http.Parameters
import kotlinx.coroutines.test.runTest
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class OAuthServiceTest {
    @Test
    fun dynamicClientUsesAuthorizationCodeWithPkce() = runTest {
        val accounts = InMemoryAccountStore()
        val routing = InMemoryRoutingStore()
        val config = ServerConfig(
            host = "127.0.0.1",
            port = 0,
            publicBaseUrl = "http://localhost",
            frontendBaseUrl = "http://localhost:8081",
            postgresUrl = null,
            postgresUser = "",
            postgresPassword = "",
            redisUrl = null,
            rabbitMqUrl = null,
            githubClientId = null,
            githubClientSecret = null,
            instanceId = InstanceId("test"),
        )
        val service = OAuthService(config, accounts, routing)
        val user = requireNotNull(accounts.register("oauth-user", "long enough password"))
        val client = service.registerClient(
            OAuthClientRegistrationRequest(listOf("http://127.0.0.1:9000/callback")),
        )
        val verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
        )
        val redirect = service.authorize(
            user.id,
            "code",
            client.clientId,
            "http://127.0.0.1:9000/callback",
            challenge,
            "S256",
            service.resourceUri,
            "state",
        )
        val code = java.net.URI(redirect).query
            .split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=') }
            .getValue("code")
        val tokenParameters = Parameters.build {
            append("grant_type", "authorization_code")
            append("code", code)
            append("client_id", client.clientId)
            append("redirect_uri", "http://127.0.0.1:9000/callback")
            append("code_verifier", verifier)
            append("resource", service.resourceUri)
        }
        val token = service.exchangeCode(tokenParameters)

        val principal = assertNotNull(accounts.mcpPrincipal(token.accessToken))
        assertEquals(service.resourceUri, principal.audience)
        assertEquals(setOf(OAuthService.MCP_SCOPE), principal.scopes)
        val reused = assertFailsWith<OAuthRequestException> {
            service.exchangeCode(tokenParameters)
        }
        assertEquals("invalid_grant", reused.error)
        service.close()
    }

    @Test
    fun preRegisteredClientAndDaemonApprovalWorkWithoutLocalState() = runTest {
        val accounts = InMemoryAccountStore()
        val routing = InMemoryRoutingStore()
        val config = config(
            oauthClients =
                """
                [
                  {
                    "client_id":"desktop-client",
                    "redirect_uris":["http://127.0.0.1:9876/callback"],
                    "client_name":"Desktop"
                  }
                ]
                """.trimIndent(),
        )
        val service = OAuthService(config, accounts, routing)
        val user = requireNotNull(accounts.register("pre-user", "long enough password"))
        val verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
        )

        val redirect = service.authorize(
            user.id,
            "code",
            "desktop-client",
            "http://127.0.0.1:9876/callback",
            challenge,
            "S256",
            service.resourceUri,
            "state",
        )
        assertEquals("http", java.net.URI(redirect).scheme)

        val browserLogin = service.startDaemonBrowserLogin()
        service.approveDaemonBrowserLogin(browserLogin.requestId, user.id)
        assertFailsWith<IllegalStateException> {
            service.approveDaemonBrowserLogin(browserLogin.requestId, user.id)
        }
        assertFailsWith<IllegalStateException> {
            service.denyDaemonBrowserLogin(browserLogin.requestId)
        }
        val approval = assertNotNull(service.pollDaemonBrowserLogin(browserLogin.requestId))
        assertEquals(user.id, accounts.consumeEnrollmentToken(approval.enrollment.token))
        assertFailsWith<IllegalStateException> {
            service.pollDaemonBrowserLogin(browserLogin.requestId)
        }
        service.close()
    }

    @Test
    fun rejectsInvalidClientMetadataScopeAndBrowserState() = runTest {
        val accounts = InMemoryAccountStore()
        val service = OAuthService(
            config(
                oauthClients =
                    """
                    [{
                      "client_id":"desktop-client",
                      "redirect_uris":["http://127.0.0.1:9876/callback"]
                    }]
                    """.trimIndent(),
                githubConfigured = true,
            ),
            accounts,
            InMemoryRoutingStore(),
        )

        assertFailsWith<IllegalArgumentException> {
            service.registerClient(
                OAuthClientRegistrationRequest(
                    listOf(
                        "http://127.0.0.1:9000/callback",
                        "http://127.0.0.1:9000/callback",
                    ),
                ),
            )
        }
        val user = requireNotNull(accounts.register("scope-user", "long enough password")).id
        assertFailsWith<IllegalArgumentException> {
            service.authorize(
                user,
                "code",
                "desktop-client",
                "http://127.0.0.1:9876/callback",
                "a".repeat(43),
                "S256",
                service.resourceUri,
                "state",
                "unknown",
            )
        }

        val github = service.startGithubLogin()
        assertFalse(github.state.isBlank())
        assertFailsWith<IllegalArgumentException> {
            service.finishGithub("unused-code", github.state, null)
        }
        service.close()
    }

    private fun config(
        oauthClients: String? = null,
        githubConfigured: Boolean = false,
    ) = ServerConfig(
        host = "127.0.0.1",
        port = 0,
        publicBaseUrl = "http://localhost",
        frontendBaseUrl = "http://localhost:8081",
        postgresUrl = null,
        postgresUser = "",
        postgresPassword = "",
        redisUrl = null,
        rabbitMqUrl = null,
        githubClientId = "github-client".takeIf { githubConfigured },
        githubClientSecret = "github-secret".takeIf { githubConfigured },
        oauthPreRegisteredClientsJson = oauthClients,
        instanceId = InstanceId("test"),
    )
}
