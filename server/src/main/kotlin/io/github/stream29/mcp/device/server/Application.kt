package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.AuthSession
import io.github.stream29.mcp.device.protocol.ConnectionId
import io.github.stream29.mcp.device.protocol.CreateAuthKeyRequest
import io.github.stream29.mcp.device.protocol.DaemonEnrollmentRequest
import io.github.stream29.mcp.device.protocol.DeviceId
import io.github.stream29.mcp.device.protocol.FileTransferContentRequest
import io.github.stream29.mcp.device.protocol.FileTransferFailureRequest
import io.github.stream29.mcp.device.protocol.FileTransferFinishRequest
import io.github.stream29.mcp.device.protocol.FileTransferManifestRequest
import io.github.stream29.mcp.device.protocol.FileTransferPlanRequest
import io.github.stream29.mcp.device.protocol.InstanceId
import io.github.stream29.mcp.device.protocol.MAX_MANIFEST_BYTES
import io.github.stream29.mcp.device.protocol.OperationErrorCode
import io.github.stream29.mcp.device.protocol.OperationResultEnvelope
import io.github.stream29.mcp.device.protocol.PasswordLoginRequest
import io.github.stream29.mcp.device.protocol.ProtocolJson
import io.github.stream29.mcp.device.protocol.RegisterRequest
import io.github.stream29.mcp.device.protocol.RenameDeviceRequest
import io.github.stream29.mcp.device.protocol.TransferId
import io.github.stream29.mcp.device.protocol.UserId
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.contentLength
import io.ktor.server.request.queryString
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.intercept
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.net.URI
import java.util.UUID

@Serializable
internal data class HealthResponse(
    val status: String,
    val instanceId: String,
    val components: Map<String, Boolean> = emptyMap(),
)

internal data class RuntimeReadiness(
    val accounts: Boolean,
    val routing: Boolean,
    val instanceRpc: Boolean,
) {
    val ready: Boolean = accounts && routing && instanceRpc
}

private data class DaemonConnectionContext(
    val userId: UserId,
    val deviceId: DeviceId,
)

private val DaemonConnectionContextKey =
    AttributeKey<DaemonConnectionContext>("DaemonConnectionContext")

internal class ServerRuntime(
    val config: ServerConfig,
    val accounts: AccountStore,
    val routing: RoutingStore,
    val connections: DeviceConnectionRegistry = DeviceConnectionRegistry(),
    val waiters: OperationWaiters = OperationWaiters(),
    val relay: FileRelayRegistry = FileRelayRegistry(),
) : AutoCloseable {
    private val noopRpc = NoopInstanceRpc()
    val operations = OperationService(config.instanceId, routing, connections, waiters, noopRpc)
    val fileTransfers = FileTransferService(config.instanceId, accounts, routing, operations, relay)
    val oauth = OAuthService(config, accounts, routing)
    val rpc: InstanceRpc =
        config.rabbitMqUrl?.let { RabbitInstanceRpc(it, config.instanceId, operations::handleRpc) } ?: noopRpc

    init {
        operations.attachRpc(rpc)
    }

    suspend fun readiness(): RuntimeReadiness = coroutineScope {
        val accountsReady = async { componentReady(accounts::isReady) }
        val routingReady = async { componentReady(routing::isReady) }
        val instanceRpcReady = async { componentReady(rpc::isReady) }
        RuntimeReadiness(
            accounts = accountsReady.await(),
            routing = routingReady.await(),
            instanceRpc = instanceRpcReady.await(),
        )
    }

    override fun close() {
        relay.close()
        oauth.close()
        rpc.close()
        (routing as? AutoCloseable)?.close()
        (accounts as? AutoCloseable)?.close()
    }

    private suspend fun componentReady(check: suspend () -> Boolean): Boolean =
        withTimeoutOrNull(READINESS_COMPONENT_TIMEOUT_MILLIS) {
            try {
                check()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                false
            }
        } ?: false

    companion object {
        private const val READINESS_COMPONENT_TIMEOUT_MILLIS = 2_000L
    }
}

internal fun createRuntime(config: ServerConfig): ServerRuntime {
    var accounts: AccountStore? = null
    var routing: RoutingStore? = null
    try {
        accounts = config.postgresUrl?.let {
            PostgresAccountStore(it, config.postgresUser, config.postgresPassword)
        } ?: InMemoryAccountStore()
        routing = config.redisUrl?.let(::RedisRoutingStore) ?: InMemoryRoutingStore()
        return ServerRuntime(config, accounts, routing)
    } catch (failure: Throwable) {
        (routing as? AutoCloseable)?.runCatching { close() }
        (accounts as? AutoCloseable)?.runCatching { close() }
        throw failure
    }
}

internal fun Application.deviceAsMcpModule(
    runtime: ServerRuntime = createRuntime(ServerConfig.fromEnvironment()),
) {
    val application = this
    install(ContentNegotiation) { json(ProtocolJson) }
    install(SSE)
    install(CORS) {
        runCatching {
            val frontend = URI(runtime.config.frontendBaseUrl)
            if (frontend.host != null && frontend.rawAuthority != null) {
                allowHost(frontend.rawAuthority, schemes = listOf(frontend.scheme.lowercase()))
            }
        }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Put)
        allowCredentials = true
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "invalid request")))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "invalid state")))
        }
        exception<kotlinx.coroutines.CancellationException> { _, cause -> throw cause }
        exception<Throwable> { call, cause ->
            application.environment.log.error("Unhandled request failure", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal error"))
        }
    }
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) { runtime.close() }

    val mcp = ModernMcpEndpoint(runtime)
    routing {
        get("/health") {
            call.respondReadiness(runtime)
        }
        get("/health/live") {
            call.respond(
                HealthResponse(
                    status = "ok",
                    instanceId = runtime.config.instanceId.value,
                ),
            )
        }
        get("/health/ready") {
            call.respondReadiness(runtime)
        }

        oauthRoutes(runtime)
        managementRoutes(runtime)
        daemonRoutes(runtime)
        relayRoutes(runtime)

        post("/mcp") { mcp.post(call) }
        get("/mcp") { mcp.unsupported(call) }
        delete("/mcp") { mcp.unsupported(call) }
    }
}

private suspend fun ApplicationCall.respondReadiness(runtime: ServerRuntime) {
    val readiness = runtime.readiness()
    respond(
        if (readiness.ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
        HealthResponse(
            status = if (readiness.ready) "ready" else "not_ready",
            instanceId = runtime.config.instanceId.value,
            components = mapOf(
                "accounts" to readiness.accounts,
                "routing" to readiness.routing,
                "instanceRpc" to readiness.instanceRpc,
            ),
        ),
    )
}

private fun io.ktor.server.routing.Route.oauthRoutes(runtime: ServerRuntime) {
    get("/.well-known/oauth-protected-resource") {
        call.respond(runtime.oauth.protectedResourceMetadata())
    }
    get("/.well-known/oauth-protected-resource/mcp") {
        call.respond(runtime.oauth.protectedResourceMetadata())
    }
    get("/.well-known/oauth-authorization-server") {
        call.respond(runtime.oauth.authorizationServerMetadata())
    }

    route("/api/auth") {
        post("/register") {
            val request = call.receive<RegisterRequest>()
            val user = runtime.accounts.register(request.username, request.password)
                ?: return@post call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "username unavailable or password too short"),
                )
            call.respondSession(runtime, user)
        }
        post("/login") {
            val request = call.receive<PasswordLoginRequest>()
            val user = runtime.accounts.authenticate(request.username, request.password)
                ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "invalid credentials"),
                )
            call.respondSession(runtime, user)
        }
        post("/github") {
            if (runtime.config.githubClientId == null || runtime.config.githubClientSecret == null) {
                call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "GitHub OAuth is not configured"))
            } else {
                val started = runtime.oauth.startGithubLogin()
                call.setGithubStateCookie(runtime, started.state)
                call.respond(mapOf("authorizationUrl" to started.authorizationUrl))
            }
        }
        get("/github/start") {
            if (runtime.config.githubClientId == null || runtime.config.githubClientSecret == null) {
                return@get call.respond(
                    HttpStatusCode.NotImplemented,
                    mapOf("error" to "GitHub OAuth is not configured"),
                )
            }
            val started = runtime.oauth.startGithubLogin()
            call.setGithubStateCookie(runtime, started.state)
            call.respondRedirect(started.authorizationUrl)
        }
        get("/github/callback") {
            val code = call.request.queryParameters["code"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "code is required"))
            val state = call.request.queryParameters["state"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "state is required"))
            val browserState = call.request.cookies[GITHUB_STATE_COOKIE]
            call.clearGithubStateCookie(runtime)
            val session = runtime.oauth.finishGithub(code, state, browserState)
            call.setSessionCookie(runtime, session)
            call.respondRedirect(
                "${runtime.config.frontendBaseUrl.trimEnd('/')}/login" +
                    "#session=${encodeFragmentComponent(session)}",
            )
        }
        get("/daemon/complete") {
            val requestId = call.request.queryParameters["request"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "request is required"))
            if (!runtime.oauth.daemonBrowserLoginExists(requestId)) {
                return@get call.respond(HttpStatusCode.Gone, mapOf("error" to "request expired"))
            }
            call.respondText(
                """
                <!doctype html>
                <html><body>
                <h1>DeviceAsMcp daemon login</h1>
                <p>Approve the daemon enrollment request on this computer.</p>
                <form method="post">
                  <input type="hidden" name="request" value="${escapeHtml(requestId)}">
                  <button name="status" value="approve">Approve</button>
                  <button name="status" value="deny">Deny</button>
                </form>
                </body></html>
                """.trimIndent(),
                ContentType.Text.Html,
            )
        }
        post("/daemon/complete") {
            if (!call.isSameOriginMutation(runtime.config.publicBaseUrl)) {
                return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "origin is not allowed"))
            }
            val parameters = call.receiveParameters()
            val requestId = parameters["request"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "request is required"))
            when (parameters["status"]) {
                "approve" -> {
                    val user = call.requireManagementUser(runtime.accounts) ?: return@post
                    try {
                        runtime.oauth.approveDaemonBrowserLogin(requestId, user.id)
                    } catch (_: IllegalStateException) {
                        return@post call.respond(
                            HttpStatusCode.Gone,
                            mapOf("error" to "request expired or already completed"),
                        )
                    }
                    call.respondText("Device login approved. Return to the daemon.")
                }
                "deny" -> {
                    try {
                        runtime.oauth.denyDaemonBrowserLogin(requestId)
                    } catch (_: IllegalStateException) {
                        return@post call.respond(
                            HttpStatusCode.Gone,
                            mapOf("error" to "request expired or already completed"),
                        )
                    }
                    call.respondText("Device login denied.")
                }
                else -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid decision"))
            }
        }
    }

    get("/oauth/authorize") {
        val user = call.managementToken()?.let { runtime.accounts.userByToken(it) }
        if (user == null) {
            val target = "${runtime.config.publicBaseUrl.trimEnd('/')}/oauth/authorize?${call.request.queryString()}"
            return@get call.respondRedirect(
                "${runtime.config.frontendBaseUrl.trimEnd('/')}/login?" +
                    "authorize=${encodeFragmentComponent(target)}",
            )
        }
        val destination = runtime.oauth.authorize(
            userId = user.id,
            responseType = call.request.queryParameters["response_type"],
            clientId = call.request.queryParameters["client_id"],
            redirectUri = call.request.queryParameters["redirect_uri"],
            codeChallenge = call.request.queryParameters["code_challenge"],
            codeChallengeMethod = call.request.queryParameters["code_challenge_method"],
            resource = call.request.queryParameters["resource"],
            scope = call.request.queryParameters["scope"],
            state = call.request.queryParameters["state"],
        )
        call.respondRedirect(destination)
    }
    post("/oauth/token") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(HttpHeaders.Pragma, "no-cache")
        try {
            call.respond(runtime.oauth.exchangeCode(call.receiveParameters()))
        } catch (failure: OAuthRequestException) {
            call.respond(
                HttpStatusCode.BadRequest,
                OAuthErrorResponse(failure.error, failure.message ?: "OAuth token request failed"),
            )
        }
    }
    post("/oauth/register") {
        try {
            call.respond(
                HttpStatusCode.Created,
                runtime.oauth.registerClient(call.receive<OAuthClientRegistrationRequest>()),
            )
        } catch (failure: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                OAuthErrorResponse(
                    "invalid_client_metadata",
                    failure.message ?: "Invalid client metadata",
                ),
            )
        }
    }
}

private fun io.ktor.server.routing.Route.managementRoutes(runtime: ServerRuntime) {
    route("/api") {
        get("/me") {
            val user = call.requireManagementUser(runtime.accounts) ?: return@get
            call.respond(user)
        }
        get("/devices") {
            val user = call.requireManagementUser(runtime.accounts) ?: return@get
            call.respond(
                runtime.accounts.devices(user.id).map { device ->
                    device.copy(online = runtime.routing.deviceOwner(device.id) != null)
                },
            )
        }
        put("/devices/{id}") {
            val user = call.requireManagementUser(runtime.accounts) ?: return@put
            val deviceId = call.parameters["id"]?.let(::DeviceId)
                ?: return@put call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<RenameDeviceRequest>()
            if (runtime.accounts.renameDevice(user.id, deviceId, request.name)) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        delete("/devices/{id}") {
            val user = call.requireManagementUser(runtime.accounts) ?: return@delete
            val deviceId = call.parameters["id"]?.let(::DeviceId)
                ?: return@delete call.respond(HttpStatusCode.BadRequest)
            if (runtime.accounts.revokeDevice(user.id, deviceId)) {
                try {
                    runtime.operations.disconnectDevice(deviceId)
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    call.application.environment.log.warn(
                        "Device ${deviceId.value} was revoked, but its connection could not be ended immediately",
                        failure,
                    )
                }
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        post("/enrollment-token") {
            val user = call.requireManagementUser(runtime.accounts) ?: return@post
            call.respond(HttpStatusCode.Created, runtime.accounts.createEnrollmentToken(user.id))
        }
        post("/auth/logout") {
            call.requireManagementUser(runtime.accounts)
                ?: return@post
            val token = call.managementToken()
            if (token != null) runtime.accounts.revokeSession(token)
            call.response.cookies.append(
                Cookie(
                    name = SESSION_COOKIE,
                    value = "",
                    path = "/",
                    maxAge = 0,
                    httpOnly = true,
                    secure = URI(runtime.config.publicBaseUrl).scheme.equals("https", ignoreCase = true),
                    extensions = mapOf("SameSite" to "Lax"),
                ),
            )
            call.respond(HttpStatusCode.NoContent)
        }
        get("/auth-keys") {
            val user = call.requireManagementUser(runtime.accounts) ?: return@get
            call.respond(runtime.accounts.authKeys(user.id))
        }
        post("/auth-keys") {
            val user = call.requireManagementUser(runtime.accounts) ?: return@post
            val request = call.receive<CreateAuthKeyRequest>()
            call.respond(
                HttpStatusCode.Created,
                runtime.accounts.createAuthKey(
                    user.id,
                    request.name,
                    runtime.oauth.resourceUri,
                    setOf(OAuthService.MCP_SCOPE),
                ),
            )
        }
        delete("/auth-keys/{id}") {
            val user = call.requireManagementUser(runtime.accounts) ?: return@delete
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            if (runtime.accounts.revokeAuthKey(user.id, id)) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}

private fun io.ktor.server.routing.Route.daemonRoutes(runtime: ServerRuntime) {
    post("/daemon/enroll") {
        val request = call.receive<DaemonEnrollmentRequest>()
        val userId = runtime.accounts.consumeEnrollmentToken(request.token)
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or expired token"))
        call.respond(
            HttpStatusCode.Created,
            runtime.accounts.enrollDevice(userId, request.name, request.platform),
        )
    }
    post("/daemon/browser-login") {
        call.respond(HttpStatusCode.Created, runtime.oauth.startDaemonBrowserLogin())
    }
    get("/daemon/browser-login/{requestId}") {
        val requestId = call.parameters["requestId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest)
        try {
            val token = runtime.oauth.pollDaemonBrowserLogin(requestId)
                ?: return@get call.respond(HttpStatusCode.Accepted, mapOf("status" to "pending"))
            call.respond(token)
        } catch (failure: IllegalStateException) {
            call.respond(HttpStatusCode.Gone, mapOf("error" to (failure.message ?: "login unavailable")))
        }
    }

    route("/daemon/connect", HttpMethod.Get) {
        @Suppress("DEPRECATION")
        intercept(ApplicationCallPipeline.Plugins) {
            val deviceId = call.request.queryParameters["deviceId"]?.let(::DeviceId)
            if (deviceId == null) {
                call.respond(HttpStatusCode.BadRequest)
                finish()
                return@intercept
            }
            val secret = call.request.headers[DEVICE_SECRET_HEADER]
            if (secret == null || !runtime.accounts.authenticateDevice(deviceId, secret)) {
                call.respond(HttpStatusCode.Unauthorized)
                finish()
                return@intercept
            }
            val userId = runtime.accounts.deviceUser(deviceId)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized)
                finish()
                return@intercept
            }
            call.attributes.put(
                DaemonConnectionContextKey,
                DaemonConnectionContext(userId, deviceId),
            )
        }
        sse {
            val context = call.attributes[DaemonConnectionContextKey]
            val connectionId = ConnectionId(UUID.randomUUID().toString())
            val owner = DeviceOwner(
                runtime.config.instanceId,
                connectionId,
                System.currentTimeMillis() + OperationService.OWNER_TTL_MILLIS,
            )
            if (!runtime.routing.claimDevice(context.deviceId, owner)) return@sse
            val connectionEnded = CompletableDeferred<Unit>()
            val connection = LocalDeviceConnection(
                context.userId,
                context.deviceId,
                connectionId,
                this,
                connectionEnded,
            )
            if (!runtime.connections.put(connection)) {
                runtime.routing.releaseDevice(context.deviceId, owner)
                return@sse
            }
            // Revocation can commit after the pre-SSE check but before this connection is registered.
            val stillAuthenticated = call.request.headers[DEVICE_SECRET_HEADER]
                ?.let { runtime.accounts.authenticateDevice(context.deviceId, it) }
                ?: false
            if (!stillAuthenticated) {
                runtime.connections.remove(context.deviceId, connectionId)
                runtime.routing.releaseDevice(context.deviceId, owner)
                return@sse
            }
            if (!runtime.connections.keepAlive(context.deviceId, connectionId)) {
                runtime.connections.remove(context.deviceId, connectionId)
                runtime.routing.releaseDevice(context.deviceId, owner)
                return@sse
            }
            val renewJob = CoroutineScope(coroutineContext).launch {
                while (isActive) {
                    delay(OperationService.OWNER_RENEW_MILLIS)
                    val renewed = owner.copy(
                        expiresAtEpochMillis =
                            System.currentTimeMillis() + OperationService.OWNER_TTL_MILLIS,
                    )
                    if (
                        !runtime.routing.renewDevice(context.deviceId, renewed) ||
                        !runtime.connections.keepAlive(context.deviceId, connectionId)
                    ) {
                        connectionEnded.complete(Unit)
                        break
                    }
                }
            }
            try {
                connectionEnded.await()
            } finally {
                renewJob.cancel()
                runtime.connections.remove(context.deviceId, connectionId)
                runtime.routing.releaseDevice(context.deviceId, owner)
            }
        }
    }

    post("/daemon/result/{deviceId}") {
        val deviceId = call.authenticatedDevice(runtime) ?: return@post
        val userId = runtime.accounts.deviceUser(deviceId)
            ?: return@post call.respond(HttpStatusCode.Unauthorized)
        when (runtime.operations.acceptDaemonResult(userId, deviceId, call.receive<OperationResultEnvelope>())) {
            ResultAcceptance.ACCEPTED -> call.respond(HttpStatusCode.Accepted)
            ResultAcceptance.DUPLICATE -> call.respond(HttpStatusCode.Conflict)
            ResultAcceptance.UNKNOWN -> call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("retryable" to true),
            )
        }
    }
    post("/daemon/file-transfer/{transferId}/instance-lost") {
        val deviceId = call.authenticatedDevice(runtime) ?: return@post
        val transferId = call.transferId() ?: return@post
        if (runtime.fileTransfers.failIfCoordinatorLost(deviceId, transferId)) {
            call.respond(HttpStatusCode.Accepted)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}

private fun io.ktor.server.routing.Route.relayRoutes(runtime: ServerRuntime) {
    route("/relay/{transferId}") {
        put("/manifest") {
            if (!call.requireRelayInstance(runtime)) return@put
            val deviceId = call.authenticatedDevice(runtime) ?: return@put
            val transferId = call.transferId() ?: return@put
            if (call.request.contentLength()?.let { it > MAX_MANIFEST_BYTES } == true) {
                return@put call.respond(HttpStatusCode.PayloadTooLarge)
            }
            val body = call.receiveLimitedText(MAX_MANIFEST_BYTES) ?: return@put
            val request = ProtocolJson.decodeFromString<FileTransferManifestRequest>(body)
            if (request.manifest.entries.distinctBy { it.relativePath }.size != request.manifest.entries.size) {
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "duplicate manifest path"))
            }
            if (
                request.manifest.rootType == io.github.stream29.mcp.device.protocol.ManifestEntryType.FILE &&
                (
                    request.manifest.entries.size != 1 ||
                        request.manifest.entries.single().relativePath !=
                        io.github.stream29.mcp.device.protocol.ROOT_FILE_RELATIVE_PATH ||
                        request.manifest.entries.single().type !=
                        io.github.stream29.mcp.device.protocol.ManifestEntryType.FILE
                    )
            ) {
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid root file manifest"))
            }
            if (runtime.fileTransfers.putManifest(deviceId, transferId, request.manifest)) {
                call.respond(HttpStatusCode.Accepted)
            } else {
                call.respond(HttpStatusCode.Conflict)
            }
        }
        get("/manifest") {
            if (!call.requireRelayInstance(runtime)) return@get
            val deviceId = call.authenticatedDevice(runtime) ?: return@get
            val transferId = call.transferId() ?: return@get
            val transfer = runtime.fileTransfers.requireParticipant(transferId, deviceId)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            if (
                transfer.destinationDeviceId != deviceId ||
                transfer.relayInstanceId != runtime.config.instanceId ||
                transfer.status != io.github.stream29.mcp.device.protocol.FileTransferStatus.RUNNING
            ) {
                return@get call.respond(HttpStatusCode.Forbidden)
            }
            var manifest = runtime.fileTransfers.destinationManifest(deviceId, transferId)
            withTimeoutOrNull(RELAY_MANIFEST_WAIT_TIMEOUT_MILLIS) {
                while (manifest == null) {
                    delay(RELAY_MANIFEST_POLL_MILLIS)
                    manifest = runtime.fileTransfers.destinationManifest(deviceId, transferId)
                    val current = runtime.fileTransfers.requireParticipant(transferId, deviceId)
                    if (current?.status != io.github.stream29.mcp.device.protocol.FileTransferStatus.RUNNING) break
                }
            }
            val resolved = manifest ?: return@get call.respond(HttpStatusCode.GatewayTimeout)
            call.respond(resolved)
        }
        put("/plan") {
            if (!call.requireRelayInstance(runtime)) return@put
            val deviceId = call.authenticatedDevice(runtime) ?: return@put
            val transferId = call.transferId() ?: return@put
            val request = call.receive<FileTransferPlanRequest>()
            if (runtime.fileTransfers.putPlan(deviceId, transferId, request.plan)) {
                call.respond(HttpStatusCode.Accepted)
            } else {
                call.respond(HttpStatusCode.Conflict)
            }
        }
        get("/plan") {
            if (!call.requireRelayInstance(runtime)) return@get
            val deviceId = call.authenticatedDevice(runtime) ?: return@get
            val transferId = call.transferId() ?: return@get
            val participant = runtime.fileTransfers.requireParticipant(transferId, deviceId)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            if (
                participant.sourceDeviceId != deviceId ||
                participant.relayInstanceId != runtime.config.instanceId ||
                participant.status != io.github.stream29.mcp.device.protocol.FileTransferStatus.RUNNING
            ) {
                return@get call.respond(HttpStatusCode.Forbidden)
            }
            var plan = runtime.fileTransfers.sourcePlan(deviceId, transferId)
            withTimeoutOrNull(RELAY_PLAN_WAIT_TIMEOUT_MILLIS) {
                while (plan == null) {
                    delay(RELAY_PLAN_POLL_MILLIS)
                    plan = runtime.fileTransfers.sourcePlan(deviceId, transferId)
                    val current = runtime.fileTransfers.requireParticipant(transferId, deviceId)
                    if (current?.status != io.github.stream29.mcp.device.protocol.FileTransferStatus.RUNNING) break
                }
            }
            val resolved = plan ?: return@get call.respond(HttpStatusCode.GatewayTimeout)
            call.respond(resolved)
        }

        put("/content") {
            if (!call.requireRelayInstance(runtime)) return@put
            val deviceId = call.authenticatedDevice(runtime) ?: return@put
            val transferId = call.transferId() ?: return@put
            val participant = runtime.fileTransfers.requireParticipant(transferId, deviceId)
                ?: return@put call.respond(HttpStatusCode.NotFound)
            if (participant.sourceDeviceId != deviceId || participant.relayInstanceId != runtime.config.instanceId) {
                return@put call.respond(HttpStatusCode.Forbidden)
            }
            if (participant.status != io.github.stream29.mcp.device.protocol.FileTransferStatus.RUNNING) {
                return@put call.respond(HttpStatusCode.Conflict)
            }
            val key = call.relayFileKey(transferId) ?: return@put
            if (!key.validRelativePath()) return@put call.respond(HttpStatusCode.BadRequest)
            val manifest = runtime.fileTransfers.manifest(deviceId, transferId)
                ?: return@put call.respond(HttpStatusCode.Conflict)
            if (manifest.entries.none {
                    it.relativePath == key.relativePath &&
                        it.type == io.github.stream29.mcp.device.protocol.ManifestEntryType.FILE
                }
            ) {
                return@put call.respond(HttpStatusCode.BadRequest)
            }
            val plan = runtime.fileTransfers.sourcePlan(deviceId, transferId)
                ?: return@put call.respond(HttpStatusCode.Conflict)
            if (key.relativePath !in plan.acceptedFiles) {
                return@put call.respond(HttpStatusCode.BadRequest)
            }
            val expectedSize = manifest.entries
                .first { it.relativePath == key.relativePath && it.type == io.github.stream29.mcp.device.protocol.ManifestEntryType.FILE }
                .size
                ?: return@put call.respond(HttpStatusCode.BadRequest)
            val upload = RelayUpload(call.receiveChannel(), expectedSize)
            if (!runtime.relay.publish(key, upload)) {
                return@put call.respond(HttpStatusCode.Conflict)
            }
            try {
                when (
                    val completion = withTimeoutOrNull(RELAY_ATTEMPT_TIMEOUT_MILLIS) {
                        upload.completion.await()
                    }
                ) {
                    RelayCompletion.Verified -> call.respond(HttpStatusCode.Accepted)
                    is RelayCompletion.Rejected -> {
                        call.application.environment.log.warn(
                            "Relay upload rejected: transfer={}, path={}, attempt={}, reason={}",
                            transferId.value,
                            key.relativePath,
                            key.attempt,
                            completion.message,
                        )
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to completion.message))
                    }
                    null -> {
                        runtime.fileTransfers.fail(
                            deviceId,
                            transferId,
                            OperationErrorCode.OPERATION_TIMEOUT,
                            "Destination did not verify content before the relay timeout",
                        )
                        call.respond(HttpStatusCode.GatewayTimeout)
                    }
                }
            } finally {
                runtime.relay.remove(key, upload)
            }
        }

        get("/content") {
            if (!call.requireRelayInstance(runtime)) return@get
            val deviceId = call.authenticatedDevice(runtime) ?: return@get
            val transferId = call.transferId() ?: return@get
            val participant = runtime.fileTransfers.requireParticipant(transferId, deviceId)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            if (
                participant.destinationDeviceId != deviceId ||
                participant.relayInstanceId != runtime.config.instanceId ||
                participant.status != io.github.stream29.mcp.device.protocol.FileTransferStatus.RUNNING
            ) {
                return@get call.respond(HttpStatusCode.Forbidden)
            }
            val key = call.relayFileKey(transferId) ?: return@get
            if (!key.validRelativePath()) return@get call.respond(HttpStatusCode.BadRequest)
            val upload = runtime.relay.await(key, RELAY_WAIT_TIMEOUT_MILLIS)
                ?: return@get call.respond(HttpStatusCode.GatewayTimeout)
            val manifest = runtime.fileTransfers.destinationManifest(deviceId, transferId)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val plan = runtime.routing.transferPlan(transferId)
                ?: return@get call.respond(HttpStatusCode.Conflict)
            if (key.relativePath !in plan.acceptedFiles) {
                return@get call.respond(HttpStatusCode.BadRequest)
            }
            val expectedSize = manifest.entries
                .firstOrNull { it.relativePath == key.relativePath && it.type == io.github.stream29.mcp.device.protocol.ManifestEntryType.FILE }
                ?.size
            if (expectedSize == null || expectedSize != upload.expectedByteCount) {
                upload.completion.complete(RelayCompletion.Rejected("Relay byte count differs from manifest"))
                return@get call.respond(HttpStatusCode.Conflict)
            }
            runCatching {
                call.respondBytesWriter(
                    contentType = ContentType.Application.OctetStream,
                    contentLength = upload.expectedByteCount,
                ) {
                    upload.channel.copyTo(this)
                }
            }.onFailure { failure ->
                call.application.environment.log.warn(
                    "Relay content stream failed: transfer={}, path={}, attempt={}",
                    transferId.value,
                    key.relativePath,
                    key.attempt,
                    failure,
                )
                upload.completion.complete(RelayCompletion.Rejected("Relay content stream failed"))
            }
        }

        post("/content/source-complete") {
            if (!call.requireRelayInstance(runtime)) return@post
            val deviceId = call.authenticatedDevice(runtime) ?: return@post
            val transferId = call.transferId() ?: return@post
            val participant = runtime.fileTransfers.requireParticipant(transferId, deviceId)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            if (
                participant.sourceDeviceId != deviceId ||
                participant.relayInstanceId != runtime.config.instanceId ||
                participant.status != io.github.stream29.mcp.device.protocol.FileTransferStatus.RUNNING
            ) {
                return@post call.respond(HttpStatusCode.Forbidden)
            }
            val request = call.receive<FileTransferContentRequest>()
            val key = RelayFileKey(transferId, request.relativePath, request.attempt)
            if (request.attempt !in 0..1 || !key.validRelativePath()) {
                return@post call.respond(HttpStatusCode.BadRequest)
            }
            val integrity = request.integrity
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "integrity is required"))
            if (runtime.relay.recordSourceIntegrity(key, integrity, RELAY_WAIT_TIMEOUT_MILLIS)) {
                call.respond(HttpStatusCode.Accepted)
            } else {
                call.respond(HttpStatusCode.Conflict)
            }
        }

        post("/content/complete") {
            if (!call.requireRelayInstance(runtime)) return@post
            val deviceId = call.authenticatedDevice(runtime) ?: return@post
            val transferId = call.transferId() ?: return@post
            val participant = runtime.fileTransfers.requireParticipant(transferId, deviceId)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            if (
                participant.destinationDeviceId != deviceId ||
                participant.relayInstanceId != runtime.config.instanceId ||
                participant.status != io.github.stream29.mcp.device.protocol.FileTransferStatus.RUNNING
            ) {
                return@post call.respond(HttpStatusCode.Forbidden)
            }
            val request = call.receive<FileTransferContentRequest>()
            val key = RelayFileKey(transferId, request.relativePath, request.attempt)
            if (request.attempt !in 0..1 || !key.validRelativePath()) {
                return@post call.respond(HttpStatusCode.BadRequest)
            }
            val integrity = request.integrity
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "integrity is required"))
            if (!integrity.sha256.matches(Regex("[0-9a-fA-F]{64}")) || integrity.byteCount < 0) {
                return@post call.respond(HttpStatusCode.BadRequest)
            }
            when (
                val completion = runtime.relay.complete(
                    key,
                    integrity,
                    RELAY_INTEGRITY_WAIT_TIMEOUT_MILLIS,
                ) {
                    runtime.fileTransfers.markFileSuccess(transferId, request.relativePath)
                }
            ) {
                RelayCompletion.Verified -> call.respond(HttpStatusCode.Accepted)
                is RelayCompletion.Rejected -> {
                    call.application.environment.log.warn(
                        "Relay integrity rejected: transfer={}, path={}, attempt={}, reason={}",
                        transferId.value,
                        key.relativePath,
                        key.attempt,
                        completion.message,
                    )
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to completion.message))
                }
                null -> call.respond(HttpStatusCode.NotFound)
            }
        }

        post("/finish") {
            if (!call.requireRelayInstance(runtime)) return@post
            val deviceId = call.authenticatedDevice(runtime) ?: return@post
            val transferId = call.transferId() ?: return@post
            val request = call.receive<FileTransferFinishRequest>()
            if (runtime.fileTransfers.finish(deviceId, transferId, request.successfulFiles)) {
                call.respond(HttpStatusCode.Accepted)
            } else {
                call.respond(HttpStatusCode.Conflict)
            }
        }
        post("/failure") {
            if (!call.requireRelayInstance(runtime)) return@post
            val deviceId = call.authenticatedDevice(runtime) ?: return@post
            val transferId = call.transferId() ?: return@post
            val request = call.receive<FileTransferFailureRequest>()
            if (runtime.fileTransfers.fail(deviceId, transferId, request.errorCode, request.message)) {
                runtime.relay.failTransfer(transferId, request.message)
                call.respond(HttpStatusCode.Accepted)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}

private suspend fun ApplicationCall.respondSession(
    runtime: ServerRuntime,
    user: io.github.stream29.mcp.device.protocol.AuthenticatedUser,
) {
    val session = runtime.accounts.issueSession(user)
    setSessionCookie(runtime, session)
    respond(AuthSession(session, user))
}

private fun ApplicationCall.setSessionCookie(runtime: ServerRuntime, token: String) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE,
            value = token,
            encoding = CookieEncoding.URI_ENCODING,
            httpOnly = true,
            secure = URI(runtime.config.publicBaseUrl).scheme.equals("https", ignoreCase = true),
            path = "/",
            maxAge = SESSION_COOKIE_MAX_AGE_SECONDS.toInt(),
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

private fun ApplicationCall.setGithubStateCookie(runtime: ServerRuntime, state: String) {
    response.cookies.append(
        Cookie(
            name = GITHUB_STATE_COOKIE,
            value = state,
            encoding = CookieEncoding.URI_ENCODING,
            httpOnly = true,
            secure = URI(runtime.config.publicBaseUrl).scheme.equals("https", ignoreCase = true),
            path = "/api/auth/github/callback",
            maxAge = GITHUB_STATE_COOKIE_MAX_AGE_SECONDS,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

private fun ApplicationCall.clearGithubStateCookie(runtime: ServerRuntime) {
    response.cookies.append(
        Cookie(
            name = GITHUB_STATE_COOKIE,
            value = "",
            httpOnly = true,
            secure = URI(runtime.config.publicBaseUrl).scheme.equals("https", ignoreCase = true),
            path = "/api/auth/github/callback",
            maxAge = 0,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

private fun ApplicationCall.isSameOriginMutation(expectedBaseUrl: String): Boolean {
    val source = request.headers[HttpHeaders.Origin]
        ?: request.headers[HttpHeaders.Referrer]
        ?: return false
    return runCatching {
        val actual = URI(source)
        val expected = URI(expectedBaseUrl)
        actual.scheme.equals(expected.scheme, ignoreCase = true) &&
            actual.host.equals(expected.host, ignoreCase = true) &&
            effectivePort(actual) == effectivePort(expected)
    }.getOrDefault(false)
}

private fun effectivePort(uri: URI): Int = when {
    uri.port >= 0 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    else -> 80
}

private suspend fun ApplicationCall.requireManagementUser(
    accounts: AccountStore,
): io.github.stream29.mcp.device.protocol.AuthenticatedUser? {
    val user = managementToken()?.let { accounts.userByToken(it) }
    if (user == null) respond(HttpStatusCode.Unauthorized, mapOf("error" to "authentication required"))
    return user
}

private fun ApplicationCall.managementToken(): String? {
    val authorization = request.headers[HttpHeaders.Authorization]
    val bearer = authorization
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')
        ?.takeIf(String::isNotBlank)
    return bearer ?: request.cookies[SESSION_COOKIE]
}

private suspend fun ApplicationCall.authenticatedDevice(runtime: ServerRuntime): DeviceId? {
    val value = parameters["deviceId"] ?: request.headers[DEVICE_ID_HEADER]
    val deviceId = value?.let(::DeviceId)
    val secret = request.headers[DEVICE_SECRET_HEADER]
    if (deviceId == null || secret == null || !runtime.accounts.authenticateDevice(deviceId, secret)) {
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    return deviceId
}

private suspend fun ApplicationCall.transferId(): TransferId? {
    val raw = parameters["transferId"]
    if (raw == null) {
        respond(HttpStatusCode.BadRequest)
        return null
    }
    return TransferId(raw)
}

private suspend fun ApplicationCall.relayFileKey(transferId: TransferId): RelayFileKey? {
    val relativePath = request.queryParameters["path"]
    val attempt = request.queryParameters["attempt"]?.toIntOrNull()
    if (relativePath == null || attempt == null || attempt !in 0..1) {
        respond(HttpStatusCode.BadRequest)
        return null
    }
    return runCatching { RelayFileKey(transferId, relativePath, attempt) }.getOrElse {
        respond(HttpStatusCode.BadRequest)
        null
    }
}

private fun RelayFileKey.validRelativePath(): Boolean =
    relativePath == io.github.stream29.mcp.device.protocol.ROOT_FILE_RELATIVE_PATH ||
        io.github.stream29.mcp.device.protocol.isSafeRelativePath(relativePath)

private suspend fun ApplicationCall.receiveLimitedText(maxBytes: Int): String? {
    val channel = receiveChannel()
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val read = channel.readAvailable(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) {
            respond(HttpStatusCode.PayloadTooLarge)
            return null
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray().decodeToString()
}

private suspend fun ApplicationCall.requireRelayInstance(runtime: ServerRuntime): Boolean {
    if (request.headers[RELAY_INSTANCE_HEADER] == runtime.config.instanceId.value) return true
    respond(
        MISDIRECTED_REQUEST,
        mapOf("error" to "relay request reached the wrong server instance"),
    )
    return false
}

private fun encodeFragmentComponent(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

private fun escapeHtml(value: String): String = buildString(value.length) {
    value.forEach {
        append(
            when (it) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> it
            },
        )
    }
}

private const val DEVICE_ID_HEADER = "X-Device-Id"
private const val DEVICE_SECRET_HEADER = "X-Device-Secret"
private const val RELAY_INSTANCE_HEADER = "X-Relay-Instance-Id"
private const val SESSION_COOKIE = "device_as_mcp_session"
private const val SESSION_COOKIE_MAX_AGE_SECONDS = 30L * 24 * 60 * 60
private const val GITHUB_STATE_COOKIE = "device_as_mcp_github_state"
private const val GITHUB_STATE_COOKIE_MAX_AGE_SECONDS = 10 * 60
private const val RELAY_WAIT_TIMEOUT_MILLIS = 30_000L
private const val RELAY_MANIFEST_WAIT_TIMEOUT_MILLIS = 30_000L
private const val RELAY_MANIFEST_POLL_MILLIS = 50L
private const val RELAY_PLAN_WAIT_TIMEOUT_MILLIS = 30_000L
private const val RELAY_PLAN_POLL_MILLIS = 50L
private const val RELAY_INTEGRITY_WAIT_TIMEOUT_MILLIS = 30_000L
private const val RELAY_ATTEMPT_TIMEOUT_MILLIS = 30L * 60 * 1_000
private val MISDIRECTED_REQUEST = HttpStatusCode(421, "Misdirected Request")
