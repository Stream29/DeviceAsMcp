package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.AuthSession
import io.github.stream29.mcp.device.protocol.CreateAuthKeyRequest
import io.github.stream29.mcp.device.protocol.DaemonEnrollmentRequest
import io.github.stream29.mcp.device.protocol.DeviceCredential
import io.github.stream29.mcp.device.protocol.DeviceEnrollmentToken
import io.github.stream29.mcp.device.protocol.DeviceSummary
import io.github.stream29.mcp.device.protocol.FileManifest
import io.github.stream29.mcp.device.protocol.FileManifestEntry
import io.github.stream29.mcp.device.protocol.FileTransferManifestRequest
import io.github.stream29.mcp.device.protocol.FileTransferRecord
import io.github.stream29.mcp.device.protocol.FileTransferStatus
import io.github.stream29.mcp.device.protocol.InstanceId
import io.github.stream29.mcp.device.protocol.ManifestEntryType
import io.github.stream29.mcp.device.protocol.PasswordLoginRequest
import io.github.stream29.mcp.device.protocol.ProtocolJson
import io.github.stream29.mcp.device.protocol.RegisterRequest
import io.github.stream29.mcp.device.protocol.RenameDeviceRequest
import io.github.stream29.mcp.device.protocol.TransferId
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplicationTest {
    @Test
    fun healthExposesStableInstanceId() = testApplication {
        val runtime = testRuntime()
        application { deviceAsMcpModule(runtime) }

        val first = client.get("/health").body<String>()
        val second = client.get("/health").body<String>()
        val liveness = client.get("/health/live")
        val readiness = client.get("/health/ready")

        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.OK, liveness.status)
        assertEquals(HttpStatusCode.OK, readiness.status)
        assertEquals(first, second)
        assertTrue(first.contains(runtime.config.instanceId.value))
        assertTrue(liveness.body<String>().contains("\"status\":\"ok\""))
        assertTrue(readiness.body<String>().contains("\"status\":\"ready\""))
        assertTrue(readiness.body<String>().contains("\"accounts\":true"))
    }

    @Test
    fun passwordLoginDeviceAndAuthKeyFlow() = testApplication {
        application { deviceAsMcpModule(testRuntime()) }
        val jsonClient = createClient { install(ContentNegotiation) { json(ProtocolJson) } }

        val register = jsonClient.post("/api/auth/register") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(RegisterRequest("alice", "correct horse battery staple"))
        }
        assertEquals(HttpStatusCode.OK, register.status)
        val session = register.body<AuthSession>()
        val sessionCookie = register.headers[HttpHeaders.SetCookie].orEmpty()
        assertTrue(sessionCookie.contains("HttpOnly"))
        assertTrue(sessionCookie.contains("SameSite=Lax"))
        assertTrue(sessionCookie.contains("Max-Age="))

        val login = jsonClient.post("/api/auth/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(PasswordLoginRequest("alice", "correct horse battery staple"))
        }
        assertEquals(HttpStatusCode.OK, login.status)
        assertNotEquals(session.accessToken, login.body<AuthSession>().accessToken)

        val enrollmentToken = jsonClient.post("/api/enrollment-token") {
            bearer(session.accessToken)
        }.body<DeviceEnrollmentToken>()
        val device = jsonClient.post("/daemon/enroll") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(DaemonEnrollmentRequest(enrollmentToken.token, "Laptop", "linux-x64"))
        }
        assertEquals(HttpStatusCode.Created, device.status)
        val credential = device.body<DeviceCredential>()
        val rename = jsonClient.put("/api/devices/${credential.deviceId.value}") {
            bearer(session.accessToken)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(RenameDeviceRequest("Workstation"))
        }
        assertEquals(HttpStatusCode.NoContent, rename.status)
        val devices = jsonClient.get("/api/devices") {
            bearer(session.accessToken)
        }.body<List<DeviceSummary>>()
        assertEquals(listOf("Workstation"), devices.map(DeviceSummary::name))

        val key = jsonClient.post("/api/auth-keys") {
            bearer(session.accessToken)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(CreateAuthKeyRequest("CI"))
        }
        assertEquals(HttpStatusCode.Created, key.status)

        assertEquals(
            HttpStatusCode.NoContent,
            jsonClient.post("/api/auth/logout") { bearer(session.accessToken) }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient.get("/api/me") { bearer(session.accessToken) }.status,
        )
    }

    @Test
    fun mcpGetAndDeleteAreRejected() = testApplication {
        application { deviceAsMcpModule(testRuntime()) }
        assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/mcp").status)
        assertEquals(HttpStatusCode.MethodNotAllowed, client.delete("/mcp").status)
    }

    @Test
    fun corsAllowsCredentialedManagementWritesFromFrontend() = testApplication {
        application { deviceAsMcpModule(testRuntime()) }

        val deleteResponse = client.options("/api/auth-keys/key") {
            header(HttpHeaders.Origin, "http://localhost:8081")
            header(HttpHeaders.AccessControlRequestMethod, "DELETE")
        }
        val putResponse = client.options("/api/devices/device") {
            header(HttpHeaders.Origin, "http://localhost:8081")
            header(HttpHeaders.AccessControlRequestMethod, "PUT")
        }

        assertEquals(HttpStatusCode.OK, deleteResponse.status)
        assertTrue(deleteResponse.headers[HttpHeaders.AccessControlAllowMethods].orEmpty().contains("DELETE"))
        assertEquals("true", deleteResponse.headers[HttpHeaders.AccessControlAllowCredentials])
        assertEquals(HttpStatusCode.OK, putResponse.status)
        assertTrue(putResponse.headers[HttpHeaders.AccessControlAllowMethods].orEmpty().contains("PUT"))
        assertEquals("true", putResponse.headers[HttpHeaders.AccessControlAllowCredentials])
    }

    @Test
    fun daemonApprovalRequiresSameOriginPostAndIsSingleUse() = testApplication {
        val runtime = testRuntime()
        application { deviceAsMcpModule(runtime) }
        val user = requireNotNull(runtime.accounts.register("approver", "long enough password"))
        val session = runtime.accounts.issueSession(user)
        val pending = runtime.oauth.startDaemonBrowserLogin()

        val legacyGet = client.get(
            "/api/auth/daemon/complete?request=${pending.requestId}&status=approve",
        ) {
            bearer(session)
        }
        assertEquals(HttpStatusCode.OK, legacyGet.status)
        assertNull(runtime.oauth.pollDaemonBrowserLogin(pending.requestId))

        val form = FormDataContent(
            Parameters.build {
                append("request", pending.requestId)
                append("status", "approve")
            },
        )
        val crossOrigin = client.post("/api/auth/daemon/complete") {
            bearer(session)
            setBody(form)
        }
        assertEquals(HttpStatusCode.Forbidden, crossOrigin.status)

        val approved = client.post("/api/auth/daemon/complete") {
            bearer(session)
            header(HttpHeaders.Origin, "http://localhost")
            setBody(form)
        }
        assertEquals(HttpStatusCode.OK, approved.status)
        assertNotNull(runtime.oauth.pollDaemonBrowserLogin(pending.requestId))

        val repeated = client.post("/api/auth/daemon/complete") {
            bearer(session)
            header(HttpHeaders.Origin, "http://localhost")
            setBody(form)
        }
        assertEquals(HttpStatusCode.Gone, repeated.status)
    }

    @Test
    fun tokenEndpointReturnsOAuthErrorShape() = testApplication {
        application { deviceAsMcpModule(testRuntime()) }
        val response = client.post("/oauth/token") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("grant_type", "authorization_code")
                    },
                ),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertTrue(response.body<String>().contains("\"error\":\"invalid_request\""))
    }

    @Test
    fun relayDestinationWaitsForIndependentManifest() = testApplication {
        val runtime = testRuntime()
        application { deviceAsMcpModule(runtime) }
        val jsonClient = createClient { install(ContentNegotiation) { json(ProtocolJson) } }
        val user = requireNotNull(runtime.accounts.register("relay-user", "long enough password"))
        val source = runtime.accounts.enrollDevice(user.id, "source", "linux-x64")
        val destination = runtime.accounts.enrollDevice(user.id, "destination", "linux-x64")
        val transferId = TransferId(UUID.randomUUID().toString())
        val record = FileTransferRecord(
            transferId = transferId,
            userId = user.id,
            sourceDeviceId = source.deviceId,
            sourcePath = "/source",
            destinationDeviceId = destination.deviceId,
            destinationPath = "/destination",
            relayInstanceId = runtime.config.instanceId,
        )
        assertTrue(runtime.routing.createTransfer(record))
        assertTrue(runtime.routing.updateTransfer(transferId, FileTransferStatus.RUNNING))
        val manifest = FileManifest(
            ManifestEntryType.DIRECTORY,
            listOf(FileManifestEntry("file.txt", ManifestEntryType.FILE, 4)),
        )

        coroutineScope {
            val waiting = async {
                jsonClient.get("/relay/${transferId.value}/manifest") {
                    header("X-Device-Id", destination.deviceId.value)
                    header("X-Device-Secret", destination.secret)
                    header("X-Relay-Instance-Id", runtime.config.instanceId.value)
                }
            }
            delay(100)
            val uploaded = jsonClient.put("/relay/${transferId.value}/manifest") {
                header("X-Device-Id", source.deviceId.value)
                header("X-Device-Secret", source.secret)
                header("X-Relay-Instance-Id", runtime.config.instanceId.value)
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(FileTransferManifestRequest(manifest))
            }

            assertEquals(HttpStatusCode.Accepted, uploaded.status)
            val downloaded = waiting.await()
            assertEquals(HttpStatusCode.OK, downloaded.status)
            assertEquals(manifest, downloaded.body<FileManifest>())
        }
    }

    private fun testRuntime() = ServerRuntime(
        ServerConfig(
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
            instanceId = InstanceId("test-instance"),
        ),
        InMemoryAccountStore(),
        InMemoryRoutingStore(),
    )

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }
}
