package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.ConnectionId
import io.github.stream29.mcp.device.protocol.DeviceId
import io.github.stream29.mcp.device.protocol.InstanceId
import io.github.stream29.mcp.device.protocol.InstanceRpcRequest
import io.github.stream29.mcp.device.protocol.InstanceRpcResponse
import io.github.stream29.mcp.device.protocol.OperationEnvelope
import io.github.stream29.mcp.device.protocol.OperationErrorCode
import io.github.stream29.mcp.device.protocol.OperationId
import io.github.stream29.mcp.device.protocol.OperationPayload
import io.github.stream29.mcp.device.protocol.OperationResult
import io.github.stream29.mcp.device.protocol.OperationResultEnvelope
import io.github.stream29.mcp.device.protocol.UserId
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal class OperationService(
    private val instanceId: InstanceId,
    private val routingStore: RoutingStore,
    private val connections: DeviceConnectionRegistry,
    private val waiters: OperationWaiters,
    private var instanceRpc: InstanceRpc,
) {
    fun attachRpc(rpc: InstanceRpc) {
        instanceRpc = rpc
    }

    suspend fun invoke(userId: UserId, deviceId: DeviceId, payload: OperationPayload): OperationResultEnvelope {
        val operation = OperationEnvelope(
            operationId = OperationId(UUID.randomUUID().toString()),
            deviceId = deviceId,
            payload = payload,
        )
        val deadline = System.currentTimeMillis() + OPERATION_TOTAL_TIMEOUT_MILLIS
        val waiter = waiters.register(operation.operationId, userId, deviceId)
            ?: return failure(operation.operationId, OperationErrorCode.INTERNAL_ERROR, "Operation ID collision")
        routingStore.putOperationOrigin(operation.operationId, instanceId, deadline)
        try {
            val firstDispatch = dispatch(operation, deadline)
            if (firstDispatch is InstanceRpcResponse.Rejected) {
                return failure(operation.operationId, firstDispatch.errorCode, firstDispatch.message)
            }
            if (firstDispatch !is InstanceRpcResponse.Accepted) {
                return failure(operation.operationId, OperationErrorCode.INTERNAL_ERROR, "Unexpected dispatch response")
            }
            withTimeoutOrNull(OPERATION_ATTEMPT_TIMEOUT_MILLIS) { waiter.await() }?.let { return it }

            val secondDispatch = dispatch(operation, deadline)
            if (secondDispatch is InstanceRpcResponse.Rejected) {
                return failure(operation.operationId, secondDispatch.errorCode, secondDispatch.message)
            }
            if (secondDispatch !is InstanceRpcResponse.Accepted) {
                return failure(operation.operationId, OperationErrorCode.INTERNAL_ERROR, "Unexpected dispatch response")
            }
            return withTimeoutOrNull(OPERATION_ATTEMPT_TIMEOUT_MILLIS) { waiter.await() }
                ?: failure(operation.operationId, OperationErrorCode.OPERATION_TIMEOUT, "Device operation timed out")
        } finally {
            if (!waiter.isCompleted) {
                runCatching { cancel(deviceId, operation.operationId) }
            }
            waiters.remove(operation.operationId)
        }
    }

    suspend fun acceptDaemonResult(userId: UserId, deviceId: DeviceId, result: OperationResultEnvelope): ResultAcceptance {
        val owner = routingStore.deviceOwner(deviceId) ?: return ResultAcceptance.UNKNOWN
        if (owner.instanceId != instanceId) {
            return forwardResult(owner.instanceId, userId, deviceId, result)
        }
        return routeResultToOrigin(userId, deviceId, result)
    }

    private suspend fun routeResultToOrigin(
        userId: UserId,
        deviceId: DeviceId,
        result: OperationResultEnvelope,
    ): ResultAcceptance {
        val local = waiters.complete(userId, deviceId, result)
        if (local != ResultAcceptance.UNKNOWN) return local
        val origin = routingStore.operationOrigin(result.operationId) ?: return ResultAcceptance.UNKNOWN
        if (origin == instanceId) return ResultAcceptance.UNKNOWN
        return forwardResult(origin, userId, deviceId, result)
    }

    private suspend fun forwardResult(
        target: InstanceId,
        userId: UserId,
        deviceId: DeviceId,
        result: OperationResultEnvelope,
    ): ResultAcceptance {
        val response = instanceRpc.call(
            target,
            InstanceRpcRequest.ForwardOperationResult(
                userId = userId,
                deviceId = deviceId,
                result = result,
            ),
            System.currentTimeMillis() + RESULT_FORWARD_TIMEOUT_MILLIS,
            RESULT_FORWARD_TIMEOUT_MILLIS,
        )
        return when (response) {
            InstanceRpcResponse.Accepted -> ResultAcceptance.ACCEPTED
            InstanceRpcResponse.Duplicate -> ResultAcceptance.DUPLICATE
            else -> ResultAcceptance.UNKNOWN
        }
    }

    suspend fun cancel(deviceId: DeviceId, targetOperationId: OperationId) {
        val owner = routingStore.deviceOwner(deviceId) ?: return
        val cancel = OperationEnvelope(
            operationId = OperationId(UUID.randomUUID().toString()),
            deviceId = deviceId,
            payload = OperationPayload.CancelOperation(targetOperationId),
        )
        if (owner.instanceId == instanceId) {
            connections.send(deviceId, owner.connectionId, cancel)
        } else {
            instanceRpc.publish(
                owner.instanceId,
                InstanceRpcRequest.CancelOperation(owner.connectionId, cancel),
                System.currentTimeMillis() + DISPATCH_TIMEOUT_MILLIS,
            )
        }
    }

    suspend fun handleRpc(request: InstanceRpcRequest): InstanceRpcResponse = when (request) {
        is InstanceRpcRequest.DispatchOperation -> handleDispatch(request.connectionId, request.operation)
        is InstanceRpcRequest.ForwardOperationResult -> {
            val local = waiters.complete(request.userId, request.deviceId, request.result)
            val routed = if (local != ResultAcceptance.UNKNOWN) {
                local
            } else if (routingStore.deviceOwner(request.deviceId)?.instanceId == instanceId) {
                routeResultToOrigin(request.userId, request.deviceId, request.result)
            } else {
                ResultAcceptance.UNKNOWN
            }
            when (routed) {
                ResultAcceptance.ACCEPTED -> InstanceRpcResponse.Accepted
                ResultAcceptance.DUPLICATE -> InstanceRpcResponse.Duplicate
                ResultAcceptance.UNKNOWN ->
                    InstanceRpcResponse.Rejected(OperationErrorCode.INVALID_REQUEST, "Unknown operation")
            }
        }
        is InstanceRpcRequest.PrepareFileSource -> handleDispatch(request.connectionId, request.operation)
        is InstanceRpcRequest.PrepareFileDestination -> handleDispatch(request.connectionId, request.operation)
        is InstanceRpcRequest.CancelFileTransfer -> handleDispatch(request.connectionId, request.operation)
        is InstanceRpcRequest.CancelOperation -> handleDispatch(request.connectionId, request.operation)
    }

    private suspend fun dispatch(operation: OperationEnvelope, deadline: Long): InstanceRpcResponse {
        val owner = routingStore.deviceOwner(operation.deviceId)
            ?: return InstanceRpcResponse.Rejected(OperationErrorCode.DEVICE_OFFLINE, "Device is offline")
        return if (owner.instanceId == instanceId) {
            handleDispatch(owner.connectionId, operation)
        } else {
            instanceRpc.call(
                owner.instanceId,
                crossInstanceRequest(owner.connectionId, operation),
                deadline,
                DISPATCH_TIMEOUT_MILLIS,
            )
        }
    }

    private fun crossInstanceRequest(
        connectionId: ConnectionId,
        operation: OperationEnvelope,
    ): InstanceRpcRequest = when (operation.payload) {
        is OperationPayload.PrepareFileSource ->
            InstanceRpcRequest.PrepareFileSource(connectionId, operation)
        is OperationPayload.PrepareFileDestination ->
            InstanceRpcRequest.PrepareFileDestination(connectionId, operation)
        is OperationPayload.CancelFileTransfer ->
            InstanceRpcRequest.CancelFileTransfer(connectionId, operation)
        else ->
            InstanceRpcRequest.DispatchOperation(instanceId, connectionId, operation)
    }

    private suspend fun handleDispatch(connectionId: ConnectionId, operation: OperationEnvelope): InstanceRpcResponse {
        val current = routingStore.deviceOwner(operation.deviceId)
            ?: return InstanceRpcResponse.Rejected(OperationErrorCode.DEVICE_OFFLINE, "Device is offline")
        if (current.instanceId != instanceId || current.connectionId != connectionId) {
            return InstanceRpcResponse.Rejected(OperationErrorCode.DEVICE_OWNER_STALE, "Device owner changed")
        }
        return if (connections.send(operation.deviceId, connectionId, operation)) {
            InstanceRpcResponse.Accepted
        } else {
            InstanceRpcResponse.Rejected(OperationErrorCode.DEVICE_OWNER_STALE, "Local device connection not found")
        }
    }

    private fun failure(id: OperationId, code: OperationErrorCode, message: String) =
        OperationResultEnvelope(id, OperationResult.Failure(code, message))

    companion object {
        const val OWNER_TTL_MILLIS = 30_000L
        const val OWNER_RENEW_MILLIS = 10_000L
        const val DISPATCH_TIMEOUT_MILLIS = 5_000L
        const val OPERATION_ATTEMPT_TIMEOUT_MILLIS = 10_000L
        const val OPERATION_TOTAL_TIMEOUT_MILLIS = 25_000L
        const val RESULT_FORWARD_TIMEOUT_MILLIS = 5_000L
    }
}
