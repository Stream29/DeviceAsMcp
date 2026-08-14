package io.github.stream29.mcp.device.server

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DeliverCallback
import com.rabbitmq.client.RpcClient
import com.rabbitmq.client.RpcClientParams
import io.github.stream29.mcp.device.protocol.InstanceId
import io.github.stream29.mcp.device.protocol.InstanceRpcMethod
import io.github.stream29.mcp.device.protocol.InstanceRpcRequest
import io.github.stream29.mcp.device.protocol.InstanceRpcResponse
import io.github.stream29.mcp.device.protocol.InstanceRpcTopology
import io.github.stream29.mcp.device.protocol.OperationErrorCode
import io.github.stream29.mcp.device.protocol.ProtocolJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal interface InstanceRpc : AutoCloseable {
    suspend fun isReady(): Boolean

    suspend fun call(
        target: InstanceId,
        request: InstanceRpcRequest,
        deadlineEpochMillis: Long,
        timeoutMillis: Long,
    ): InstanceRpcResponse

    suspend fun publish(
        target: InstanceId,
        request: InstanceRpcRequest,
        deadlineEpochMillis: Long,
    )
}

internal class NoopInstanceRpc : InstanceRpc {
    override suspend fun isReady(): Boolean = true

    override suspend fun call(
        target: InstanceId,
        request: InstanceRpcRequest,
        deadlineEpochMillis: Long,
        timeoutMillis: Long,
    ): InstanceRpcResponse =
        InstanceRpcResponse.Rejected(OperationErrorCode.DEVICE_OFFLINE, "Cross-instance RPC is disabled")

    override suspend fun publish(
        target: InstanceId,
        request: InstanceRpcRequest,
        deadlineEpochMillis: Long,
    ) = Unit

    override fun close() = Unit
}

internal class RabbitInstanceRpc(
    rabbitMqUrl: String,
    private val instanceId: InstanceId,
    private val handler: suspend (InstanceRpcRequest) -> InstanceRpcResponse,
) : InstanceRpc {
    private val connection: Connection
    private val consumerChannel: Channel
    private val rpcClients = ConcurrentHashMap<InstanceId, TargetRpcClient>()

    init {
        val factory = ConnectionFactory().apply {
            setUri(rabbitMqUrl)
            isAutomaticRecoveryEnabled = true
        }
        connection = factory.newConnection("device-as-mcp-${instanceId.value}")
        consumerChannel = connection.createChannel()
        consumerChannel.exchangeDeclare(InstanceRpcTopology.EXCHANGE, "direct", true)
        val queue = InstanceRpcTopology.queue(instanceId)
        consumerChannel.queueDeclare(queue, false, true, true, emptyMap())
        consumerChannel.queueBind(queue, InstanceRpcTopology.EXCHANGE, InstanceRpcTopology.routingKey(instanceId))
        consumerChannel.basicQos(32)
        consumerChannel.basicConsume(queue, false, DeliverCallback { _, delivery ->
            val response = runCatching {
                require(delivery.body.size <= MAX_RPC_BODY_BYTES) { "RPC request body is too large" }
                val request = ProtocolJson.decodeFromString<InstanceRpcRequest>(String(delivery.body, StandardCharsets.UTF_8))
                val headerDeadline = (delivery.properties.headers?.get("deadline-epoch-millis") as? Number)?.toLong()
                if (delivery.properties.type != method(request) || headerDeadline == null) {
                    InstanceRpcResponse.Rejected(OperationErrorCode.INVALID_REQUEST, "RPC metadata mismatch")
                } else if (headerDeadline <= System.currentTimeMillis()) {
                    InstanceRpcResponse.Rejected(OperationErrorCode.OPERATION_TIMEOUT, "RPC deadline elapsed")
                } else {
                    runBlocking { handler(request) }
                }
            }.getOrElse {
                InstanceRpcResponse.Rejected(OperationErrorCode.INTERNAL_ERROR, it.message ?: "RPC handler failed")
            }
            val replyTo = delivery.properties.replyTo
            if (replyTo != null) {
                runCatching {
                    val properties = AMQP.BasicProperties.Builder()
                        .correlationId(delivery.properties.correlationId)
                        .build()
                    consumerChannel.basicPublish(
                        "",
                        replyTo,
                        properties,
                        ProtocolJson.encodeToString(response).toByteArray(),
                    )
                }
            }
            consumerChannel.basicAck(delivery.envelope.deliveryTag, false)
        }, { })
    }

    override suspend fun call(
        target: InstanceId,
        request: InstanceRpcRequest,
        deadlineEpochMillis: Long,
        timeoutMillis: Long,
    ): InstanceRpcResponse =
        withContext(Dispatchers.IO) {
            try {
                val remaining = (deadlineEpochMillis - System.currentTimeMillis()).coerceAtMost(timeoutMillis)
                if (remaining <= 0) {
                    return@withContext InstanceRpcResponse.Rejected(
                        OperationErrorCode.OPERATION_TIMEOUT,
                        "Cross-instance RPC deadline elapsed",
                    )
                }
                val targetClient = rpcClients.computeIfAbsent(target) { createRpcClient(it) }
                targetClient.mutex.withLock {
                    val callRemaining = (deadlineEpochMillis - System.currentTimeMillis()).coerceAtMost(timeoutMillis)
                    if (callRemaining <= 0) {
                        return@withLock InstanceRpcResponse.Rejected(
                            OperationErrorCode.OPERATION_TIMEOUT,
                            "Cross-instance RPC deadline elapsed while waiting for the target channel",
                        )
                    }
                    val properties = properties(request, deadlineEpochMillis, callRemaining, reply = true)
                    val response = targetClient.client.doCall(
                        properties,
                        ProtocolJson.encodeToString(request).toByteArray(),
                        callRemaining.toInt(),
                    )
                    ProtocolJson.decodeFromString<InstanceRpcResponse>(
                        String(response.body, StandardCharsets.UTF_8),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                InstanceRpcResponse.Rejected(
                    OperationErrorCode.DEVICE_OWNER_STALE,
                    failure.message ?: "Cross-instance RPC failed",
                )
            }
        }

    override suspend fun publish(
        target: InstanceId,
        request: InstanceRpcRequest,
        deadlineEpochMillis: Long,
    ) = withContext(Dispatchers.IO) {
        val remaining = deadlineEpochMillis - System.currentTimeMillis()
        if (remaining <= 0) return@withContext
        connection.createChannel().use { channel ->
            channel.confirmSelect()
            val returnedRoutingKey = AtomicReference<String?>()
            channel.addReturnListener { returned -> returnedRoutingKey.set(returned.routingKey) }
            channel.basicPublish(
                InstanceRpcTopology.EXCHANGE,
                InstanceRpcTopology.routingKey(target),
                true,
                properties(request, deadlineEpochMillis, remaining, reply = false),
                ProtocolJson.encodeToString(request).toByteArray(),
            )
            channel.waitForConfirmsOrDie(remaining)
            check(returnedRoutingKey.get() == null) {
                "Instance RPC target is unavailable: ${returnedRoutingKey.get()}"
            }
        }
    }

    override suspend fun isReady(): Boolean = connection.isOpen && consumerChannel.isOpen

    override fun close() {
        rpcClients.values.forEach { runCatching { it.client.close() } }
        runCatching { consumerChannel.close() }
        runCatching { connection.close() }
    }

    private fun createRpcClient(target: InstanceId): TargetRpcClient = TargetRpcClient(
        ConfirmingRpcClient(
            RpcClientParams().channel(connection.createChannel().apply {
            confirmSelect()
            })
                .exchange(InstanceRpcTopology.EXCHANGE)
                .routingKey(InstanceRpcTopology.routingKey(target))
                .replyTo("amq.rabbitmq.reply-to")
                .useMandatory(),
        ),
    )

    private fun properties(
        request: InstanceRpcRequest,
        deadlineEpochMillis: Long,
        remaining: Long,
        reply: Boolean,
    ): AMQP.BasicProperties =
        AMQP.BasicProperties.Builder()
            .type(method(request))
            .contentType("application/json")
            .deliveryMode(1)
            .expiration(remaining.toString())
            .headers(mapOf("deadline-epoch-millis" to deadlineEpochMillis))
            .apply { if (!reply) replyTo(null) }
            .build()

    private fun method(request: InstanceRpcRequest): String = when (request) {
        is InstanceRpcRequest.DispatchOperation -> InstanceRpcMethod.DISPATCH_OPERATION
        is InstanceRpcRequest.ForwardOperationResult -> InstanceRpcMethod.FORWARD_OPERATION_RESULT
        is InstanceRpcRequest.PrepareFileSource -> InstanceRpcMethod.PREPARE_FILE_SOURCE
        is InstanceRpcRequest.PrepareFileDestination -> InstanceRpcMethod.PREPARE_FILE_DESTINATION
        is InstanceRpcRequest.CancelFileTransfer -> InstanceRpcMethod.CANCEL_FILE_TRANSFER
        is InstanceRpcRequest.CancelOperation -> InstanceRpcMethod.CANCEL_OPERATION
    }

    private data class TargetRpcClient(
        val client: RpcClient,
        val mutex: Mutex = Mutex(),
    )

    private class ConfirmingRpcClient(params: RpcClientParams) : RpcClient(params) {
        override fun publish(props: AMQP.BasicProperties, message: ByteArray) {
            super.publish(props, message)
            val timeout = props.expiration?.toLongOrNull()?.coerceAtLeast(1) ?: DEFAULT_CONFIRM_TIMEOUT_MILLIS
            channel.waitForConfirmsOrDie(timeout)
        }

        companion object {
            private const val DEFAULT_CONFIRM_TIMEOUT_MILLIS = 5_000L
        }
    }

    companion object {
        private const val MAX_RPC_BODY_BYTES = 1024 * 1024
    }
}
