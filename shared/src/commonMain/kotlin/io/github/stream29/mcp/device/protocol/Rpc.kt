package io.github.stream29.mcp.device.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object InstanceRpcTopology {
    const val EXCHANGE = "device_as_mcp.instance_rpc.v1"
    const val QUEUE_PREFIX = "device_as_mcp.instance_rpc."
    const val ROUTING_KEY_PREFIX = "instance."

    fun queue(instanceId: InstanceId): String = QUEUE_PREFIX + instanceId.value
    fun routingKey(instanceId: InstanceId): String = ROUTING_KEY_PREFIX + instanceId.value
}

object InstanceRpcMethod {
    const val DISPATCH_OPERATION = "dispatch-operation.v1"
    const val FORWARD_OPERATION_RESULT = "forward-operation-result.v1"
    const val PREPARE_FILE_SOURCE = "prepare-file-source.v1"
    const val PREPARE_FILE_DESTINATION = "prepare-file-destination.v1"
    const val CANCEL_FILE_TRANSFER = "cancel-file-transfer.v1"
    const val CANCEL_OPERATION = "cancel-operation.v1"
}

@Serializable
sealed interface InstanceRpcRequest {
    @Serializable
    @SerialName(InstanceRpcMethod.DISPATCH_OPERATION)
    data class DispatchOperation(
        val originInstanceId: InstanceId,
        val connectionId: ConnectionId,
        val operation: OperationEnvelope,
    ) : InstanceRpcRequest

    @Serializable
    @SerialName(InstanceRpcMethod.FORWARD_OPERATION_RESULT)
    data class ForwardOperationResult(
        val userId: UserId,
        val deviceId: DeviceId,
        val result: OperationResultEnvelope,
    ) : InstanceRpcRequest

    @Serializable
    @SerialName(InstanceRpcMethod.PREPARE_FILE_SOURCE)
    data class PrepareFileSource(
        val connectionId: ConnectionId,
        val operation: OperationEnvelope,
    ) : InstanceRpcRequest

    @Serializable
    @SerialName(InstanceRpcMethod.PREPARE_FILE_DESTINATION)
    data class PrepareFileDestination(
        val connectionId: ConnectionId,
        val operation: OperationEnvelope,
    ) : InstanceRpcRequest

    @Serializable
    @SerialName(InstanceRpcMethod.CANCEL_FILE_TRANSFER)
    data class CancelFileTransfer(
        val connectionId: ConnectionId,
        val operation: OperationEnvelope,
    ) : InstanceRpcRequest

    @Serializable
    @SerialName(InstanceRpcMethod.CANCEL_OPERATION)
    data class CancelOperation(
        val connectionId: ConnectionId,
        val operation: OperationEnvelope,
    ) : InstanceRpcRequest
}

@Serializable
sealed interface InstanceRpcResponse {
    @Serializable
    @SerialName("accepted")
    data object Accepted : InstanceRpcResponse

    @Serializable
    @SerialName("operation_result")
    data class Result(val result: OperationResultEnvelope) : InstanceRpcResponse

    @Serializable
    @SerialName("duplicate")
    data object Duplicate : InstanceRpcResponse

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val errorCode: OperationErrorCode,
        val message: String,
    ) : InstanceRpcResponse
}
