package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.AuthenticatedUser
import io.github.stream29.mcp.device.protocol.DeviceId
import io.github.stream29.mcp.device.protocol.DeviceSummary
import io.github.stream29.mcp.device.protocol.LaunchFileTransferRequest
import io.github.stream29.mcp.device.protocol.OperationPayload
import io.github.stream29.mcp.device.protocol.OperationResult
import io.github.stream29.mcp.device.protocol.OperationResultPayload
import io.github.stream29.mcp.device.protocol.ProtocolJson
import io.github.stream29.mcp.device.protocol.RemoteMcpTools
import io.github.stream29.mcp.device.protocol.TerminalLaunchStatus
import io.github.stream29.mcp.device.protocol.TerminalSessionId
import io.github.stream29.mcp.device.protocol.TransferId
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseAndSortContentTypeHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.accept
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import io.ktor.utils.io.readAvailable
import java.net.URI
import java.util.Base64

internal const val MODERN_MCP_VERSION = "2026-07-28"
internal const val LEGACY_MCP_VERSION = "2025-06-18"

internal class ModernMcpEndpoint(private val runtime: ServerRuntime) {
    suspend fun post(call: ApplicationCall) {
        if (call.request.queryParameters.names().any { it.equals("access_token", ignoreCase = true) }) {
            call.respond(HttpStatusCode.BadRequest, error(null, -32600, "Access tokens are not accepted in the URI"))
            return
        }
        val principal = call.resolveMcpPrincipal(runtime.accounts)
        if (principal == null || principal.audience != runtime.oauth.resourceUri) {
            call.response.header(
                HttpHeaders.WWWAuthenticate,
                """Bearer resource_metadata="${runtime.config.publicBaseUrl.trimEnd('/')}/.well-known/oauth-protected-resource/mcp"""",
            )
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_token"))
            return
        }
        if (OAuthService.MCP_SCOPE !in principal.scopes) {
            call.response.header(
                HttpHeaders.WWWAuthenticate,
                """Bearer error="insufficient_scope", scope="${OAuthService.MCP_SCOPE}"""",
            )
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "insufficient_scope"))
            return
        }
        val user = principal.user
        if (!validOrigin(call)) {
            call.respond(HttpStatusCode.Forbidden, error(null, -32600, "Origin is not allowed"))
            return
        }
        if (!call.request.contentType().match(ContentType.Application.Json)) {
            call.respond(HttpStatusCode.UnsupportedMediaType)
            return
        }
        val acceptedTypes = runCatching {
            parseAndSortContentTypeHeader(call.request.accept())
                .filter { it.quality > 0.0 }
                .map { ContentType.parse(it.value) }
        }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, error(null, -32600, "Accept header is invalid"))
            return
        }
        if (
            acceptedTypes.none { ContentType.Application.Json.match(it) } ||
            acceptedTypes.none { ContentType.Text.EventStream.match(it) }
        ) {
            call.respond(
                HttpStatusCode.NotAcceptable,
                error(null, -32600, "Accept must include application/json and text/event-stream"),
            )
            return
        }
        val requestText = call.receiveMcpText()
        if (requestText == null) {
            call.respond(HttpStatusCode.PayloadTooLarge, error(null, -32600, "Request body is too large"))
            return
        }
        val request = try {
            ProtocolJson.parseToJsonElement(requestText).jsonObject
        } catch (_: Throwable) {
            call.respond(HttpStatusCode.BadRequest, error(null, -32700, "Parse error"))
            return
        }
        val id = request["id"]
        val jsonRpc = request.stringValue("jsonrpc")
        val method = request.stringValue("method")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        if (
            jsonRpc != "2.0" ||
            method == null ||
            request["params"]?.let { it !is JsonObject } == true ||
            id?.let { it !is JsonNull && !it.validRequestId() } == true ||
            request.keys.any { it !in JSON_RPC_REQUEST_KEYS }
        ) {
            call.respond(HttpStatusCode.BadRequest, error(id, -32600, "Invalid JSON-RPC request"))
            return
        }
        val legacyRequest = call.isLegacyRequest(params)
        if (!legacyRequest) {
            val metadataError = validateMetadata(call, request, method, params, id)
            if (metadataError != null) {
                call.respond(HttpStatusCode.BadRequest, metadataError)
                return
            }
        }
        if (id == null || id is JsonNull) {
            call.respond(HttpStatusCode.Accepted)
            return
        }

        when (method) {
            "initialize" -> {
                if (!legacyRequest) {
                    call.respondJson(HttpStatusCode.NotFound, error(id, -32601, "Method not found"))
                    return
                }
                val initializeError = validateLegacyInitialize(params, id)
                if (initializeError != null) {
                    call.respond(HttpStatusCode.BadRequest, initializeError)
                    return
                }
                call.respondJson(HttpStatusCode.OK, success(id, legacyInitializeResult()))
            }
            "server/discover" -> {
                if (legacyRequest) {
                    call.respondJson(HttpStatusCode.NotFound, error(id, -32601, "Method not found"))
                } else {
                    call.respondJson(HttpStatusCode.OK, success(id, discoverResult()))
                }
            }
            "tools/list" -> call.respondJson(HttpStatusCode.OK, success(id, toolsResult()))
            "tools/call" -> {
                val name = params.stringValue("name")
                if (name == null || name !in RemoteMcpTools.names) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        error(id, -32602, "Unknown tool: ${name ?: "<missing>"}"),
                    )
                    return
                }
                val rawArguments = params["arguments"]
                if (rawArguments != null && rawArguments !is JsonObject) {
                    call.respond(HttpStatusCode.BadRequest, error(id, -32602, "Tool arguments must be an object"))
                    return
                }
                val arguments = rawArguments ?: JsonObject(emptyMap())
                try {
                    arguments.validateToolArguments(name)
                } catch (failure: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        error(id, -32602, failure.message ?: "Invalid tool arguments"),
                    )
                    return
                }
                call.respondJson(HttpStatusCode.OK, success(id, callTool(user, name, arguments)))
            }
            else -> call.respondJson(HttpStatusCode.NotFound, error(id, -32601, "Method not found"))
        }
    }

    suspend fun unsupported(call: ApplicationCall) {
        call.response.header(HttpHeaders.Allow, HttpMethod.Post.value)
        call.respond(HttpStatusCode.MethodNotAllowed)
    }

    private suspend fun callTool(
        user: AuthenticatedUser,
        name: String,
        arguments: JsonObject,
    ): JsonObject = try {
        when (name) {
            RemoteMcpTools.LIST_DEVICE -> {
                val devices = runtime.accounts.devices(user.id).map {
                    it.copy(online = runtime.routing.deviceOwner(it.id) != null)
                }
                toolSuccess(ProtocolJson.encodeToJsonElement(ListSerializer(DeviceSummary.serializer()), devices))
            }
            RemoteMcpTools.LAUNCH_TERMINAL_SESSION -> {
                val deviceId = arguments.requiredString("deviceId").let(::DeviceId)
                requireOwned(user, deviceId)
                val result = try {
                    runtime.operations.invoke(
                        user.id,
                        deviceId,
                        OperationPayload.LaunchTerminalSession(
                            script = arguments.requiredString("script"),
                            tty = arguments.optionalBoolean("tty") ?: false,
                        ),
                    )
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    // JSON responses have no transport cancellation signal once dispatched.
                    throw cancellation
                }
                val payload = result.successPayload<OperationResultPayload.TerminalLaunch>()
                val sessionId = payload.sessionId
                if (payload.status == TerminalLaunchStatus.RUNNING && sessionId != null) {
                    runtime.routing.putTerminalRoute(
                        sessionId,
                        TerminalRoute(user.id, deviceId),
                        null,
                    )
                }
                toolSuccess(ProtocolJson.encodeToJsonElement(OperationResultPayload.TerminalLaunch.serializer(), payload))
            }
            RemoteMcpTools.TERMINAL_SESSION_INPUT -> {
                val sessionId = TerminalSessionId(arguments.requiredString("sessionId"))
                val route = requireTerminalRoute(user, sessionId)
                val result = runtime.operations.invoke(
                    user.id,
                    route.deviceId,
                    OperationPayload.TerminalSessionInput(
                        sessionId = sessionId,
                        stdin = arguments.optionalString("stdin").orEmpty(),
                        eof = arguments.optionalBoolean("eof") ?: false,
                    ),
                )
                if (
                    (result.result as? OperationResult.Failure)?.errorCode ==
                    io.github.stream29.mcp.device.protocol.OperationErrorCode.TERMINAL_NOT_FOUND
                ) {
                    runtime.routing.removeTerminalRoute(sessionId)
                }
                val payload = result.successPayload<OperationResultPayload.TerminalInput>()
                runtime.routing.putTerminalRoute(
                    sessionId,
                    route,
                    if (payload.accepted) null else TERMINAL_ENDED_ROUTE_TTL_MILLIS,
                )
                toolSuccess(ProtocolJson.encodeToJsonElement(OperationResultPayload.TerminalInput.serializer(), payload))
            }
            RemoteMcpTools.TERMINAL_SESSION_OUTPUT -> {
                val sessionId = TerminalSessionId(arguments.requiredString("sessionId"))
                val route = requireTerminalRoute(user, sessionId)
                val result = runtime.operations.invoke(
                    user.id,
                    route.deviceId,
                    OperationPayload.TerminalSessionOutput(sessionId),
                )
                if (
                    (result.result as? OperationResult.Failure)?.errorCode ==
                    io.github.stream29.mcp.device.protocol.OperationErrorCode.TERMINAL_NOT_FOUND
                ) {
                    runtime.routing.removeTerminalRoute(sessionId)
                }
                val payload = result.successPayload<OperationResultPayload.TerminalOutput>()
                runtime.routing.putTerminalRoute(
                    sessionId,
                    route,
                    if (payload.running) null else TERMINAL_ENDED_ROUTE_TTL_MILLIS,
                )
                toolSuccess(ProtocolJson.encodeToJsonElement(OperationResultPayload.TerminalOutput.serializer(), payload))
            }
            RemoteMcpTools.LAUNCH_FILE_TRANSFER -> {
                val request = LaunchFileTransferRequest(
                    sourceDeviceId = DeviceId(arguments.requiredString("sourceDeviceId")),
                    sourcePath = arguments.requiredString("sourcePath"),
                    destinationDeviceId = DeviceId(arguments.requiredString("destinationDeviceId")),
                    destinationPath = arguments.requiredString("destinationPath"),
                )
                val result = runtime.fileTransfers.launch(user.id, request).getOrThrow()
                toolSuccess(
                    ProtocolJson.encodeToJsonElement(
                        io.github.stream29.mcp.device.protocol.LaunchFileTransferResult.serializer(),
                        result,
                    ),
                )
            }
            RemoteMcpTools.FILE_TRANSFER_STATUS -> {
                val result = runtime.fileTransfers.status(
                    user.id,
                    TransferId(arguments.requiredString("transferId")),
                )
                toolSuccess(
                    ProtocolJson.encodeToJsonElement(
                        io.github.stream29.mcp.device.protocol.FileTransferSummary.serializer(),
                        result,
                    ),
                )
            }
            RemoteMcpTools.CANCEL_FILE_TRANSFER -> {
                val result = runtime.fileTransfers.cancel(
                    user.id,
                    TransferId(arguments.requiredString("transferId")),
                ) ?: error("Transfer not found")
                toolSuccess(
                    ProtocolJson.encodeToJsonElement(
                        io.github.stream29.mcp.device.protocol.CancelFileTransferResult.serializer(),
                        result,
                    ),
                )
            }
            else -> error("Unknown tool")
        }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        toolError(failure.message ?: "Tool execution failed")
    }

    private suspend fun requireOwned(user: AuthenticatedUser, deviceId: DeviceId) {
        require(runtime.accounts.deviceUser(deviceId) == user.id) { "Device not found" }
    }

    private suspend fun requireTerminalRoute(
        user: AuthenticatedUser,
        sessionId: TerminalSessionId,
    ): TerminalRoute {
        val route = runtime.routing.terminalRoute(sessionId) ?: error("Terminal session not found")
        require(route.userId == user.id) { "Terminal session not found" }
        return route
    }

    private fun ApplicationCall.isLegacyRequest(params: JsonObject): Boolean {
        val versionHeaders = request.headers.getAll("MCP-Protocol-Version")
        if (
            versionHeaders != null &&
            (versionHeaders.size != 1 || versionHeaders.single() != LEGACY_MCP_VERSION)
        ) {
            return false
        }
        if (
            request.headers.names().any { name ->
                name.equals("Mcp-Method", ignoreCase = true) ||
                    name.equals("Mcp-Name", ignoreCase = true) ||
                    name.startsWith("Mcp-Param-", ignoreCase = true)
            }
        ) {
            return false
        }
        val metadata = params["_meta"] as? JsonObject
        return metadata?.containsKey("io.modelcontextprotocol/protocolVersion") != true
    }

    private fun validateLegacyInitialize(params: JsonObject, id: JsonElement): JsonObject? {
        if (params.stringValue("protocolVersion") != LEGACY_MCP_VERSION) {
            return error(id, -32602, "Unsupported legacy protocol version")
        }
        if (params["capabilities"] !is JsonObject) {
            return error(id, -32602, "Missing client capabilities")
        }
        if (params["clientInfo"] !is JsonObject) {
            return error(id, -32602, "Missing client info")
        }
        return null
    }

    private fun validateMetadata(
        call: ApplicationCall,
        request: JsonObject,
        method: String,
        params: JsonObject,
        id: JsonElement?,
    ): JsonObject? {
        val versionHeader = call.singleHeader("MCP-Protocol-Version")
            ?: return error(id, -32020, "Missing or repeated MCP-Protocol-Version header")
        val methodHeader = call.singleHeader("Mcp-Method")
            ?: return error(id, -32020, "Missing or repeated Mcp-Method header")
        val metadata = params["_meta"] as? JsonObject
        val versionBody = (metadata?.get("io.modelcontextprotocol/protocolVersion") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
        if (versionHeader != MODERN_MCP_VERSION || versionBody != MODERN_MCP_VERSION) {
            if (versionHeader == versionBody) {
                return error(
                    id,
                    -32022,
                    "Unsupported protocol version",
                    buildJsonObject {
                        put("supported", JsonArray(listOf(JsonPrimitive(MODERN_MCP_VERSION))))
                        put("requested", versionHeader)
                    },
                )
            }
            return error(id, -32020, "Header mismatch: MCP-Protocol-Version")
        }
        if (methodHeader != method) return error(id, -32020, "Header mismatch: Mcp-Method")
        if (metadata["io.modelcontextprotocol/clientCapabilities"] !is JsonObject) {
            return error(id, -32602, "Missing client capabilities")
        }
        if (method == "tools/call") {
            val name = params.stringValue("name") ?: return error(id, -32602, "Missing tool name")
            val encodedNames = call.request.headers.getAll("Mcp-Name")
            if (encodedNames?.size != 1) {
                return error(id, -32020, "Missing or repeated Mcp-Name header")
            }
            val headerName = encodedNames.single().let(::decodeHeaderValue)
            if (headerName != name) return error(id, -32020, "Header mismatch: Mcp-Name")
        } else if (call.request.headers.getAll("Mcp-Name") != null) {
            return error(id, -32020, "Unexpected Mcp-Name header")
        }
        return null
    }

    private fun validOrigin(call: ApplicationCall): Boolean {
        val origin = call.request.header(HttpHeaders.Origin) ?: return true
        return runCatching {
            val originUri = URI(origin)
            val publicUri = URI(runtime.config.publicBaseUrl)
            val frontendUri = URI(runtime.config.frontendBaseUrl)
            validSerializedOrigin(originUri) &&
                (sameOrigin(originUri, publicUri) || sameOrigin(originUri, frontendUri))
        }.getOrDefault(false)
    }

    private fun validSerializedOrigin(uri: URI): Boolean =
        uri.isAbsolute &&
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.query == null &&
            uri.fragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")

    private fun sameOrigin(first: URI, second: URI): Boolean =
        first.scheme.equals(second.scheme, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)

    private fun effectivePort(uri: URI): Int = if (uri.port >= 0) {
        uri.port
    } else if (uri.scheme.equals("https", true)) {
        443
    } else {
        80
    }

    private fun legacyInitializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", LEGACY_MCP_VERSION)
        put("capabilities", buildJsonObject {
            put("tools", buildJsonObject { put("listChanged", false) })
        })
        put("serverInfo", SERVER_META.getValue("io.modelcontextprotocol/serverInfo"))
        put(
            "instructions",
            "Use the tools to list the caller's devices, run terminal sessions, and transfer files between devices.",
        )
    }

    private fun discoverResult(): JsonObject = completeResult {
        put("supportedVersions", JsonArray(listOf(JsonPrimitive(MODERN_MCP_VERSION))))
        put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) })
        put(
            "instructions",
            "Use the tools to list the caller's devices, run terminal sessions, and transfer files between devices.",
        )
        put("ttlMs", 300_000)
        put("cacheScope", "public")
    }

    private fun toolsResult(): JsonObject = completeResult {
        put(
            "tools",
            buildJsonArray {
                RemoteMcpTools.names.sorted().forEach { name ->
                    add(
                        buildJsonObject {
                            put("name", name)
                            put("description", TOOL_DESCRIPTIONS.getValue(name))
                            put("inputSchema", RemoteMcpTools.inputSchemas.getValue(name))
                        },
                    )
                }
            },
        )
        put("ttlMs", 300_000)
        put("cacheScope", "public")
    }

    private fun toolSuccess(value: JsonElement): JsonObject = completeResult {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", ProtocolJson.encodeToString(value))
            })
        })
        put("structuredContent", value)
        put("isError", false)
    }

    private fun toolError(message: String): JsonObject = completeResult {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", message)
            })
        })
        put("isError", true)
    }

    private fun completeResult(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("resultType", "complete")
            block()
            put("_meta", SERVER_META)
        }

    private fun success(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(
        id: JsonElement?,
        code: Int,
        message: String,
        data: JsonElement? = null,
    ): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        if (id != null && id !is JsonNull) put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
            if (data != null) put("data", data)
        })
    }

    private fun decodeHeaderValue(value: String): String? = if (
        value.startsWith(BASE64_PREFIX) && value.endsWith(BASE64_SUFFIX)
    ) {
        runCatching {
            val encoded = value.substring(BASE64_PREFIX.length, value.length - BASE64_SUFFIX.length)
            Base64.getDecoder().decode(encoded).decodeToString()
        }.getOrNull()
    } else {
        value.takeIf { plain ->
            plain == plain.trim() && plain.all { it.code in 0x20..0x7e }
        }
    }

    private inline fun <reified T : OperationResultPayload>
        io.github.stream29.mcp.device.protocol.OperationResultEnvelope.successPayload(): T =
        when (val value = result) {
            is OperationResult.Success -> value.payload as? T
                ?: error("Unexpected device result: ${value.payload::class.simpleName}")
            is OperationResult.Failure -> error("${value.errorCode}: ${value.message}")
        }

    private fun JsonObject.requiredString(name: String): String =
        optionalString(name)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$name is required")

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        return (value as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?: throw IllegalArgumentException("$name must be a string")
    }

    private fun JsonObject.optionalBoolean(name: String): Boolean? {
        val value = get(name) ?: return null
        return (value as? JsonPrimitive)?.booleanOrNull
            ?: throw IllegalArgumentException("$name must be a boolean")
    }

    private fun JsonObject.validateToolArguments(name: String) {
        val (required, allowed) = when (name) {
            RemoteMcpTools.LIST_DEVICE -> emptySet<String>() to emptySet()
            RemoteMcpTools.LAUNCH_TERMINAL_SESSION ->
                setOf("deviceId", "script") to setOf("deviceId", "script", "tty")
            RemoteMcpTools.TERMINAL_SESSION_INPUT ->
                setOf("sessionId") to setOf("sessionId", "stdin", "eof")
            RemoteMcpTools.TERMINAL_SESSION_OUTPUT ->
                setOf("sessionId") to setOf("sessionId")
            RemoteMcpTools.LAUNCH_FILE_TRANSFER ->
                setOf("sourceDeviceId", "sourcePath", "destinationDeviceId", "destinationPath") to
                    setOf("sourceDeviceId", "sourcePath", "destinationDeviceId", "destinationPath")
            RemoteMcpTools.FILE_TRANSFER_STATUS,
            RemoteMcpTools.CANCEL_FILE_TRANSFER,
            -> setOf("transferId") to setOf("transferId")
            else -> throw IllegalArgumentException("Unknown tool")
        }
        val missing = required - keys
        require(missing.isEmpty()) { "Missing tool arguments: ${missing.sorted().joinToString()}" }
        val unknown = keys - allowed
        require(unknown.isEmpty()) { "Unknown tool arguments: ${unknown.sorted().joinToString()}" }
        when (name) {
            RemoteMcpTools.LIST_DEVICE -> Unit
            RemoteMcpTools.LAUNCH_TERMINAL_SESSION -> {
                requiredString("deviceId")
                requiredString("script")
                optionalBoolean("tty")
            }
            RemoteMcpTools.TERMINAL_SESSION_INPUT -> {
                requiredString("sessionId")
                optionalString("stdin")
                optionalBoolean("eof")
            }
            RemoteMcpTools.TERMINAL_SESSION_OUTPUT -> requiredString("sessionId")
            RemoteMcpTools.LAUNCH_FILE_TRANSFER -> {
                requiredString("sourceDeviceId")
                requiredString("sourcePath")
                requiredString("destinationDeviceId")
                requiredString("destinationPath")
            }
            RemoteMcpTools.FILE_TRANSFER_STATUS,
            RemoteMcpTools.CANCEL_FILE_TRANSFER,
            -> requiredString("transferId")
        }
    }

    private fun JsonObject.stringValue(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonElement.validRequestId(): Boolean =
        this is JsonPrimitive && (isString || longOrNull != null)

    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: JsonObject) {
        respondText(ProtocolJson.encodeToString(body), ContentType.Application.Json, status)
    }

    private suspend fun ApplicationCall.receiveMcpText(): String? {
        if (request.contentLength()?.let { it > MAX_MCP_BODY_BYTES } == true) return null
        val channel = receiveChannel()
        val output = java.io.ByteArrayOutputStream(minOf(MAX_MCP_BODY_BYTES, 64 * 1024))
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = channel.readAvailable(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > MAX_MCP_BODY_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray().decodeToString()
    }

    companion object {
        private const val TERMINAL_ENDED_ROUTE_TTL_MILLIS = 30L * 60 * 1_000
        private const val MAX_MCP_BODY_BYTES = 1024 * 1024
        private const val BASE64_PREFIX = "=?base64?"
        private const val BASE64_SUFFIX = "?="
        private val JSON_RPC_REQUEST_KEYS = setOf("jsonrpc", "id", "method", "params")
        private val SERVER_META = buildJsonObject {
            put(
                "io.modelcontextprotocol/serverInfo",
                buildJsonObject {
                    put("name", "device-as-mcp")
                    put("version", "0.1.0")
                    put("title", "DeviceAsMcp")
                },
            )
        }
        private val TOOL_DESCRIPTIONS = mapOf(
            RemoteMcpTools.LIST_DEVICE to "List devices owned by the authenticated user and their online state.",
            RemoteMcpTools.LAUNCH_TERMINAL_SESSION to "Run a shell script on a device, returning output or a background session ID.",
            RemoteMcpTools.TERMINAL_SESSION_INPUT to "Write UTF-8 input or EOF to a background terminal session.",
            RemoteMcpTools.TERMINAL_SESSION_OUTPUT to "Consume currently unread output from a background terminal session.",
            RemoteMcpTools.LAUNCH_FILE_TRANSFER to "Transfer a file or folder directly between two owned devices through the relay.",
            RemoteMcpTools.FILE_TRANSFER_STATUS to "Read the aggregate status of a file transfer.",
            RemoteMcpTools.CANCEL_FILE_TRANSFER to "Cancel a running file transfer without deleting written destination content.",
        )
    }
}

private suspend fun ApplicationCall.resolveMcpPrincipal(accounts: AccountStore): McpTokenPrincipal? {
    val authorizationValues = request.headers.getAll(HttpHeaders.Authorization)
    if (authorizationValues?.size != 1) return null
    val authorization = authorizationValues.single().trim()
    val separator = authorization.indexOf(' ')
    if (separator <= 0 || !authorization.take(separator).equals("Bearer", ignoreCase = true)) return null
    val bearer = authorization.drop(separator + 1)
    if (bearer.isBlank() || bearer.any(Char::isWhitespace)) return null
    return accounts.mcpPrincipal(bearer)
}

private fun ApplicationCall.singleHeader(name: String): String? =
    request.headers.getAll(name)?.singleOrNull()
