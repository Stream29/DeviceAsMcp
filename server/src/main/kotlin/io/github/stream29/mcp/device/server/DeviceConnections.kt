package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.ConnectionId
import io.github.stream29.mcp.device.protocol.DeviceId
import io.github.stream29.mcp.device.protocol.OperationEnvelope
import io.github.stream29.mcp.device.protocol.OperationId
import io.github.stream29.mcp.device.protocol.OperationResultEnvelope
import io.github.stream29.mcp.device.protocol.ProtocolJson
import io.github.stream29.mcp.device.protocol.UserId
import io.ktor.server.sse.ServerSSESession
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

internal data class LocalDeviceConnection(
    val userId: UserId,
    val deviceId: DeviceId,
    val connectionId: ConnectionId,
    val session: ServerSSESession,
    val ended: CompletableDeferred<Unit>,
) {
    val sendMutex = Mutex()
}

internal class DeviceConnectionRegistry {
    private val connections = ConcurrentHashMap<DeviceId, LocalDeviceConnection>()

    fun put(connection: LocalDeviceConnection): Boolean = connections.putIfAbsent(connection.deviceId, connection) == null

    fun get(deviceId: DeviceId): LocalDeviceConnection? = connections[deviceId]

    fun remove(deviceId: DeviceId, connectionId: ConnectionId): Boolean {
        val current = connections[deviceId] ?: return false
        if (current.connectionId != connectionId) return false
        return connections.remove(deviceId, current)
    }

    fun disconnect(deviceId: DeviceId, connectionId: ConnectionId): Boolean {
        val connection = connections[deviceId] ?: return false
        if (connection.connectionId != connectionId) return false
        connection.ended.complete(Unit)
        return true
    }

    suspend fun send(deviceId: DeviceId, connectionId: ConnectionId, operation: OperationEnvelope): Boolean {
        val connection = connections[deviceId] ?: return false
        if (connection.connectionId != connectionId) return false
        return runCatching {
            connection.sendMutex.withLock {
                connection.session.send(
                    ServerSentEvent(
                        data = ProtocolJson.encodeToString(operation),
                        event = "operation",
                        id = operation.operationId.value,
                    ),
                )
            }
        }.isSuccess
    }

    suspend fun keepAlive(deviceId: DeviceId, connectionId: ConnectionId): Boolean {
        val connection = connections[deviceId] ?: return false
        if (connection.connectionId != connectionId) return false
        return runCatching {
            connection.sendMutex.withLock {
                connection.session.send(ServerSentEvent(retry = 2_000, comments = "keepalive"))
            }
        }.isSuccess
    }
}

internal class OperationWaiters {
    private data class Entry(
        val userId: UserId,
        val deviceId: DeviceId,
        val deferred: CompletableDeferred<OperationResultEnvelope>,
        var completed: Boolean = false,
    )

    private val mutex = Mutex()
    private val entries = mutableMapOf<OperationId, Entry>()
    private data class Completed(
        val userId: UserId,
        val deviceId: DeviceId,
        val expiresAtMillis: Long,
    )
    private val completed = mutableMapOf<OperationId, Completed>()

    suspend fun register(
        operationId: OperationId,
        userId: UserId,
        deviceId: DeviceId,
    ): CompletableDeferred<OperationResultEnvelope>? = mutex.withLock {
        removeExpiredCompleted()
        if (operationId in entries || operationId in completed) return@withLock null
        CompletableDeferred<OperationResultEnvelope>().also {
            entries[operationId] = Entry(userId, deviceId, it)
        }
    }

    suspend fun complete(
        userId: UserId,
        deviceId: DeviceId,
        result: OperationResultEnvelope,
    ): ResultAcceptance = mutex.withLock {
        removeExpiredCompleted()
        val entry = entries[result.operationId]
            ?: return@withLock if (
                completed[result.operationId]?.let { it.userId == userId && it.deviceId == deviceId } == true
            ) {
                ResultAcceptance.DUPLICATE
            } else {
                ResultAcceptance.UNKNOWN
            }
        if (entry.userId != userId || entry.deviceId != deviceId) return@withLock ResultAcceptance.UNKNOWN
        if (entry.completed) return@withLock ResultAcceptance.DUPLICATE
        entry.completed = true
        entry.deferred.complete(result)
        ResultAcceptance.ACCEPTED
    }

    suspend fun remove(operationId: OperationId) {
        mutex.withLock {
            val entry = entries.remove(operationId) ?: return@withLock
            if (entry.completed) {
                completed[operationId] = Completed(
                    entry.userId,
                    entry.deviceId,
                    System.currentTimeMillis() + COMPLETED_RETENTION_MILLIS,
                )
            } else {
                entry.deferred.cancel()
            }
        }
    }

    private fun removeExpiredCompleted() {
        val now = System.currentTimeMillis()
        completed.entries.removeIf { it.value.expiresAtMillis <= now }
    }

    companion object {
        private const val COMPLETED_RETENTION_MILLIS = 60_000L
    }
}

internal enum class ResultAcceptance { ACCEPTED, DUPLICATE, UNKNOWN }
