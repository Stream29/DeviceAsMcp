package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.ConnectionId
import io.github.stream29.mcp.device.protocol.DeviceId
import io.github.stream29.mcp.device.protocol.FileManifest
import io.github.stream29.mcp.device.protocol.FileManifestEntry
import io.github.stream29.mcp.device.protocol.FileTransferPlan
import io.github.stream29.mcp.device.protocol.FileTransferRecord
import io.github.stream29.mcp.device.protocol.FileTransferStatus
import io.github.stream29.mcp.device.protocol.InstanceId
import io.github.stream29.mcp.device.protocol.InstanceRpcRequest
import io.github.stream29.mcp.device.protocol.InstanceRpcResponse
import io.github.stream29.mcp.device.protocol.ManifestEntryType
import io.github.stream29.mcp.device.protocol.OperationEnvelope
import io.github.stream29.mcp.device.protocol.OperationErrorCode
import io.github.stream29.mcp.device.protocol.OperationId
import io.github.stream29.mcp.device.protocol.OperationPayload
import io.github.stream29.mcp.device.protocol.OperationResult
import io.github.stream29.mcp.device.protocol.OperationResultEnvelope
import io.github.stream29.mcp.device.protocol.OperationResultPayload
import io.github.stream29.mcp.device.protocol.TransferId
import io.github.stream29.mcp.device.protocol.UserId
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiddlewareIntegrationTest {
    @Test
    fun postgresqlAccountLifecycle() = runBlocking {
        requireIntegration()
        val jdbcUrl = environment("DEVICE_AS_MCP_TEST_DATABASE_URL", "jdbc:postgresql://localhost:5432/device_as_mcp")
        val databaseUser = environment("DEVICE_AS_MCP_TEST_DATABASE_USER", "device_as_mcp")
        val databasePassword = environment("DEVICE_AS_MCP_TEST_DATABASE_PASSWORD", "device_as_mcp")
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val username = "integration-$suffix"
        val githubLogin = "github-$suffix"
        val store = PostgresAccountStore(jdbcUrl, databaseUser, databasePassword)
        try {
            assertTrue(store.isReady())
            val user = assertNotNull(store.register(username, "integration password"))
            assertEquals(user.id, store.authenticate(username, "integration password")?.id)

            val session = store.issueSession(user)
            assertEquals(user.id, store.userByToken(session)?.id)
            store.revokeSession(session)
            assertNull(store.userByToken(session))

            val key = store.createAuthKey(
                user.id,
                "integration",
                "https://example.test/mcp",
                setOf(OAuthService.MCP_SCOPE),
            )
            assertEquals(user.id, store.mcpPrincipal(key.token)?.user?.id)
            assertTrue(store.revokeAuthKey(user.id, key.id))
            assertNull(store.mcpPrincipal(key.token))

            val enrollment = store.createEnrollmentToken(user.id)
            assertEquals(user.id, store.consumeEnrollmentToken(enrollment.token))
            assertNull(store.consumeEnrollmentToken(enrollment.token))

            val device = store.enrollDevice(user.id, "integration device", "linux-x64")
            assertTrue(store.authenticateDevice(device.deviceId, device.secret))
            assertEquals(user.id, store.deviceUser(device.deviceId))
            assertTrue(store.renameDevice(user.id, device.deviceId, "renamed integration device"))
            assertEquals("renamed integration device", store.devices(user.id).single().name)

            val github = store.findOrCreateGithubUser("github-id-$suffix", githubLogin)
            val renamed = store.findOrCreateGithubUser("github-id-$suffix", "$githubLogin-renamed")
            assertEquals(github.id, renamed.id)
        } finally {
            store.close()
            DriverManager.getConnection(jdbcUrl, databaseUser, databasePassword).use { connection ->
                connection.prepareStatement(
                    "delete from app_user where username = ? or username like ?",
                ).use {
                    it.setString(1, username)
                    it.setString(2, "$githubLogin%")
                    it.executeUpdate()
                }
            }
        }
    }

    @Test
    fun redisFencingAndTransferLifecycle() = runBlocking {
        requireIntegration()
        val store = RedisRoutingStore(
            environment("DEVICE_AS_MCP_TEST_REDIS_URL", "redis://localhost:6379"),
        )
        val suffix = UUID.randomUUID().toString()
        val deviceId = DeviceId(suffix)
        val owner = DeviceOwner(
            InstanceId(UUID.randomUUID().toString()),
            ConnectionId(UUID.randomUUID().toString()),
            System.currentTimeMillis() + 30_000,
        )
        val transferId = TransferId(UUID.randomUUID().toString())
        try {
            assertTrue(store.isReady())
            assertTrue(store.claimDevice(deviceId, owner))
            assertFalse(
                store.renewDevice(
                    deviceId,
                    owner.copy(connectionId = ConnectionId(UUID.randomUUID().toString())),
                ),
            )
            assertTrue(
                store.renewDevice(
                    deviceId,
                    owner.copy(expiresAtEpochMillis = System.currentTimeMillis() + 30_000),
                ),
            )

            val ephemeralKey = "integration-$suffix"
            store.putEphemeral(ephemeralKey, "pending", 30_000)
            assertTrue(store.compareAndSetEphemeral(ephemeralKey, "pending", "complete", 30_000))
            assertFalse(store.compareAndSetEphemeral(ephemeralKey, "pending", "other", 30_000))
            assertEquals("complete", store.consumeEphemeral(ephemeralKey))

            val manifest = FileManifest(
                ManifestEntryType.DIRECTORY,
                listOf(FileManifestEntry("file.txt", ManifestEntryType.FILE, 4)),
            )
            val plan = FileTransferPlan(listOf("file.txt"), 0)
            assertTrue(
                store.createTransfer(
                    FileTransferRecord(
                        transferId,
                        UserId(UUID.randomUUID().toString()),
                        deviceId,
                        "/source",
                        DeviceId(UUID.randomUUID().toString()),
                        "/destination",
                        owner.instanceId,
                    ),
                ),
            )
            assertTrue(store.putTransferManifest(transferId, manifest))
            assertTrue(store.putTransferPlan(transferId, plan))
            assertTrue(store.updateTransfer(transferId, FileTransferStatus.RUNNING))
            assertTrue(store.markTransferFileSuccess(transferId, "file.txt"))
            assertEquals(1, store.transfer(transferId)?.successfulFiles)
            assertTrue(
                store.failRunningTransfer(
                    transferId,
                    OperationErrorCode.SERVER_INSTANCE_LOST,
                    "integration failure",
                ),
            )
            assertEquals(FileTransferStatus.FAILED, store.transfer(transferId)?.status)
        } finally {
            store.removeTransfer(transferId)
            store.releaseDevice(deviceId, owner)
            store.close()
        }
    }

    @Test
    fun rabbitMqExactInstanceRpcAndUnroutableFailure() = runBlocking {
        requireIntegration()
        val rabbitUrl = environment(
            "DEVICE_AS_MCP_TEST_RABBITMQ_URL",
            "amqp://device_as_mcp:device_as_mcp@localhost:5672",
        )
        val callerId = InstanceId(UUID.randomUUID().toString())
        val targetId = InstanceId(UUID.randomUUID().toString())
        val received = Channel<Unit>(Channel.UNLIMITED)
        val target = RabbitInstanceRpc(rabbitUrl, targetId) { request ->
            if (request is InstanceRpcRequest.CancelOperation) received.trySend(Unit)
            InstanceRpcResponse.Accepted
        }
        val caller = RabbitInstanceRpc(rabbitUrl, callerId) { InstanceRpcResponse.Accepted }
        try {
            assertTrue(target.isReady())
            assertTrue(caller.isReady())
            val operation = OperationEnvelope(
                operationId = OperationId(UUID.randomUUID().toString()),
                deviceId = DeviceId(UUID.randomUUID().toString()),
                payload = OperationPayload.CancelOperation(OperationId(UUID.randomUUID().toString())),
            )
            val request = InstanceRpcRequest.CancelOperation(
                ConnectionId(UUID.randomUUID().toString()),
                operation,
            )
            val deadline = System.currentTimeMillis() + 5_000

            assertEquals(
                InstanceRpcResponse.Accepted,
                caller.call(targetId, request, deadline, 5_000),
            )
            withTimeout(5_000) { received.receive() }
            caller.publish(targetId, request, System.currentTimeMillis() + 5_000)
            withTimeout(5_000) { received.receive() }

            val missing = caller.call(
                InstanceId(UUID.randomUUID().toString()),
                request,
                System.currentTimeMillis() + 5_000,
                5_000,
            )
            assertTrue(missing is InstanceRpcResponse.Rejected)
            assertEquals(OperationErrorCode.DEVICE_OWNER_STALE, missing.errorCode)
        } finally {
            caller.close()
            target.close()
        }
    }

    @Test
    fun daemonResultRoutesThroughOwnerToOrigin() = runBlocking {
        requireIntegration()
        val redisUrl = environment("DEVICE_AS_MCP_TEST_REDIS_URL", "redis://localhost:6379")
        val rabbitUrl = environment(
            "DEVICE_AS_MCP_TEST_RABBITMQ_URL",
            "amqp://device_as_mcp:device_as_mcp@localhost:5672",
        )
        val originId = InstanceId(UUID.randomUUID().toString())
        val ownerId = InstanceId(UUID.randomUUID().toString())
        val ingressId = InstanceId(UUID.randomUUID().toString())
        val originRouting = RedisRoutingStore(redisUrl)
        val ownerRouting = RedisRoutingStore(redisUrl)
        val ingressRouting = RedisRoutingStore(redisUrl)
        val originWaiters = OperationWaiters()
        val originOperations = OperationService(
            originId,
            originRouting,
            DeviceConnectionRegistry(),
            originWaiters,
            NoopInstanceRpc(),
        )
        val ownerOperations = OperationService(
            ownerId,
            ownerRouting,
            DeviceConnectionRegistry(),
            OperationWaiters(),
            NoopInstanceRpc(),
        )
        val ingressOperations = OperationService(
            ingressId,
            ingressRouting,
            DeviceConnectionRegistry(),
            OperationWaiters(),
            NoopInstanceRpc(),
        )
        val originRpc = RabbitInstanceRpc(rabbitUrl, originId, originOperations::handleRpc)
        val ownerRpc = RabbitInstanceRpc(rabbitUrl, ownerId, ownerOperations::handleRpc)
        val ingressRpc = RabbitInstanceRpc(rabbitUrl, ingressId, ingressOperations::handleRpc)
        originOperations.attachRpc(originRpc)
        ownerOperations.attachRpc(ownerRpc)
        ingressOperations.attachRpc(ingressRpc)

        val userId = UserId(UUID.randomUUID().toString())
        val deviceId = DeviceId(UUID.randomUUID().toString())
        val operationId = OperationId(UUID.randomUUID().toString())
        val owner = DeviceOwner(
            ownerId,
            ConnectionId(UUID.randomUUID().toString()),
            System.currentTimeMillis() + 30_000,
        )
        val result = OperationResultEnvelope(
            operationId,
            OperationResult.Success(OperationResultPayload.Acknowledged()),
        )
        try {
            assertTrue(ownerRouting.claimDevice(deviceId, owner))
            originRouting.putOperationOrigin(
                operationId,
                originId,
                System.currentTimeMillis() + 30_000,
            )
            val waiter = assertNotNull(originWaiters.register(operationId, userId, deviceId))

            assertEquals(
                ResultAcceptance.ACCEPTED,
                ingressOperations.acceptDaemonResult(userId, deviceId, result),
            )
            assertEquals(result, withTimeout(5_000) { waiter.await() })
            assertEquals(
                ResultAcceptance.DUPLICATE,
                ingressOperations.acceptDaemonResult(userId, deviceId, result),
            )
        } finally {
            originWaiters.remove(operationId)
            originRouting.removeOperationOrigin(operationId)
            ownerRouting.releaseDevice(deviceId, owner)
            ingressRpc.close()
            ownerRpc.close()
            originRpc.close()
            ingressRouting.close()
            ownerRouting.close()
            originRouting.close()
        }
    }

    private fun requireIntegration() {
        assumeTrue(
            System.getenv("DEVICE_AS_MCP_RUN_INTEGRATION_TESTS") == "true",
            "Set DEVICE_AS_MCP_RUN_INTEGRATION_TESTS=true to run middleware integration tests",
        )
    }

    private fun environment(name: String, default: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank) ?: default
}
