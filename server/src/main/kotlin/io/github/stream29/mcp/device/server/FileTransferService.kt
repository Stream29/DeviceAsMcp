package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.CancelFileTransferResult
import io.github.stream29.mcp.device.protocol.FileManifest
import io.github.stream29.mcp.device.protocol.FileTransferPlan
import io.github.stream29.mcp.device.protocol.FileTransferRecord
import io.github.stream29.mcp.device.protocol.FileTransferStatus
import io.github.stream29.mcp.device.protocol.FileTransferSummary
import io.github.stream29.mcp.device.protocol.LaunchFileTransferRequest
import io.github.stream29.mcp.device.protocol.LaunchFileTransferResult
import io.github.stream29.mcp.device.protocol.OperationPayload
import io.github.stream29.mcp.device.protocol.OperationResult
import io.github.stream29.mcp.device.protocol.OperationResultPayload
import io.github.stream29.mcp.device.protocol.TransferId
import io.github.stream29.mcp.device.protocol.UserId
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class FileTransferService(
    private val instanceId: io.github.stream29.mcp.device.protocol.InstanceId,
    private val accounts: AccountStore,
    private val routing: RoutingStore,
    private val operations: OperationService,
    private val relay: FileRelayRegistry,
) {
    suspend fun launch(userId: UserId, request: LaunchFileTransferRequest): Result<LaunchFileTransferResult> = runCatching {
        require(accounts.deviceUser(request.sourceDeviceId) == userId) { "Source device is not owned by the caller" }
        require(accounts.deviceUser(request.destinationDeviceId) == userId) { "Destination device is not owned by the caller" }
        val transferId = TransferId(UUID.randomUUID().toString())

        val (sourceResult, destinationResult) = try {
            coroutineScope {
                val source = async {
                    operations.invoke(
                        userId,
                        request.sourceDeviceId,
                        OperationPayload.PrepareFileSource(
                            transferId = transferId,
                            sourcePath = request.sourcePath,
                            relayInstanceId = instanceId,
                        ),
                    ).requireSuccess<OperationResultPayload.FilePreflight>("Source preflight failed")
                }
                val destination = async {
                    operations.invoke(
                        userId,
                        request.destinationDeviceId,
                        OperationPayload.PrepareFileDestination(
                            transferId = transferId,
                            destinationPath = request.destinationPath,
                            relayInstanceId = instanceId,
                        ),
                    ).requireSuccess<OperationResultPayload.FilePreflight>("Destination preflight failed")
                }
                source.await() to destination.await()
            }
        } catch (failure: Throwable) {
            cancelDaemonState(userId, request, transferId)
            throw failure
        }
        require(sourceResult.accepted) { "Source preflight was rejected" }
        require(destinationResult.accepted) { "Destination preflight was rejected" }

        val record = FileTransferRecord(
            transferId = transferId,
            userId = userId,
            sourceDeviceId = request.sourceDeviceId,
            sourcePath = request.sourcePath,
            destinationDeviceId = request.destinationDeviceId,
            destinationPath = request.destinationPath,
            relayInstanceId = instanceId,
        )
        check(routing.createTransfer(record)) { "Transfer ID collision" }

        try {
            routing.updateTransfer(transferId, FileTransferStatus.RUNNING)
            coroutineScope {
                launch {
                    operations.invoke(
                        userId,
                        request.destinationDeviceId,
                        OperationPayload.StartFileDestination(transferId, instanceId),
                    ).requireSuccess<OperationResultPayload.Acknowledged>("Destination start failed")
                }
                launch {
                    operations.invoke(
                        userId,
                        request.sourceDeviceId,
                        OperationPayload.StartFileSource(transferId, instanceId),
                    ).requireSuccess<OperationResultPayload.Acknowledged>("Source start failed")
                }
            }
        } catch (failure: Throwable) {
            routing.updateTransfer(
                transferId,
                FileTransferStatus.FAILED,
                io.github.stream29.mcp.device.protocol.OperationErrorCode.INTERNAL_ERROR,
                failure.message ?: "Transfer start failed",
            )
            relay.failTransfer(transferId, failure.message ?: "Transfer start failed")
            cancelDaemonState(userId, request, transferId)
            throw failure
        }
        LaunchFileTransferResult(transferId)
    }

    suspend fun status(userId: UserId, transferId: TransferId): FileTransferSummary {
        val record = routing.transfer(transferId)
        if (record == null || record.userId != userId) {
            return FileTransferSummary(transferId, FileTransferStatus.ABSENT, 0)
        }
        return FileTransferSummary(
            transferId = transferId,
            status = record.status,
            successfulFiles = record.successfulFiles,
            errorCode = record.errorCode,
            message = record.message,
        )
    }

    suspend fun cancel(userId: UserId, transferId: TransferId): CancelFileTransferResult? {
        val record = routing.transfer(transferId) ?: return null
        if (record.userId != userId) return null
        val stopped = withTimeoutOrNull(CANCEL_WAIT_MILLIS) {
            coroutineScope {
                setOf(record.sourceDeviceId, record.destinationDeviceId)
                    .map { deviceId ->
                        async {
                            runCatching {
                                operations.invoke(
                                    userId,
                                    deviceId,
                                    OperationPayload.CancelFileTransfer(transferId),
                                ).requireSuccess<OperationResultPayload.Acknowledged>(
                                    "Device did not stop the file transfer",
                                ).accepted
                            }.getOrDefault(false)
                        }
                    }
                    .awaitAll()
                    .all { it }
            }
        } == true
        if (!stopped) return CancelFileTransferResult(transferId, false)

        relay.failTransfer(transferId, "Transfer was cancelled")
        routing.removeTransfer(transferId)
        return CancelFileTransferResult(transferId, true)
    }

    suspend fun manifest(
        deviceId: io.github.stream29.mcp.device.protocol.DeviceId,
        transferId: TransferId,
    ): FileManifest? {
        val transfer = routing.transfer(transferId) ?: return null
        if (transfer.sourceDeviceId != deviceId && transfer.destinationDeviceId != deviceId) return null
        return routing.transferManifest(transferId)
    }

    suspend fun destinationManifest(
        deviceId: io.github.stream29.mcp.device.protocol.DeviceId,
        transferId: TransferId,
    ): io.github.stream29.mcp.device.protocol.FileManifest? {
        val transfer = routing.transfer(transferId) ?: return null
        if (transfer.destinationDeviceId != deviceId || transfer.relayInstanceId != instanceId) return null
        return routing.transferManifest(transferId)
    }

    suspend fun sourcePlan(
        deviceId: io.github.stream29.mcp.device.protocol.DeviceId,
        transferId: TransferId,
    ): FileTransferPlan? {
        val transfer = routing.transfer(transferId) ?: return null
        if (
            transfer.sourceDeviceId != deviceId ||
            transfer.relayInstanceId != instanceId ||
            transfer.status != FileTransferStatus.RUNNING
        ) {
            return null
        }
        return routing.transferPlan(transferId)
    }

    suspend fun putPlan(
        deviceId: io.github.stream29.mcp.device.protocol.DeviceId,
        transferId: TransferId,
        plan: FileTransferPlan,
    ): Boolean {
        val transfer = routing.transfer(transferId) ?: return false
        if (
            transfer.destinationDeviceId != deviceId ||
            transfer.relayInstanceId != instanceId ||
            transfer.status != FileTransferStatus.RUNNING
        ) {
            return false
        }
        val manifest = routing.transferManifest(transferId) ?: return false
        val manifestFiles = manifest.entries
            .asSequence()
            .filter { it.type == io.github.stream29.mcp.device.protocol.ManifestEntryType.FILE }
            .map { it.relativePath }
            .toSet()
        if (plan.acceptedFiles.any { it !in manifestFiles }) return false
        if (plan.acceptedFiles.size + plan.skippedEntries > manifest.entries.size) return false
        routing.transferPlan(transferId)?.let { return it == plan }
        return routing.putTransferPlan(transferId, plan)
    }

    suspend fun putManifest(
        deviceId: io.github.stream29.mcp.device.protocol.DeviceId,
        transferId: TransferId,
        manifest: io.github.stream29.mcp.device.protocol.FileManifest,
    ): Boolean {
        val transfer = routing.transfer(transferId) ?: return false
        if (
            transfer.sourceDeviceId != deviceId ||
            transfer.relayInstanceId != instanceId ||
            transfer.status != FileTransferStatus.RUNNING
        ) {
            return false
        }
        routing.transferManifest(transferId)?.let { return it == manifest }
        return routing.putTransferManifest(transferId, manifest)
    }

    suspend fun markFileSuccess(
        transferId: TransferId,
        relativePath: String,
    ): Boolean {
        val manifest = routing.transferManifest(transferId) ?: return false
        val plan = routing.transferPlan(transferId) ?: return false
        if (relativePath !in plan.acceptedFiles) return false
        if (manifest.entries.none {
                it.relativePath == relativePath &&
                    it.type == io.github.stream29.mcp.device.protocol.ManifestEntryType.FILE
            }
        ) {
            return false
        }
        return routing.markTransferFileSuccess(transferId, relativePath)
    }

    suspend fun finish(
        deviceId: io.github.stream29.mcp.device.protocol.DeviceId,
        transferId: TransferId,
        successfulFiles: Int,
    ): Boolean {
        val transfer = routing.transfer(transferId) ?: return false
        if (transfer.destinationDeviceId != deviceId || transfer.relayInstanceId != instanceId) return false
        require(successfulFiles >= 0) { "successfulFiles cannot be negative" }
        val current = routing.transfer(transferId) ?: return false
        val plan = routing.transferPlan(transferId) ?: return false
        if (current.successfulFiles != successfulFiles || successfulFiles != plan.acceptedFiles.size) return false
        routing.removeTransfer(transferId)
        return true
    }

    suspend fun fail(
        deviceId: io.github.stream29.mcp.device.protocol.DeviceId,
        transferId: TransferId,
        errorCode: io.github.stream29.mcp.device.protocol.OperationErrorCode,
        message: String,
    ): Boolean {
        val transfer = requireParticipant(transferId, deviceId) ?: return false
        if (transfer.relayInstanceId != instanceId) return false
        return routing.updateTransfer(transferId, FileTransferStatus.FAILED, errorCode, message)
    }

    suspend fun requireParticipant(
        transferId: TransferId,
        deviceId: io.github.stream29.mcp.device.protocol.DeviceId,
    ): FileTransferRecord? = routing.transfer(transferId)?.takeIf {
        it.sourceDeviceId == deviceId || it.destinationDeviceId == deviceId
    }

    suspend fun failIfCoordinatorLost(
        deviceId: io.github.stream29.mcp.device.protocol.DeviceId,
        transferId: TransferId,
    ): Boolean {
        val transfer = requireParticipant(transferId, deviceId) ?: return false
        if (transfer.status != FileTransferStatus.RUNNING) return false
        return routing.failRunningTransfer(
            transferId,
            io.github.stream29.mcp.device.protocol.OperationErrorCode.SERVER_INSTANCE_LOST,
            "The file relay server instance became unavailable",
        )
    }

    private suspend fun cancelDaemonState(
        userId: UserId,
        request: LaunchFileTransferRequest,
        transferId: TransferId,
    ) {
        kotlinx.coroutines.withTimeoutOrNull(CANCEL_WAIT_MILLIS) {
            coroutineScope {
                launch {
                    runCatching {
                        operations.invoke(
                            userId,
                            request.sourceDeviceId,
                            OperationPayload.CancelFileTransfer(transferId),
                        )
                    }
                }
                launch {
                    runCatching {
                        operations.invoke(
                            userId,
                            request.destinationDeviceId,
                            OperationPayload.CancelFileTransfer(transferId),
                        )
                    }
                }
            }
        }
    }

    private inline fun <reified T : OperationResultPayload> io.github.stream29.mcp.device.protocol.OperationResultEnvelope.requireSuccess(
        context: String,
    ): T = when (val result = result) {
        is OperationResult.Success -> (result.payload as? T)
            ?: error("$context: unexpected result ${result.payload::class.simpleName}")
        is OperationResult.Failure -> error("$context: ${result.errorCode}: ${result.message}")
    }

    companion object {
        private const val CANCEL_WAIT_MILLIS = 5_000L
    }
}
