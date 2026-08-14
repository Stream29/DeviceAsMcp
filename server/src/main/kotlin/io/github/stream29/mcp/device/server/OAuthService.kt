package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.ProtocolJson
import io.github.stream29.mcp.device.protocol.UserId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.utils.io.readAvailable

internal class OAuthService(
    private val config: ServerConfig,
    private val accounts: AccountStore,
    private val routing: RoutingStore,
) : AutoCloseable {
    private val client = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = OUTBOUND_CONNECT_TIMEOUT_MILLIS
            requestTimeoutMillis = OUTBOUND_REQUEST_TIMEOUT_MILLIS
            socketTimeoutMillis = OUTBOUND_REQUEST_TIMEOUT_MILLIS
        }
        install(ContentNegotiation) { json(ProtocolJson) }
    }
    private val random = SecureRandom()
    val resourceUri: String = "${config.publicBaseUrl.trimEnd('/')}/mcp"
    private val preRegisteredClients: Map<String, OAuthClientRecord> =
        config.oauthPreRegisteredClientsJson
            ?.takeIf(String::isNotBlank)
            ?.let { encoded ->
                ProtocolJson.decodeFromString<List<OAuthPreRegisteredClient>>(encoded)
                    .also { clients ->
                        require(clients.map { it.clientId }.distinct().size == clients.size) {
                            "Pre-registered OAuth client IDs must be unique"
                        }
                    }
                    .associate { client ->
                        require(client.clientId.isNotBlank()) { "Pre-registered OAuth client_id cannot be blank" }
                        require(client.redirectUris.isNotEmpty()) {
                            "Pre-registered OAuth clients require at least one redirect URI"
                        }
                        require(client.redirectUris.size <= MAX_REDIRECT_URIS) {
                            "Pre-registered OAuth clients have too many redirect URIs"
                        }
                        validateClientName(client.clientName)
                        client.redirectUris.forEach(::validateRedirectUri)
                        client.clientId to OAuthClientRecord(
                            client.clientId,
                            client.redirectUris.distinct(),
                            client.clientName,
                        )
                    }
            }
            .orEmpty()

    suspend fun startGithubLogin(): GithubAuthorizationStart {
        val clientId = requireNotNull(config.githubClientId) { "GitHub OAuth is not configured" }
        requireNotNull(config.githubClientSecret) { "GitHub OAuth is not configured" }
        val state = randomToken()
        routing.putEphemeral(githubStateKey(state), "pending", GITHUB_STATE_TTL_MILLIS)
        return GithubAuthorizationStart(
            authorizationUrl = URLBuilder("https://github.com/login/oauth/authorize").apply {
                parameters.append("client_id", clientId)
                parameters.append("redirect_uri", "${config.publicBaseUrl.trimEnd('/')}/api/auth/github/callback")
                parameters.append("state", state)
                parameters.append("scope", "read:user")
            }.buildString(),
            state = state,
        )
    }

    suspend fun finishGithub(code: String, state: String, browserState: String?): String {
        require(
            browserState != null &&
                MessageDigest.isEqual(
                    state.toByteArray(Charsets.UTF_8),
                    browserState.toByteArray(Charsets.UTF_8),
                )
        ) {
            "OAuth state does not match the initiating browser"
        }
        check(routing.consumeEphemeral(githubStateKey(state)) != null) { "Invalid or expired OAuth state" }
        val clientId = requireNotNull(config.githubClientId) { "GitHub OAuth is not configured" }
        val clientSecret = requireNotNull(config.githubClientSecret) { "GitHub OAuth is not configured" }
        val tokenResponse = client.post("https://github.com/login/oauth/access_token") {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                    "code" to code,
                    "redirect_uri" to "${config.publicBaseUrl.trimEnd('/')}/api/auth/github/callback",
                ),
            )
        }.body<JsonObject>()
        val accessToken = tokenResponse["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error(tokenResponse["error_description"]?.jsonPrimitive?.contentOrNull ?: "GitHub token exchange failed")
        val profile = client.get("https://api.github.com/user") {
            bearerAuth(accessToken)
            accept(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, "DeviceAsMcp")
        }.body<JsonObject>()
        val login = profile["login"]?.jsonPrimitive?.contentOrNull ?: error("GitHub profile has no login")
        val githubId = profile["id"]?.jsonPrimitive?.contentOrNull ?: error("GitHub profile has no stable ID")
        val user = accounts.findOrCreateGithubUser(githubId, login)
        return accounts.issueSession(user)
    }

    suspend fun startDaemonBrowserLogin(): DaemonBrowserLoginStart {
        val requestId = randomToken()
        routing.putEphemeral(
            daemonLoginStateKey(requestId),
            DAEMON_LOGIN_PENDING,
            DAEMON_LOGIN_TTL_MILLIS,
        )
        return DaemonBrowserLoginStart(
            requestId = requestId,
            verificationUri = "${config.publicBaseUrl.trimEnd('/')}/api/auth/daemon/complete?request=$requestId",
            expiresInSeconds = DAEMON_LOGIN_TTL_MILLIS / 1_000,
            intervalSeconds = DAEMON_LOGIN_POLL_SECONDS,
        )
    }

    suspend fun approveDaemonBrowserLogin(requestId: String, userId: UserId) {
        check(
            routing.compareAndSetEphemeral(
            daemonLoginStateKey(requestId),
                DAEMON_LOGIN_PENDING,
            ProtocolJson.encodeToString(DaemonBrowserLoginApproval(userId)),
            DAEMON_LOGIN_TTL_MILLIS,
            ),
        ) { "Unknown, expired, or already completed daemon login request" }
    }

    suspend fun denyDaemonBrowserLogin(requestId: String) {
        check(
            routing.compareAndSetEphemeral(
                daemonLoginStateKey(requestId),
                DAEMON_LOGIN_PENDING,
                DAEMON_LOGIN_DENIED,
                DAEMON_LOGIN_TTL_MILLIS,
            ),
        ) { "Unknown, expired, or already completed daemon login request" }
    }

    suspend fun daemonBrowserLoginExists(requestId: String): Boolean =
        routing.ephemeral(daemonLoginStateKey(requestId)) != null

    suspend fun pollDaemonBrowserLogin(requestId: String): DaemonBrowserLoginResult? {
        val state = routing.ephemeral(daemonLoginStateKey(requestId)) ?: error("Unknown or expired daemon login request")
        if (state == DAEMON_LOGIN_PENDING) return null
        if (state == DAEMON_LOGIN_DENIED) error("Daemon login was denied")
        val consumed = routing.consumeEphemeral(daemonLoginStateKey(requestId))
            ?: error("Daemon login was already consumed")
        val approval = ProtocolJson.decodeFromString<DaemonBrowserLoginApproval>(consumed)
        return DaemonBrowserLoginResult(accounts.createEnrollmentToken(approval.userId))
    }

    suspend fun registerClient(request: OAuthClientRegistrationRequest): OAuthClientRegistrationResponse {
        require(request.redirectUris.size in 1..MAX_REDIRECT_URIS) {
            "redirect_uris must contain 1 to $MAX_REDIRECT_URIS values"
        }
        require(request.redirectUris.distinct().size == request.redirectUris.size) {
            "redirect_uris must not contain duplicates"
        }
        validateClientName(request.clientName)
        request.redirectUris.forEach(::validateRedirectUri)
        require(request.tokenEndpointAuthMethod == null || request.tokenEndpointAuthMethod == "none") {
            "Only public clients are supported"
        }
        require(request.grantTypes.isEmpty() || request.grantTypes == listOf("authorization_code")) {
            "Only authorization_code is supported"
        }
        require(request.responseTypes.isEmpty() || request.responseTypes == listOf("code")) {
            "Only code responses are supported"
        }
        val clientId = "dcr_${randomToken()}"
        val record = OAuthClientRecord(clientId, request.redirectUris, request.clientName)
        routing.putEphemeral(
            clientKey(clientId),
            ProtocolJson.encodeToString(record),
            CLIENT_REGISTRATION_TTL_MILLIS,
        )
        return OAuthClientRegistrationResponse(
            clientId = clientId,
            redirectUris = record.redirectUris,
            clientName = record.clientName,
        )
    }

    suspend fun authorize(
        userId: UserId,
        responseType: String?,
        clientId: String?,
        redirectUri: String?,
        codeChallenge: String?,
        codeChallengeMethod: String?,
        resource: String?,
        state: String?,
        scope: String? = null,
    ): String {
        require(responseType == "code") { "response_type must be code" }
        require(!clientId.isNullOrBlank()) { "client_id is required" }
        require(!redirectUri.isNullOrBlank()) { "redirect_uri is required" }
        require(codeChallenge?.matches(PKCE_CHALLENGE_PATTERN) == true) {
            "code_challenge must be an S256 base64url value"
        }
        require(codeChallengeMethod == "S256") { "code_challenge_method must be S256" }
        require(resource == resourceUri) { "resource must identify $resourceUri" }
        val requestedScopes = parseScope(scope)
        val record = resolveClient(clientId)
        require(redirectUri in record.redirectUris) { "redirect_uri is not registered" }
        val code = randomToken()
        val grant = OAuthAuthorizationGrant(
            userId = userId,
            clientId = clientId,
            redirectUri = redirectUri,
            codeChallenge = codeChallenge,
            resource = resource,
            scopes = requestedScopes,
        )
        routing.putEphemeral(codeKey(code), ProtocolJson.encodeToString(grant), AUTHORIZATION_CODE_TTL_MILLIS)
        return URLBuilder(redirectUri).apply {
            parameters.append("code", code)
            state?.let { parameters.append("state", it) }
        }.buildString()
    }

    suspend fun exchangeCode(parameters: Parameters): OAuthTokenResponse {
        if (parameters["grant_type"] != "authorization_code") {
            throw OAuthRequestException("unsupported_grant_type", "grant_type must be authorization_code")
        }
        val code = parameters["code"]?.takeIf(String::isNotBlank)
            ?: throw OAuthRequestException("invalid_request", "code is required")
        val verifier = parameters["code_verifier"]?.takeIf(String::isNotBlank)
            ?: throw OAuthRequestException("invalid_request", "code_verifier is required")
        if (!PKCE_VERIFIER_PATTERN.matches(verifier)) {
            throw OAuthRequestException("invalid_request", "code_verifier is invalid")
        }
        val encoded = routing.consumeEphemeral(codeKey(code))
            ?: throw OAuthRequestException("invalid_grant", "Invalid or expired authorization code")
        val grant = ProtocolJson.decodeFromString<OAuthAuthorizationGrant>(encoded)
        if (parameters["client_id"] != grant.clientId) {
            throw OAuthRequestException("invalid_grant", "client_id does not match authorization code")
        }
        if (parameters["redirect_uri"] != grant.redirectUri) {
            throw OAuthRequestException("invalid_grant", "redirect_uri does not match authorization code")
        }
        if (parameters["resource"] != resourceUri || grant.resource != resourceUri) {
            throw OAuthRequestException("invalid_target", "resource does not match authorization code")
        }
        parameters["scope"]?.let {
            val tokenScopes = runCatching { parseScope(it) }.getOrElse {
                throw OAuthRequestException("invalid_scope", it.message ?: "scope is invalid")
            }
            if (tokenScopes != grant.scopes) {
                throw OAuthRequestException("invalid_scope", "scope exceeds the authorization grant")
            }
        }
        if (pkceChallenge(verifier) != grant.codeChallenge) {
            throw OAuthRequestException("invalid_grant", "PKCE verification failed")
        }
        val accessToken = accounts.issueMcpAccessToken(
            grant.userId,
            resourceUri,
            grant.scopes,
            System.currentTimeMillis() + ACCESS_TOKEN_TTL_MILLIS,
        )
        return OAuthTokenResponse(
            accessToken = accessToken,
            expiresIn = ACCESS_TOKEN_TTL_MILLIS / 1_000,
            scope = grant.scopes.sorted().joinToString(" "),
        )
    }

    fun protectedResourceMetadata() = OAuthProtectedResourceMetadata(
        resource = resourceUri,
        authorizationServers = listOf(config.publicBaseUrl.trimEnd('/')),
        scopesSupported = listOf(MCP_SCOPE),
        bearerMethodsSupported = listOf("header"),
    )

    fun authorizationServerMetadata() = OAuthAuthorizationServerMetadata(
        issuer = config.publicBaseUrl.trimEnd('/'),
        authorizationEndpoint = "${config.publicBaseUrl.trimEnd('/')}/oauth/authorize",
        tokenEndpoint = "${config.publicBaseUrl.trimEnd('/')}/oauth/token",
        registrationEndpoint = "${config.publicBaseUrl.trimEnd('/')}/oauth/register",
        responseTypesSupported = listOf("code"),
        grantTypesSupported = listOf("authorization_code"),
        codeChallengeMethodsSupported = listOf("S256"),
        tokenEndpointAuthMethodsSupported = listOf("none"),
        scopesSupported = listOf(MCP_SCOPE),
    )

    override fun close() {
        client.close()
    }

    private suspend fun resolveClient(clientId: String): OAuthClientRecord {
        preRegisteredClients[clientId]?.let { return it }
        routing.ephemeral(clientKey(clientId))?.let {
            return ProtocolJson.decodeFromString(it)
        }
        if (clientId.startsWith("https://")) {
            val uri = URI(clientId)
            require(
                uri.isAbsolute &&
                    uri.scheme.equals("https", ignoreCase = true) &&
                    !uri.host.isNullOrBlank() &&
                    uri.userInfo == null &&
                    uri.fragment == null
            ) {
                "Invalid Client ID Metadata Document URL"
            }
            require(!isPrivateHost(uri.host)) { "Client metadata must not use a private network host" }
            requirePublicAddresses(uri.host)
            val response = client.get(clientId) { accept(ContentType.Application.Json) }
            require(response.status.isSuccess()) { "Client metadata request failed with HTTP ${response.status.value}" }
            require(response.contentType()?.match(ContentType.Application.Json) == true) {
                "Client metadata response must use application/json"
            }
            require(response.contentLength()?.let { it <= MAX_CLIENT_METADATA_BYTES } != false) {
                "Client metadata document is too large"
            }
            val metadata = readClientMetadata(response)
            require(metadata.clientId == clientId) { "Client metadata client_id mismatch" }
            require(metadata.redirectUris.size in 1..MAX_REDIRECT_URIS) {
                "Client metadata must contain 1 to $MAX_REDIRECT_URIS redirect_uris"
            }
            require(metadata.redirectUris.distinct().size == metadata.redirectUris.size) {
                "Client metadata redirect_uris must not contain duplicates"
            }
            validateClientName(metadata.clientName)
            metadata.redirectUris.forEach(::validateRedirectUri)
            return OAuthClientRecord(clientId, metadata.redirectUris, metadata.clientName)
        }
        throw IllegalArgumentException("Unknown client_id")
    }

    private fun validateRedirectUri(value: String) {
        require(value.length <= MAX_URI_LENGTH) { "redirect_uri is too long" }
        val uri = URI(value)
        val loopback = uri.host == "127.0.0.1" || uri.host == "::1" || uri.host.equals("localhost", true)
        require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) {
            "redirect_uri must use HTTPS or a loopback HTTP address"
        }
        require(
            uri.isAbsolute &&
                !uri.host.isNullOrBlank() &&
                uri.fragment == null &&
                uri.userInfo == null
        ) {
            "Invalid redirect_uri"
        }
    }

    private fun validateClientName(value: String?) {
        require(value == null || value.trim().length in 1..MAX_CLIENT_NAME_LENGTH) {
            "client_name must contain 1 to $MAX_CLIENT_NAME_LENGTH characters"
        }
    }

    private fun isPrivateHost(host: String?): Boolean {
        if (host == null) return true
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized.endsWith(".local")) return true
        if (':' in normalized) {
            return normalized == "::1" ||
                normalized.startsWith("fc") ||
                normalized.startsWith("fd") ||
                normalized.startsWith("fe80:")
        }
        val parts = normalized.split('.').mapNotNull(String::toIntOrNull)
        if (parts.size != 4) return false
        return parts[0] == 10 ||
            parts[0] == 127 ||
            parts[0] == 0 ||
            (parts[0] == 169 && parts[1] == 254) ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168)
    }

    private suspend fun requirePublicAddresses(host: String?) {
        require(!host.isNullOrBlank()) { "Client metadata host is required" }
        val addresses = withContext(Dispatchers.IO) { InetAddress.getAllByName(host).toList() }
        require(addresses.isNotEmpty() && addresses.all { address ->
            !address.isAnyLocalAddress &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress &&
                !address.isMulticastAddress &&
                !address.address.isUniqueLocalIpv6()
        }) {
            "Client metadata must not resolve to a private network address"
        }
    }

    private fun ByteArray.isUniqueLocalIpv6(): Boolean =
        size == 16 && ((first().toInt() and 0xfe) == 0xfc)

    private suspend fun readClientMetadata(
        response: io.ktor.client.statement.HttpResponse,
    ): OAuthClientMetadataDocument {
        val channel = response.bodyAsChannel()
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = channel.readAvailable(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= MAX_CLIENT_METADATA_BYTES) { "Client metadata document is too large" }
            output.write(buffer, 0, read)
        }
        return ProtocolJson.decodeFromString(output.toByteArray().decodeToString())
    }

    private fun parseScope(value: String?): Set<String> {
        val scopes = value
            ?.split(' ')
            ?.filter(String::isNotBlank)
            ?.toSet()
            .orEmpty()
            .ifEmpty { setOf(MCP_SCOPE) }
        require(scopes == setOf(MCP_SCOPE)) { "scope must be $MCP_SCOPE" }
        return scopes
    }

    private fun pkceChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(
            verifier.toByteArray(Charsets.US_ASCII),
        ),
    )

    private fun randomToken(): String = ByteArray(32)
        .also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun githubStateKey(state: String) = "github-state:$state"
    private fun daemonLoginStateKey(requestId: String) = "daemon-login:$requestId"
    private fun clientKey(clientId: String) = "oauth-client:$clientId"
    private fun codeKey(code: String) = "oauth-code:$code"

    companion object {
        const val MCP_SCOPE = "device:full"
        private const val GITHUB_STATE_TTL_MILLIS = 10 * 60 * 1_000L
        private const val AUTHORIZATION_CODE_TTL_MILLIS = 5 * 60 * 1_000L
        private const val ACCESS_TOKEN_TTL_MILLIS = 60 * 60 * 1_000L
        private const val CLIENT_REGISTRATION_TTL_MILLIS = 30L * 24 * 60 * 60 * 1_000
        private const val DAEMON_LOGIN_TTL_MILLIS = 10 * 60 * 1_000L
        private const val DAEMON_LOGIN_POLL_SECONDS = 2L
        private const val MAX_CLIENT_METADATA_BYTES = 64 * 1024L
        private const val MAX_REDIRECT_URIS = 20
        private const val MAX_URI_LENGTH = 4_096
        private const val MAX_CLIENT_NAME_LENGTH = 200
        private const val OUTBOUND_CONNECT_TIMEOUT_MILLIS = 5_000L
        private const val OUTBOUND_REQUEST_TIMEOUT_MILLIS = 10_000L
        private const val DAEMON_LOGIN_PENDING = "pending"
        private const val DAEMON_LOGIN_DENIED = "denied"
        private val PKCE_CHALLENGE_PATTERN = Regex("[A-Za-z0-9_-]{43}")
        private val PKCE_VERIFIER_PATTERN = Regex("[A-Za-z0-9._~-]{43,128}")
    }
}

internal data class GithubAuthorizationStart(
    val authorizationUrl: String,
    val state: String,
)

internal class OAuthRequestException(
    val error: String,
    message: String,
) : IllegalArgumentException(message)

@Serializable
internal data class DaemonBrowserLoginStart(
    val requestId: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

@Serializable
internal data class DaemonBrowserLoginResult(
    val enrollment: io.github.stream29.mcp.device.protocol.DeviceEnrollmentToken,
)

@Serializable
private data class DaemonBrowserLoginApproval(val userId: UserId)

@Serializable
internal data class OAuthClientRegistrationRequest(
    @SerialName("redirect_uris")
    val redirectUris: List<String>,
    @SerialName("client_name")
    val clientName: String? = null,
    @SerialName("token_endpoint_auth_method")
    val tokenEndpointAuthMethod: String? = null,
    @SerialName("grant_types")
    val grantTypes: List<String> = emptyList(),
    @SerialName("response_types")
    val responseTypes: List<String> = emptyList(),
)

@Serializable
internal data class OAuthClientRegistrationResponse(
    @SerialName("client_id")
    val clientId: String,
    @SerialName("redirect_uris")
    val redirectUris: List<String>,
    @SerialName("client_name")
    val clientName: String? = null,
    @SerialName("token_endpoint_auth_method")
    val tokenEndpointAuthMethod: String = "none",
    @SerialName("grant_types")
    val grantTypes: List<String> = listOf("authorization_code"),
    @SerialName("response_types")
    val responseTypes: List<String> = listOf("code"),
)

@Serializable
private data class OAuthClientRecord(
    val clientId: String,
    val redirectUris: List<String>,
    val clientName: String?,
)

@Serializable
internal data class OAuthPreRegisteredClient(
    @SerialName("client_id")
    val clientId: String,
    @SerialName("redirect_uris")
    val redirectUris: List<String>,
    @SerialName("client_name")
    val clientName: String? = null,
)

@Serializable
private data class OAuthAuthorizationGrant(
    val userId: UserId,
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String,
    val resource: String,
    val scopes: Set<String>,
)

@Serializable
private data class OAuthClientMetadataDocument(
    @SerialName("client_id")
    val clientId: String,
    @SerialName("redirect_uris")
    val redirectUris: List<String>,
    @SerialName("client_name")
    val clientName: String? = null,
)

@Serializable
internal data class OAuthTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("token_type")
    val tokenType: String = "Bearer",
    @SerialName("expires_in")
    val expiresIn: Long,
    val scope: String,
)

@Serializable
internal data class OAuthErrorResponse(
    val error: String,
    @SerialName("error_description")
    val errorDescription: String,
)

@Serializable
internal data class OAuthProtectedResourceMetadata(
    val resource: String,
    @SerialName("authorization_servers")
    val authorizationServers: List<String>,
    @SerialName("scopes_supported")
    val scopesSupported: List<String>,
    @SerialName("bearer_methods_supported")
    val bearerMethodsSupported: List<String>,
)

@Serializable
internal data class OAuthAuthorizationServerMetadata(
    val issuer: String,
    @SerialName("authorization_endpoint")
    val authorizationEndpoint: String,
    @SerialName("token_endpoint")
    val tokenEndpoint: String,
    @SerialName("registration_endpoint")
    val registrationEndpoint: String,
    @SerialName("response_types_supported")
    val responseTypesSupported: List<String>,
    @SerialName("grant_types_supported")
    val grantTypesSupported: List<String>,
    @SerialName("code_challenge_methods_supported")
    val codeChallengeMethodsSupported: List<String>,
    @SerialName("token_endpoint_auth_methods_supported")
    val tokenEndpointAuthMethodsSupported: List<String>,
    @SerialName("scopes_supported")
    val scopesSupported: List<String>,
)
