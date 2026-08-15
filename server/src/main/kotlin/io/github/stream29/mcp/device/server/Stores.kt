package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.AuthKeySummary
import io.github.stream29.mcp.device.protocol.AuthenticatedUser
import io.github.stream29.mcp.device.protocol.ConnectionId
import io.github.stream29.mcp.device.protocol.CreatedAuthKey
import io.github.stream29.mcp.device.protocol.DeviceCredential
import io.github.stream29.mcp.device.protocol.DeviceId
import io.github.stream29.mcp.device.protocol.DeviceSummary
import io.github.stream29.mcp.device.protocol.DeviceEnrollmentToken
import io.github.stream29.mcp.device.protocol.FileManifest
import io.github.stream29.mcp.device.protocol.FileTransferPlan
import io.github.stream29.mcp.device.protocol.FileTransferRecord
import io.github.stream29.mcp.device.protocol.FileTransferStatus
import io.github.stream29.mcp.device.protocol.InstanceId
import io.github.stream29.mcp.device.protocol.OperationId
import io.github.stream29.mcp.device.protocol.OperationErrorCode
import io.github.stream29.mcp.device.protocol.TerminalSessionId
import io.github.stream29.mcp.device.protocol.TransferId
import io.github.stream29.mcp.device.protocol.UserId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

internal data class DeviceOwner(
    val instanceId: InstanceId,
    val connectionId: ConnectionId,
    val expiresAtEpochMillis: Long,
)

internal data class TerminalRoute(
    val userId: UserId,
    val deviceId: DeviceId,
)

internal data class McpTokenPrincipal(
    val user: AuthenticatedUser,
    val audience: String,
    val scopes: Set<String>,
)

internal interface RoutingStore {
    suspend fun isReady(): Boolean
    suspend fun claimDevice(deviceId: DeviceId, owner: DeviceOwner): Boolean
    suspend fun renewDevice(deviceId: DeviceId, owner: DeviceOwner): Boolean
    suspend fun releaseDevice(deviceId: DeviceId, owner: DeviceOwner): Boolean
    suspend fun deviceOwner(deviceId: DeviceId): DeviceOwner?
    suspend fun putOperationOrigin(operationId: OperationId, origin: InstanceId, expiresAtEpochMillis: Long)
    suspend fun operationOrigin(operationId: OperationId): InstanceId?
    suspend fun removeOperationOrigin(operationId: OperationId)
    suspend fun putEphemeral(key: String, value: String, ttlMillis: Long)
    suspend fun ephemeral(key: String): String?
    suspend fun consumeEphemeral(key: String): String?
    suspend fun compareAndSetEphemeral(
        key: String,
        expected: String,
        value: String,
        ttlMillis: Long,
    ): Boolean
    suspend fun putTerminalRoute(sessionId: TerminalSessionId, route: TerminalRoute, ttlMillis: Long?)
    suspend fun terminalRoute(sessionId: TerminalSessionId): TerminalRoute?
    suspend fun removeTerminalRoute(sessionId: TerminalSessionId)
    suspend fun createTransfer(record: FileTransferRecord): Boolean
    suspend fun putTransferManifest(transferId: TransferId, manifest: FileManifest): Boolean
    suspend fun putTransferPlan(transferId: TransferId, plan: FileTransferPlan): Boolean
    suspend fun transfer(transferId: TransferId): FileTransferRecord?
    suspend fun transferManifest(transferId: TransferId): FileManifest?
    suspend fun transferPlan(transferId: TransferId): FileTransferPlan?
    suspend fun updateTransfer(
        transferId: TransferId,
        status: FileTransferStatus,
        errorCode: OperationErrorCode? = null,
        message: String? = null,
    ): Boolean
    suspend fun markTransferFileSuccess(transferId: TransferId, relativePath: String): Boolean
    suspend fun failRunningTransfer(
        transferId: TransferId,
        errorCode: OperationErrorCode,
        message: String,
    ): Boolean
    suspend fun removeTransfer(transferId: TransferId)
}

internal class InMemoryRoutingStore(
    private val now: () -> Long = System::currentTimeMillis,
) : RoutingStore {
    private val mutex = Mutex()
    private val owners = mutableMapOf<DeviceId, DeviceOwner>()
    private val origins = mutableMapOf<OperationId, Pair<InstanceId, Long>>()
    private val ephemeral = mutableMapOf<String, Pair<String, Long>>()
    private val terminalRoutes = mutableMapOf<TerminalSessionId, Pair<TerminalRoute, Long?>>()
    private data class TransferEntry(
        var record: FileTransferRecord,
        var manifest: FileManifest? = null,
        var plan: FileTransferPlan? = null,
        val successfulPaths: MutableSet<String> = mutableSetOf(),
        var expiresAtMillis: Long,
    )
    private val transfers = mutableMapOf<TransferId, TransferEntry>()

    override suspend fun isReady(): Boolean = true

    override suspend fun claimDevice(deviceId: DeviceId, owner: DeviceOwner): Boolean = mutex.withLock {
        val current = owners[deviceId]
        if (current != null && current.expiresAtEpochMillis > now()) return@withLock false
        owners[deviceId] = owner
        true
    }

    override suspend fun renewDevice(deviceId: DeviceId, owner: DeviceOwner): Boolean = mutex.withLock {
        val current = owners[deviceId]
        if (
            current == null ||
            current.instanceId != owner.instanceId ||
            current.connectionId != owner.connectionId
        ) {
            return@withLock false
        }
        owners[deviceId] = owner
        true
    }

    override suspend fun releaseDevice(deviceId: DeviceId, owner: DeviceOwner): Boolean = mutex.withLock {
        val current = owners[deviceId]
        if (
            current == null ||
            current.instanceId != owner.instanceId ||
            current.connectionId != owner.connectionId
        ) {
            return@withLock false
        }
        owners.remove(deviceId)
        true
    }

    override suspend fun deviceOwner(deviceId: DeviceId): DeviceOwner? = mutex.withLock {
        owners[deviceId]?.takeIf { it.expiresAtEpochMillis > now() } ?: run {
            owners.remove(deviceId)
            null
        }
    }

    override suspend fun putOperationOrigin(
        operationId: OperationId,
        origin: InstanceId,
        expiresAtEpochMillis: Long,
    ) = mutex.withLock { origins[operationId] = origin to expiresAtEpochMillis }

    override suspend fun operationOrigin(operationId: OperationId): InstanceId? = mutex.withLock {
        origins[operationId]?.takeIf { it.second > now() }?.first ?: run {
            origins.remove(operationId)
            null
        }
    }

    override suspend fun removeOperationOrigin(operationId: OperationId) {
        mutex.withLock { origins.remove(operationId) }
    }

    override suspend fun putEphemeral(key: String, value: String, ttlMillis: Long) {
        require(key.isNotBlank() && ttlMillis > 0)
        mutex.withLock { ephemeral[key] = value to (now() + ttlMillis) }
    }

    override suspend fun ephemeral(key: String): String? = mutex.withLock {
        ephemeral[key]?.takeIf { it.second > now() }?.first ?: run {
            ephemeral.remove(key)
            null
        }
    }

    override suspend fun consumeEphemeral(key: String): String? = mutex.withLock {
        ephemeral.remove(key)?.takeIf { it.second > now() }?.first
    }

    override suspend fun compareAndSetEphemeral(
        key: String,
        expected: String,
        value: String,
        ttlMillis: Long,
    ): Boolean {
        require(key.isNotBlank() && ttlMillis > 0)
        return mutex.withLock {
            val current = ephemeral[key]?.takeIf { it.second > now() }?.first
            if (current != expected) {
                if (current == null) ephemeral.remove(key)
                return@withLock false
            }
            ephemeral[key] = value to (now() + ttlMillis)
            true
        }
    }

    override suspend fun putTerminalRoute(sessionId: TerminalSessionId, route: TerminalRoute, ttlMillis: Long?) {
        mutex.withLock { terminalRoutes[sessionId] = route to ttlMillis?.let { now() + it } }
    }

    override suspend fun terminalRoute(sessionId: TerminalSessionId): TerminalRoute? = mutex.withLock {
        terminalRoutes[sessionId]?.takeIf { it.second == null || it.second!! > now() }?.first ?: run {
            terminalRoutes.remove(sessionId)
            null
        }
    }

    override suspend fun removeTerminalRoute(sessionId: TerminalSessionId) {
        mutex.withLock { terminalRoutes.remove(sessionId) }
    }

    override suspend fun createTransfer(record: FileTransferRecord): Boolean = mutex.withLock {
        removeExpiredTransfers()
        if (record.transferId in transfers) return@withLock false
        transfers[record.transferId] = TransferEntry(
            record = record,
            expiresAtMillis = now() + TRANSFER_TTL_MILLIS,
        )
        true
    }

    override suspend fun putTransferManifest(transferId: TransferId, manifest: FileManifest): Boolean = mutex.withLock {
        val entry = activeTransfer(transferId) ?: return@withLock false
        if (entry.manifest != null) return@withLock false
        entry.manifest = manifest
        entry.refresh()
        true
    }

    override suspend fun putTransferPlan(transferId: TransferId, plan: FileTransferPlan): Boolean = mutex.withLock {
        val entry = activeTransfer(transferId) ?: return@withLock false
        if (entry.plan != null) return@withLock false
        entry.plan = plan
        entry.refresh()
        true
    }

    override suspend fun transfer(transferId: TransferId): FileTransferRecord? = mutex.withLock {
        activeTransfer(transferId)?.record
    }

    override suspend fun transferManifest(transferId: TransferId): FileManifest? = mutex.withLock {
        activeTransfer(transferId)?.manifest
    }

    override suspend fun transferPlan(transferId: TransferId): FileTransferPlan? = mutex.withLock {
        activeTransfer(transferId)?.plan
    }

    override suspend fun updateTransfer(
        transferId: TransferId,
        status: FileTransferStatus,
        errorCode: OperationErrorCode?,
        message: String?,
    ): Boolean = mutex.withLock {
        val entry = activeTransfer(transferId) ?: return@withLock false
        entry.record = entry.record.copy(status = status, errorCode = errorCode, message = message)
        entry.refresh()
        true
    }

    override suspend fun markTransferFileSuccess(transferId: TransferId, relativePath: String): Boolean = mutex.withLock {
        val entry = activeTransfer(transferId) ?: return@withLock false
        if (entry.record.status != FileTransferStatus.RUNNING) return@withLock false
        if (!entry.successfulPaths.add(relativePath)) return@withLock true
        entry.record = entry.record.copy(successfulFiles = entry.successfulPaths.size)
        entry.refresh()
        true
    }

    override suspend fun failRunningTransfer(
        transferId: TransferId,
        errorCode: OperationErrorCode,
        message: String,
    ): Boolean = mutex.withLock {
        val entry = activeTransfer(transferId) ?: return@withLock false
        if (entry.record.status != FileTransferStatus.RUNNING) return@withLock false
        entry.record = entry.record.copy(
            status = FileTransferStatus.FAILED,
            errorCode = errorCode,
            message = message,
        )
        entry.refresh()
        true
    }

    override suspend fun removeTransfer(transferId: TransferId) {
        mutex.withLock { transfers.remove(transferId) }
    }

    private fun activeTransfer(transferId: TransferId): TransferEntry? {
        val entry = transfers[transferId] ?: return null
        if (entry.expiresAtMillis > now()) return entry
        transfers.remove(transferId)
        return null
    }

    private fun removeExpiredTransfers() {
        val current = now()
        transfers.entries.removeAll { it.value.expiresAtMillis <= current }
    }

    private fun TransferEntry.refresh() {
        expiresAtMillis = now() + TRANSFER_TTL_MILLIS
    }

    companion object {
        private const val TRANSFER_TTL_MILLIS = 30 * 60 * 1_000L
    }
}

internal interface AccountStore {
    suspend fun isReady(): Boolean
    suspend fun register(username: String, password: String): AuthenticatedUser?
    suspend fun authenticate(username: String, password: String): AuthenticatedUser?
    suspend fun userByToken(token: String): AuthenticatedUser?
    suspend fun mcpPrincipal(token: String): McpTokenPrincipal?
    suspend fun issueSession(user: AuthenticatedUser): String
    suspend fun revokeSession(token: String)
    suspend fun issueMcpAccessToken(
        userId: UserId,
        audience: String,
        scopes: Set<String>,
        expiresAtEpochMillis: Long,
    ): String
    suspend fun createAuthKey(
        userId: UserId,
        name: String,
        audience: String,
        scopes: Set<String>,
    ): CreatedAuthKey
    suspend fun authKeys(userId: UserId): List<AuthKeySummary>
    suspend fun revokeAuthKey(userId: UserId, keyId: String): Boolean
    suspend fun createEnrollmentToken(userId: UserId): DeviceEnrollmentToken
    suspend fun consumeEnrollmentToken(token: String): UserId?
    suspend fun enrollDevice(userId: UserId, name: String, platform: String): DeviceCredential
    suspend fun devices(userId: UserId): List<DeviceSummary>
    suspend fun renameDevice(userId: UserId, deviceId: DeviceId, name: String): Boolean
    suspend fun updateDeviceDescription(
        userId: UserId,
        deviceId: DeviceId,
        description: String,
    ): DeviceSummary?
    suspend fun revokeDevice(userId: UserId, deviceId: DeviceId): Boolean
    suspend fun deviceUser(deviceId: DeviceId): UserId?
    suspend fun authenticateDevice(deviceId: DeviceId, secret: String): Boolean
    suspend fun findOrCreateGithubUser(githubId: String, githubLogin: String): AuthenticatedUser
}

internal class InMemoryAccountStore(
    private val now: () -> Long = System::currentTimeMillis,
) : AccountStore {
    private data class UserRecord(
        val user: AuthenticatedUser,
        val passwordHash: String,
        val githubId: String? = null,
    )
    private data class AuthKeyRecord(
        val summary: AuthKeySummary,
        val userId: UserId,
        val token: String,
        val audience: String,
        val scopes: Set<String>,
    )
    private data class AccessTokenRecord(
        val userId: UserId,
        val audience: String,
        val scopes: Set<String>,
        val expiresAtEpochMillis: Long,
    )
    private data class DeviceRecord(
        val summary: DeviceSummary,
        val userId: UserId,
        val secret: String,
    )

    private val mutex = Mutex()
    private val users = mutableMapOf<String, UserRecord>()
    private val sessions = mutableMapOf<String, Pair<UserId, Long>>()
    private val mcpAccessTokens = mutableMapOf<String, AccessTokenRecord>()
    private val authKeys = mutableMapOf<String, AuthKeyRecord>()
    private val enrollmentTokens = mutableMapOf<String, Pair<UserId, Long>>()
    private val devices = mutableMapOf<DeviceId, DeviceRecord>()
    private val random = SecureRandom()

    override suspend fun isReady(): Boolean = true

    override suspend fun register(username: String, password: String): AuthenticatedUser? = mutex.withLock {
        val normalized = username.trim().lowercase()
        if (normalized.length !in 1..100 || password.length !in 8..1_024 || normalized in users) {
            return@withLock null
        }
        val user = AuthenticatedUser(UserId(UUID.randomUUID().toString()), normalized)
        users[normalized] = UserRecord(user, BCrypt.hashpw(password, BCrypt.gensalt(12)))
        user
    }

    override suspend fun authenticate(username: String, password: String): AuthenticatedUser? = mutex.withLock {
        if (username.length > 100 || password.length > 1_024) return@withLock null
        users[username.trim().lowercase()]?.takeIf { BCrypt.checkpw(password, it.passwordHash) }?.user
    }

    override suspend fun userByToken(token: String): AuthenticatedUser? = mutex.withLock {
        val session = sessions[token] ?: return@withLock null
        if (session.second <= now()) {
            sessions.remove(token)
            return@withLock null
        }
        val userId = session.first
        users.values.firstOrNull { it.user.id == userId }?.user
    }

    override suspend fun mcpPrincipal(token: String): McpTokenPrincipal? = mutex.withLock {
        val oauth = mcpAccessTokens[token]?.takeIf { it.expiresAtEpochMillis > now() }
        if (mcpAccessTokens[token] != null && oauth == null) mcpAccessTokens.remove(token)
        val authKey = authKeys.values.firstOrNull { it.token == token }
        val userId = authKey?.userId ?: oauth?.userId ?: return@withLock null
        val user = users.values.firstOrNull { it.user.id == userId }?.user ?: return@withLock null
        McpTokenPrincipal(
            user = user,
            audience = authKey?.audience ?: requireNotNull(oauth).audience,
            scopes = authKey?.scopes ?: requireNotNull(oauth).scopes,
        )
    }

    override suspend fun issueSession(user: AuthenticatedUser): String = mutex.withLock {
        randomToken().also {
            sessions[it] = user.id to (now() + SESSION_TTL_MILLIS)
        }
    }

    override suspend fun revokeSession(token: String) {
        mutex.withLock { sessions.remove(token) }
    }

    override suspend fun issueMcpAccessToken(
        userId: UserId,
        audience: String,
        scopes: Set<String>,
        expiresAtEpochMillis: Long,
    ): String = mutex.withLock {
        require(audience.isNotBlank() && scopes.isNotEmpty())
        require(expiresAtEpochMillis > now())
        randomToken().also {
            mcpAccessTokens[it] = AccessTokenRecord(userId, audience, scopes.toSet(), expiresAtEpochMillis)
        }
    }

    override suspend fun createAuthKey(
        userId: UserId,
        name: String,
        audience: String,
        scopes: Set<String>,
    ): CreatedAuthKey = mutex.withLock {
        require(name.trim().length in 1..100) { "Auth key name must contain 1 to 100 characters" }
        require(audience.isNotBlank() && scopes.isNotEmpty())
        val id = UUID.randomUUID().toString()
        val token = randomToken()
        val summary = AuthKeySummary(id, name.ifBlank { "auth key" }, now())
        authKeys[id] = AuthKeyRecord(summary, userId, token, audience, scopes.toSet())
        CreatedAuthKey(id, summary.name, token)
    }

    override suspend fun authKeys(userId: UserId): List<AuthKeySummary> = mutex.withLock {
        authKeys.values.filter { it.userId == userId }.map { it.summary }.sortedByDescending { it.createdAtEpochMillis }
    }

    override suspend fun revokeAuthKey(userId: UserId, keyId: String): Boolean = mutex.withLock {
        if (authKeys[keyId]?.userId != userId) return@withLock false
        authKeys.remove(keyId) != null
    }

    override suspend fun createEnrollmentToken(userId: UserId): DeviceEnrollmentToken = mutex.withLock {
        val token = randomToken()
        val expiresAt = now() + ENROLLMENT_TTL_MILLIS
        enrollmentTokens[token] = userId to expiresAt
        DeviceEnrollmentToken(token, expiresAt)
    }

    override suspend fun consumeEnrollmentToken(token: String): UserId? = mutex.withLock {
        val entry = enrollmentTokens.remove(token) ?: return@withLock null
        entry.first.takeIf { entry.second > now() }
    }

    override suspend fun enrollDevice(userId: UserId, name: String, platform: String): DeviceCredential = mutex.withLock {
        require(name.trim().length in 1..100) { "Device name must contain 1 to 100 characters" }
        require(platform.trim().length in 1..100) { "Platform must contain 1 to 100 characters" }
        val id = DeviceId(UUID.randomUUID().toString())
        val secret = randomToken()
        devices[id] = DeviceRecord(DeviceSummary(id, name, platform, false), userId, secret)
        DeviceCredential(id, secret)
    }

    override suspend fun devices(userId: UserId): List<DeviceSummary> = mutex.withLock {
        devices.values.filter { it.userId == userId }.map { it.summary }
    }

    override suspend fun renameDevice(userId: UserId, deviceId: DeviceId, name: String): Boolean =
        mutex.withLock {
            val normalized = name.trim()
            require(normalized.length in 1..100) {
                "Device name must contain 1 to 100 characters"
            }
            val device = devices[deviceId]?.takeIf { it.userId == userId }
                ?: return@withLock false
            devices[deviceId] = device.copy(summary = device.summary.copy(name = normalized))
            true
        }

    override suspend fun updateDeviceDescription(
        userId: UserId,
        deviceId: DeviceId,
        description: String,
    ): DeviceSummary? = mutex.withLock {
        val device = devices[deviceId]?.takeIf { it.userId == userId }
            ?: return@withLock null
        val updated = device.summary.copy(description = description)
        devices[deviceId] = device.copy(summary = updated)
        updated
    }

    override suspend fun revokeDevice(userId: UserId, deviceId: DeviceId): Boolean = mutex.withLock {
        if (devices[deviceId]?.userId != userId) return@withLock false
        devices.remove(deviceId) != null
    }

    override suspend fun deviceUser(deviceId: DeviceId): UserId? = mutex.withLock { devices[deviceId]?.userId }

    override suspend fun authenticateDevice(deviceId: DeviceId, secret: String): Boolean = mutex.withLock {
        devices[deviceId]?.secret == secret
    }

    override suspend fun findOrCreateGithubUser(
        githubId: String,
        githubLogin: String,
    ): AuthenticatedUser = mutex.withLock {
        require(githubId.isNotBlank() && githubId.length <= 100) { "GitHub user ID is invalid" }
        val normalized = githubLogin.trim().lowercase()
        require(normalized.length in 1..100) { "GitHub login is invalid" }
        users.entries.firstOrNull { it.value.githubId == githubId }?.let { (username, record) ->
            val updated = record.copy(user = record.user.copy(githubLogin = normalized))
            users[username] = updated
            users.entries
                .filter { it.key != username && it.value.user.githubLogin == normalized }
                .forEach { (otherUsername, otherRecord) ->
                    users[otherUsername] = otherRecord.copy(
                        user = otherRecord.user.copy(githubLogin = null),
                    )
                }
            return@withLock updated.user
        }
        users.entries
            .filter { it.value.user.githubLogin == normalized }
            .forEach { (username, record) ->
                users[username] = record.copy(user = record.user.copy(githubLogin = null))
            }
        run {
            var username = normalized
            var suffix = 1
            while (username in users) username = "$normalized-github-${suffix++}"
            val user = AuthenticatedUser(UserId(UUID.randomUUID().toString()), username, normalized)
            users[username] = UserRecord(
                user,
                BCrypt.hashpw(randomToken(), BCrypt.gensalt(12)),
                githubId,
            )
            user
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val ENROLLMENT_TTL_MILLIS = 10 * 60 * 1_000L
        private const val SESSION_TTL_MILLIS = 30L * 24 * 60 * 60 * 1_000
    }
}
