package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.FileIntegrity
import io.github.stream29.mcp.device.protocol.TransferId
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal data class RelayFileKey(
    val transferId: TransferId,
    val relativePath: String,
    val attempt: Int,
)

internal data class RelayUpload(
    val channel: ByteReadChannel,
    val expectedByteCount: Long,
    val sourceIntegrity: CompletableDeferred<FileIntegrity> = CompletableDeferred(),
    val completion: CompletableDeferred<RelayCompletion> = CompletableDeferred(),
)

internal sealed interface RelayCompletion {
    data object Verified : RelayCompletion
    data class Rejected(val message: String) : RelayCompletion
}

internal class FileRelayRegistry : AutoCloseable {
    private val mutex = Mutex()
    private val pending = mutableMapOf<RelayFileKey, CompletableDeferred<RelayUpload>>()
    private val uploads = mutableMapOf<RelayFileKey, RelayUpload>()
    private var closed = false

    suspend fun publish(key: RelayFileKey, upload: RelayUpload): Boolean = mutex.withLock {
        if (closed || key in uploads) return@withLock false
        uploads[key] = upload
        pending.remove(key)?.complete(upload)
        true
    }

    suspend fun await(key: RelayFileKey, timeoutMillis: Long): RelayUpload? {
        val waiter = mutex.withLock {
            if (closed) return null
            uploads[key]?.let { return it }
            pending.getOrPut(key) { CompletableDeferred() }
        }
        return withTimeoutOrNull(timeoutMillis) { waiter.await() }.also { value ->
            if (value == null) {
                mutex.withLock {
                    if (pending[key] === waiter) pending.remove(key)
                }
            }
        }
    }

    suspend fun recordSourceIntegrity(
        key: RelayFileKey,
        integrity: FileIntegrity,
        uploadWaitMillis: Long = 0,
    ): Boolean {
        val upload = await(key, uploadWaitMillis) ?: return false
        if (integrity.byteCount != upload.expectedByteCount) {
            upload.sourceIntegrity.complete(integrity)
            return false
        }
        return upload.sourceIntegrity.complete(integrity)
    }

    suspend fun complete(
        key: RelayFileKey,
        destinationIntegrity: FileIntegrity,
        sourceWaitMillis: Long,
        acceptVerified: suspend () -> Boolean,
    ): RelayCompletion? {
        val upload = mutex.withLock { uploads[key] } ?: return null
        val sourceIntegrity = withTimeoutOrNull(sourceWaitMillis) {
            runCatching { upload.sourceIntegrity.await() }.getOrNull()
        }
        val integrityResult = when {
            sourceIntegrity == null ->
                RelayCompletion.Rejected("Source did not report content integrity")
            sourceIntegrity != destinationIntegrity ->
                RelayCompletion.Rejected(
                    "Integrity mismatch: source=$sourceIntegrity, destination=$destinationIntegrity",
                )
            destinationIntegrity.byteCount != upload.expectedByteCount ->
                RelayCompletion.Rejected("Transferred byte count differs from the manifest")
            else -> null
        }
        val result = integrityResult ?: if (runCatching { acceptVerified() }.getOrDefault(false)) {
            RelayCompletion.Verified
        } else {
            RelayCompletion.Rejected("Transfer state no longer accepts file progress")
        }
        val removed = mutex.withLock {
            pending.remove(key)
            if (uploads[key] === upload) {
                uploads.remove(key)
                true
            } else {
                false
            }
        }
        if (!removed) return null
        upload.completion.complete(result)
        return result
    }

    suspend fun failTransfer(transferId: TransferId, message: String) {
        val (affected, waiters) = mutex.withLock {
            val uploadKeys = uploads.keys.filter { it.transferId == transferId }
            val pendingKeys = pending.keys.filter { it.transferId == transferId }
            val values = uploadKeys.mapNotNull(uploads::remove)
            val pendingValues = pendingKeys.mapNotNull(pending::remove)
            values to pendingValues
        }
        waiters.forEach { it.cancel() }
        affected.forEach { it.completion.complete(RelayCompletion.Rejected(message)) }
        affected.forEach { it.sourceIntegrity.cancel() }
    }

    suspend fun remove(key: RelayFileKey, upload: RelayUpload) {
        val removed = mutex.withLock {
            val owned = uploads[key] === upload
            if (owned) uploads.remove(key)
            pending.remove(key)
            owned
        }
        if (removed) {
            upload.sourceIntegrity.cancel()
            upload.completion.complete(RelayCompletion.Rejected("Source content stream ended before verification"))
        }
    }

    override fun close() {
        val failure = RelayCompletion.Rejected("Relay server instance stopped")
        val (activeUploads, activeWaiters) = runBlocking {
            mutex.withLock {
                if (closed) return@withLock emptyList<RelayUpload>() to emptyList()
                closed = true
                val values = uploads.values.toList()
                val waiters = pending.values.toList()
                uploads.clear()
                pending.clear()
                values to waiters
            }
        }
        activeUploads.forEach {
            it.sourceIntegrity.cancel()
            it.completion.complete(failure)
        }
        activeWaiters.forEach { it.cancel() }
    }
}
