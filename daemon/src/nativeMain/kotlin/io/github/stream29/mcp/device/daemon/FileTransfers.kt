package io.github.stream29.mcp.device.daemon

import io.github.stream29.mcp.device.protocol.FileIntegrity
import io.github.stream29.mcp.device.protocol.FileManifest
import io.github.stream29.mcp.device.protocol.FileManifestEntry
import io.github.stream29.mcp.device.protocol.FileTransferContentRequest
import io.github.stream29.mcp.device.protocol.FileTransferFailureRequest
import io.github.stream29.mcp.device.protocol.FileTransferFinishRequest
import io.github.stream29.mcp.device.protocol.FileTransferManifestRequest
import io.github.stream29.mcp.device.protocol.FileTransferPlan
import io.github.stream29.mcp.device.protocol.FileTransferPlanRequest
import io.github.stream29.mcp.device.protocol.InstanceId
import io.github.stream29.mcp.device.protocol.MAX_MANIFEST_BYTES
import io.github.stream29.mcp.device.protocol.ManifestEntryType
import io.github.stream29.mcp.device.protocol.OperationErrorCode
import io.github.stream29.mcp.device.protocol.ProtocolJson
import io.github.stream29.mcp.device.protocol.ROOT_FILE_RELATIVE_PATH
import io.github.stream29.mcp.device.protocol.TransferId
import io.github.stream29.mcp.device.protocol.isSafeRelativePath
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import okio.HashingSink
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

internal class FileTransfers(
    private val config: DaemonConfig,
    private val client: HttpClient,
    private val scope: CoroutineScope,
) {
    private data class Source(
        val root: Path,
        val manifest: FileManifest,
        val relayInstanceId: InstanceId,
    )

    private data class Destination(
        val root: Path,
        val relayInstanceId: InstanceId,
        val caseSensitive: Boolean,
    )

    private data class TransferJobs(var source: Job? = null, var destination: Job? = null)

    private data class DestinationCandidate(
        val entry: FileManifestEntry,
        val target: Path,
    )

    private data class PreparedDestination(
        val plan: FileTransferPlan,
        val files: List<DestinationCandidate>,
    )

    private enum class TransferSide { SOURCE, DESTINATION }

    private val sources = mutableMapOf<TransferId, Source>()
    private val destinations = mutableMapOf<TransferId, Destination>()
    private val jobs = mutableMapOf<TransferId, TransferJobs>()
    private val pendingCoordinatorLosses = mutableSetOf<TransferId>()
    private val stateMutex = Mutex()

    suspend fun prepareSource(
        transferId: TransferId,
        rawPath: String,
        relayInstanceId: InstanceId,
    ) {
        val root = expandAbsolutePath(rawPath)
        val metadata = runCatching { systemFileSystem.metadata(root) }.getOrNull()
            ?: throw FileTransferException(OperationErrorCode.PATH_NOT_FOUND, "Source path does not exist")
        if (metadata.symlinkTarget != null) {
            throw FileTransferException(OperationErrorCode.PATH_NOT_READABLE, "Source links are not transferred")
        }
        if (metadata.isRegularFile) {
            runCatching {
                systemFileSystem.source(root).use { it.read(okio.Buffer(), 1) }
            }.getOrElse {
                throw FileTransferException(OperationErrorCode.PATH_NOT_READABLE, "Source file is not readable")
            }
        } else if (metadata.isDirectory) {
            runCatching { systemFileSystem.list(root) }.getOrElse {
                throw FileTransferException(OperationErrorCode.PATH_NOT_READABLE, "Source folder is not readable")
            }
        }
        val manifest = when {
            metadata.isRegularFile -> FileManifest(
                ManifestEntryType.FILE,
                listOf(FileManifestEntry(ROOT_FILE_RELATIVE_PATH, ManifestEntryType.FILE, metadata.size)),
            )
            metadata.isDirectory -> FileManifest(
                ManifestEntryType.DIRECTORY,
                runCatching {
                    val canonicalRoot = systemFileSystem.canonicalize(root)
                    buildList {
                        collectManifest(
                            root = root,
                            canonicalRoot = canonicalRoot,
                            directory = root,
                            destination = this,
                            visitedDirectories = mutableSetOf(canonicalRoot.pathKey()),
                        )
                    }
                }.getOrElse { failure ->
                    throw FileTransferException(
                        OperationErrorCode.PATH_NOT_READABLE,
                        "Source folder cannot be fully read: ${failure.message ?: "unknown error"}",
                    )
                },
            )
            else -> throw FileTransferException(
                OperationErrorCode.PATH_NOT_READABLE,
                "Source is not a regular file or folder",
            )
        }
        val manifestBytes = ProtocolJson
            .encodeToString(FileTransferManifestRequest(manifest))
            .encodeToByteArray()
            .size
        if (manifestBytes > MAX_MANIFEST_BYTES) {
            throw FileTransferException(OperationErrorCode.MANIFEST_TOO_LARGE, "File manifest exceeds 16 MiB")
        }
        stateMutex.withLock {
            sources[transferId]?.let { existing ->
                require(existing == Source(root, manifest, relayInstanceId)) {
                    "Source preflight parameters changed for an existing transfer"
                }
            }
            sources[transferId] = Source(root, manifest, relayInstanceId)
        }
    }

    suspend fun prepareDestination(
        transferId: TransferId,
        rawPath: String,
        relayInstanceId: InstanceId,
    ) {
        val root = expandAbsolutePath(rawPath)
        if (!supportedPathName(root.name)) {
            throw FileTransferException(
                OperationErrorCode.DESTINATION_NOT_WRITABLE,
                "Destination name is unsupported by this filesystem",
            )
        }
        if (systemFileSystem.exists(root)) {
            throw FileTransferException(OperationErrorCode.DESTINATION_EXISTS, "Destination already exists")
        }
        val parent = root.parent ?: throw FileTransferException(
            OperationErrorCode.DESTINATION_NOT_WRITABLE,
            "Destination has no parent",
        )
        val parentMetadata = runCatching { systemFileSystem.metadata(parent) }.getOrNull()
        if (parentMetadata?.isDirectory != true || parentMetadata.symlinkTarget != null) {
            throw FileTransferException(
                OperationErrorCode.DESTINATION_NOT_WRITABLE,
                "Destination parent is unavailable or is a link",
            )
        }
        val caseSensitive = probeDestinationParent(parent)
        stateMutex.withLock {
            destinations[transferId]?.let { existing ->
                require(
                    existing.root == root &&
                        existing.relayInstanceId == relayInstanceId &&
                        existing.caseSensitive == caseSensitive,
                ) {
                    "Destination preflight parameters changed for an existing transfer"
                }
            }
            destinations[transferId] = Destination(root, relayInstanceId, caseSensitive)
        }
    }

    suspend fun startSource(transferId: TransferId, relayInstanceId: InstanceId) {
        val source = stateMutex.withLock {
            sources[transferId] ?: error("Source preflight state was not found")
        }
        require(source.relayInstanceId == relayInstanceId) { "File relay instance changed after preflight" }
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runSource(transferId, source)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                reportFailure(transferId, source.relayInstanceId, failure)
            } finally {
                cleanup(transferId, TransferSide.SOURCE, job)
            }
        }
        val shouldStart = stateMutex.withLock {
            val transferJobs = jobs.getOrPut(transferId) { TransferJobs() }
            if (transferJobs.source?.isActive == true) {
                false
            } else {
                transferJobs.source = job
                true
            }
        }
        if (shouldStart) job.start() else job.cancel()
    }

    suspend fun startDestination(transferId: TransferId, relayInstanceId: InstanceId) {
        val destination = stateMutex.withLock {
            destinations[transferId] ?: error("Destination preflight state was not found")
        }
        require(destination.relayInstanceId == relayInstanceId) { "File relay instance changed after preflight" }
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runDestination(transferId, destination)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                reportFailure(transferId, destination.relayInstanceId, failure)
            } finally {
                cleanup(transferId, TransferSide.DESTINATION, job)
            }
        }
        val shouldStart = stateMutex.withLock {
            val transferJobs = jobs.getOrPut(transferId) { TransferJobs() }
            if (transferJobs.destination?.isActive == true) {
                false
            } else {
                transferJobs.destination = job
                true
            }
        }
        if (shouldStart) job.start() else job.cancel()
    }

    suspend fun cancel(transferId: TransferId) {
        val transferJobs = stateMutex.withLock {
            sources.remove(transferId)
            destinations.remove(transferId)
            pendingCoordinatorLosses.remove(transferId)
            jobs.remove(transferId)
        }
        listOfNotNull(transferJobs?.source, transferJobs?.destination)
            .distinct()
            .forEach { it.cancelAndJoin() }
    }

    suspend fun reportCoordinatorLosses() {
        val pending = stateMutex.withLock { pendingCoordinatorLosses.toList() }
        pending.forEach { transferId ->
            val response = runCatching {
                client.post(
                    "${config.serverUrl}/daemon/file-transfer/${transferId.value}/instance-lost",
                ) {
                    header("X-Device-Id", config.credential.deviceId.value)
                    header("X-Device-Secret", config.credential.secret)
                }
            }.getOrNull()
            if (
                response != null &&
                (
                    response.status.isSuccess() ||
                        response.status == HttpStatusCode.NotFound ||
                        response.status == HttpStatusCode.Conflict
                    )
            ) {
                stateMutex.withLock { pendingCoordinatorLosses.remove(transferId) }
            }
        }
    }

    private suspend fun runSource(transferId: TransferId, source: Source) {
        val manifestResponse = request(transferId, "manifest", HttpVerb.PUT, source.relayInstanceId) {
            contentType(ContentType.Application.Json)
            setBody(FileTransferManifestRequest(source.manifest))
        }
        if (!manifestResponse.status.isSuccess()) {
            throw FileTransferException(OperationErrorCode.INVALID_REQUEST, "Relay rejected the source manifest")
        }
        val planResponse = request(transferId, "plan", HttpVerb.GET, source.relayInstanceId)
        if (!planResponse.status.isSuccess()) {
            throw FileTransferException(OperationErrorCode.OPERATION_TIMEOUT, "Destination file plan was unavailable")
        }
        val accepted = planResponse.body<FileTransferPlan>().acceptedFiles.toSet()
        val files = source.manifest.entries.filter {
            it.type == ManifestEntryType.FILE && it.relativePath in accepted
        }
        files.forEach { entry -> uploadWithRetry(transferId, source, entry) }
    }

    private suspend fun runDestination(transferId: TransferId, destination: Destination) {
        val manifestResponse = request(transferId, "manifest", HttpVerb.GET, destination.relayInstanceId)
        if (!manifestResponse.status.isSuccess()) {
            throw FileTransferException(OperationErrorCode.INVALID_REQUEST, "Source manifest was unavailable")
        }
        val manifest = manifestResponse.body<FileManifest>()
        val prepared = prepareDestinationContent(destination, manifest)
        val planResponse = request(transferId, "plan", HttpVerb.PUT, destination.relayInstanceId) {
            contentType(ContentType.Application.Json)
            setBody(FileTransferPlanRequest(prepared.plan))
        }
        if (!planResponse.status.isSuccess()) {
            throw FileTransferException(OperationErrorCode.INVALID_REQUEST, "Relay rejected the destination file plan")
        }
        var successful = 0
        prepared.files.forEach { candidate ->
            downloadWithRetry(transferId, destination, candidate.entry, candidate.target)
            successful++
        }
        val finishResponse = request(transferId, "finish", HttpVerb.POST, destination.relayInstanceId) {
            contentType(ContentType.Application.Json)
            setBody(FileTransferFinishRequest(successful))
        }
        if (!finishResponse.status.isSuccess()) {
            throw FileTransferException(OperationErrorCode.INVALID_REQUEST, "Relay rejected transfer completion")
        }
    }

    private fun prepareDestinationContent(
        destination: Destination,
        manifest: FileManifest,
    ): PreparedDestination {
        require(manifest.entries.distinctBy { it.relativePath }.size == manifest.entries.size) {
            "Manifest has duplicate paths"
        }
        if (manifest.rootType == ManifestEntryType.FILE) {
            require(
                manifest.entries.size == 1 &&
                    manifest.entries.single().relativePath == ROOT_FILE_RELATIVE_PATH &&
                    manifest.entries.single().type == ManifestEntryType.FILE,
            ) {
                "Root file manifest is invalid"
            }
            createEmptyFile(destination.root, mustCreate = true).getOrElse {
                throw FileTransferException(
                    OperationErrorCode.DESTINATION_NOT_WRITABLE,
                    "Destination file cannot be created",
                )
            }
            return PreparedDestination(
                FileTransferPlan(listOf(ROOT_FILE_RELATIVE_PATH), 0),
                listOf(DestinationCandidate(manifest.entries.single(), destination.root)),
            )
        }

        runCatching { systemFileSystem.createDirectories(destination.root) }.getOrElse {
            throw FileTransferException(
                OperationErrorCode.DESTINATION_NOT_WRITABLE,
                "Destination folder cannot be created",
            )
        }
        val occupied = mutableSetOf<String>()
        val skippedDirectories = mutableListOf<String>()
        val directoryCandidates = mutableListOf<DestinationCandidate>()
        val fileCandidates = mutableListOf<DestinationCandidate>()
        manifest.entries.forEach { entry ->
            val relativePath = entry.relativePath
            if (
                !isSafeRelativePath(relativePath) ||
                skippedDirectories.any { relativePath == it || relativePath.startsWith("$it/") } ||
                !supportedManifestPath(relativePath)
            ) {
                if (entry.type == ManifestEntryType.DIRECTORY) skippedDirectories += relativePath
                return@forEach
            }
            val target = destination.root / relativePath
            val collisionKey = if (destination.caseSensitive) relativePath else relativePath.lowercase()
            if (
                !containedBy(destination.root, target) ||
                !occupied.add(collisionKey) ||
                systemFileSystem.exists(target)
            ) {
                if (entry.type == ManifestEntryType.DIRECTORY) skippedDirectories += relativePath
                return@forEach
            }
            val candidate = DestinationCandidate(entry, target)
            when (entry.type) {
                ManifestEntryType.DIRECTORY -> directoryCandidates += candidate
                ManifestEntryType.FILE -> fileCandidates += candidate
            }
        }

        val createdDirectories = mutableListOf<DestinationCandidate>()
        directoryCandidates.forEach { candidate ->
            val relativePath = candidate.entry.relativePath
            if (skippedDirectories.any { relativePath != it && relativePath.startsWith("$it/") }) {
                return@forEach
            }
            val created = runCatching {
                systemFileSystem.createDirectories(candidate.target)
                val metadata = systemFileSystem.metadata(candidate.target)
                check(metadata.isDirectory && metadata.symlinkTarget == null)
                check(canonicalContained(destination.root, candidate.target, destination.caseSensitive))
            }.isSuccess
            if (created) {
                createdDirectories += candidate
            } else {
                skippedDirectories += relativePath
            }
        }

        val createdFiles = mutableListOf<DestinationCandidate>()
        fileCandidates.forEach { candidate ->
            if (skippedDirectories.any {
                    candidate.entry.relativePath == it || candidate.entry.relativePath.startsWith("$it/")
                }
            ) {
                return@forEach
            }
            val parent = candidate.target.parent
            val created = parent != null &&
                canonicalContained(destination.root, parent, destination.caseSensitive) &&
                createEmptyFile(candidate.target, mustCreate = true).isSuccess
            if (created) createdFiles += candidate
        }
        return PreparedDestination(
            plan = FileTransferPlan(
                acceptedFiles = createdFiles.map { it.entry.relativePath },
                skippedEntries = manifest.entries.size - createdDirectories.size - createdFiles.size,
            ),
            files = createdFiles,
        )
    }

    private suspend fun uploadWithRetry(
        transferId: TransferId,
        source: Source,
        entry: FileManifestEntry,
    ) {
        val path = if (source.manifest.rootType == ManifestEntryType.FILE) {
            source.root
        } else {
            source.root / entry.relativePath
        }
        var lastFailure = "Relay rejected content"
        repeat(2) { attempt ->
            val failure = uploadAttempt(transferId, source, entry, path, attempt)
            if (failure == null) return
            lastFailure = failure
        }
        throw FileTransferException(
            OperationErrorCode.FILE_INTEGRITY_MISMATCH,
            "File ${entry.relativePath} upload failed after retry: $lastFailure",
        )
    }

    private suspend fun uploadAttempt(
        transferId: TransferId,
        source: Source,
        entry: FileManifestEntry,
        path: Path,
        attempt: Int,
    ): String? = try {
        coroutineScope {
            val computedIntegrity = CompletableDeferred<FileIntegrity>()
            val upload = async {
                request(
                    transferId,
                    "content?path=${encodeComponent(entry.relativePath)}&attempt=$attempt",
                    HttpVerb.PUT,
                    source.relayInstanceId,
                ) {
                    contentType(ContentType.Application.OctetStream)
                    setBody(
                        FileOutgoingContent(
                            path = path,
                            expectedByteCount = requireNotNull(entry.size),
                            computedIntegrity = computedIntegrity,
                        ),
                    )
                }
            }
            val progress: Pair<FileIntegrity?, io.ktor.client.statement.HttpResponse?> = select {
                computedIntegrity.onAwait { it to null }
                upload.onAwait { null to it }
            }
            val (integrity, earlyResponse) = progress
            if (earlyResponse != null) {
                return@coroutineScope "Relay upload returned HTTP ${earlyResponse.status.value}: " +
                    runCatching { earlyResponse.bodyAsText().take(512) }.getOrDefault("")
            }
            val sourceComplete = request(
                transferId,
                "content/source-complete",
                HttpVerb.POST,
                source.relayInstanceId,
            ) {
                contentType(ContentType.Application.Json)
                setBody(FileTransferContentRequest(entry.relativePath, attempt, requireNotNull(integrity)))
            }
            if (!sourceComplete.status.isSuccess()) {
                upload.cancel()
                runCatching { upload.await() }
                return@coroutineScope "Source integrity report returned HTTP ${sourceComplete.status.value}: " +
                    runCatching { sourceComplete.bodyAsText().take(512) }.getOrDefault("")
            }
            val completed = upload.await()
            if (completed.status.isSuccess()) {
                null
            } else {
                "Relay upload returned HTTP ${completed.status.value}: " +
                    runCatching { completed.bodyAsText().take(512) }.getOrDefault("")
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        "Relay upload failed: ${failure.message ?: failure::class.simpleName}"
    }

    private suspend fun downloadWithRetry(
        transferId: TransferId,
        destination: Destination,
        entry: FileManifestEntry,
        target: Path,
    ) {
        var lastFailure = "Relay did not provide content"
        repeat(2) { attempt ->
            val parent = target.parent
            if (
                parent == null ||
                !destinationTargetIsSafe(destination, target, parent)
            ) {
                throw FileTransferException(
                    OperationErrorCode.DESTINATION_NOT_WRITABLE,
                    "Destination path containment changed during transfer",
                )
            }
            val response = request(
                transferId,
                "content?path=${encodeComponent(entry.relativePath)}&attempt=$attempt",
                HttpVerb.GET,
                destination.relayInstanceId,
            )
            if (!response.status.isSuccess()) {
                lastFailure = "Relay download returned HTTP ${response.status.value}: " +
                    runCatching { response.bodyAsText().take(512) }.getOrDefault("")
                return@repeat
            }
            val integrity = runCatching {
                val hashingSink = HashingSink.sha256(systemFileSystem.sink(target, mustCreate = false))
                val sink = hashingSink.buffer()
                val channel = response.body<io.ktor.utils.io.ByteReadChannel>()
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                sink.use {
                    while (true) {
                        val read = channel.readAvailable(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        it.write(buffer, 0, read)
                        count += read
                    }
                }
                FileIntegrity(hashingSink.hash.hex(), count)
            }.getOrElse { failure ->
                lastFailure = "Relay download stream failed: ${failure.message ?: failure::class.simpleName}"
                truncate(target)
                return@repeat
            }
            val localMatchesManifest = entry.size == integrity.byteCount
            if (!localMatchesManifest) {
                lastFailure = "Relay returned ${integrity.byteCount} bytes; manifest expected ${entry.size}"
            }
            val completed = request(
                transferId,
                "content/complete",
                HttpVerb.POST,
                destination.relayInstanceId,
            ) {
                contentType(ContentType.Application.Json)
                setBody(FileTransferContentRequest(entry.relativePath, attempt, integrity))
            }
            if (completed.status.isSuccess() && localMatchesManifest) return
            if (!completed.status.isSuccess()) {
                lastFailure = "Relay integrity check returned HTTP ${completed.status.value}: " +
                    runCatching { completed.bodyAsText().take(512) }.getOrDefault("")
            }
            truncate(target)
        }
        throw FileTransferException(
            OperationErrorCode.FILE_INTEGRITY_MISMATCH,
            "File ${entry.relativePath} failed after retry: $lastFailure",
        )
    }

    private fun destinationTargetIsSafe(
        destination: Destination,
        target: Path,
        parent: Path,
    ): Boolean {
        if (target != destination.root) {
            return canonicalContained(destination.root, parent, destination.caseSensitive)
        }
        return runCatching {
            val targetMetadata = systemFileSystem.metadata(target)
            val parentMetadata = systemFileSystem.metadata(parent)
            targetMetadata.isRegularFile &&
                targetMetadata.symlinkTarget == null &&
                parentMetadata.isDirectory &&
                parentMetadata.symlinkTarget == null
        }.getOrDefault(false)
    }

    private fun collectManifest(
        root: Path,
        canonicalRoot: Path,
        directory: Path,
        destination: MutableList<FileManifestEntry>,
        visitedDirectories: MutableSet<String>,
    ) {
        systemFileSystem.list(directory).sortedBy { it.toString() }.forEach { path ->
            val metadata = systemFileSystem.metadata(path)
            if (metadata.symlinkTarget != null) return@forEach
            val canonical = systemFileSystem.canonicalize(path)
            val caseSensitive = !platformName().startsWith("windows")
            if (!canonicalContained(canonicalRoot, canonical, caseSensitive)) {
                return@forEach
            }
            val relative = path.relativeTo(root).toString().replace('\\', '/')
            val expectedCanonical = canonicalRoot / relative
            if (canonical.pathKey() != expectedCanonical.pathKey()) return@forEach
            when {
                metadata.isDirectory -> {
                    if (!visitedDirectories.add(canonical.pathKey())) return@forEach
                    destination += FileManifestEntry(relative, ManifestEntryType.DIRECTORY)
                    collectManifest(
                        root,
                        canonicalRoot,
                        path,
                        destination,
                        visitedDirectories,
                    )
                }
                metadata.isRegularFile -> destination +=
                    FileManifestEntry(relative, ManifestEntryType.FILE, metadata.size)
            }
        }
    }

    private suspend fun reportFailure(
        transferId: TransferId,
        relayInstanceId: InstanceId,
        failure: Throwable,
    ) {
        val typed = failure as? FileTransferException
        val response = runCatching {
            request(transferId, "failure", HttpVerb.POST, relayInstanceId) {
                contentType(ContentType.Application.Json)
                setBody(
                    FileTransferFailureRequest(
                        typed?.code ?: OperationErrorCode.INTERNAL_ERROR,
                        failure.message ?: "File transfer failed",
                    ),
                )
            }
        }.getOrNull()
        if (
            response == null ||
            response.status.value == 421 ||
            response.status.value >= 500
        ) {
            stateMutex.withLock { pendingCoordinatorLosses += transferId }
            reportCoordinatorLosses()
        }
    }

    private suspend fun cleanup(transferId: TransferId, side: TransferSide, completedJob: Job) {
        stateMutex.withLock {
            val transferJobs = jobs[transferId]
            when (side) {
                TransferSide.SOURCE -> {
                    sources.remove(transferId)
                    if (transferJobs?.source === completedJob) transferJobs.source = null
                }
                TransferSide.DESTINATION -> {
                    destinations.remove(transferId)
                    if (transferJobs?.destination === completedJob) transferJobs.destination = null
                }
            }
            if (transferJobs?.source == null && transferJobs?.destination == null) jobs.remove(transferId)
        }
    }

    private suspend fun request(
        transferId: TransferId,
        suffix: String,
        verb: HttpVerb,
        relayInstanceId: InstanceId,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): io.ktor.client.statement.HttpResponse {
        val url = "${config.serverUrl}/relay/${transferId.value}/$suffix"
        val requestBlock: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header("X-Device-Id", config.credential.deviceId.value)
            header("X-Device-Secret", config.credential.secret)
            header("X-Relay-Instance-Id", relayInstanceId.value)
            block()
        }
        return when (verb) {
            HttpVerb.GET -> client.get(url, requestBlock)
            HttpVerb.PUT -> client.put(url, requestBlock)
            HttpVerb.POST -> client.post(url, requestBlock)
        }
    }

    private fun probeDestinationParent(parent: Path): Boolean {
        val name = ".device-as-mcp-case-probe-${randomId().lowercase()}"
        val lower = parent / name
        val upper = parent / name.uppercase()
        return runCatching {
            createEmptyFile(lower, mustCreate = true).getOrThrow()
            !systemFileSystem.exists(upper)
        }.getOrElse {
            throw FileTransferException(
                OperationErrorCode.DESTINATION_NOT_WRITABLE,
                "Destination parent is not writable",
            )
        }.also {
            runCatching { systemFileSystem.delete(lower) }
            if (upper != lower) runCatching { systemFileSystem.delete(upper) }
        }
    }

    private fun createEmptyFile(path: Path, mustCreate: Boolean): Result<Unit> = runCatching {
        systemFileSystem.sink(path, mustCreate = mustCreate).buffer().use { }
    }

    private fun truncate(path: Path) {
        runCatching { systemFileSystem.sink(path, mustCreate = false).buffer().use { } }
    }

    private fun canonicalContained(root: Path, candidate: Path, caseSensitive: Boolean): Boolean {
        val rootValue = runCatching { systemFileSystem.canonicalize(root) }
            .getOrElse { return false }
            .toString()
            .replace('\\', '/')
            .trimEnd('/')
        val candidateValue = runCatching { systemFileSystem.canonicalize(candidate) }
            .getOrElse { return false }
            .toString()
            .replace('\\', '/')
            .trimEnd('/')
        return candidateValue.equals(rootValue, ignoreCase = !caseSensitive) ||
            candidateValue.startsWith("$rootValue/", ignoreCase = !caseSensitive)
    }

    private fun containedBy(root: Path, target: Path): Boolean {
        val rootValue = root.normalized().toString().replace('\\', '/').trimEnd('/')
        val targetValue = target.normalized().toString().replace('\\', '/')
        return targetValue.startsWith(
            "$rootValue/",
            ignoreCase = platformName().startsWith("windows"),
        )
    }

    private fun Path.pathKey(): String = toString()
        .replace('\\', '/')
        .trimEnd('/')
        .let { if (platformName().startsWith("windows")) it.lowercase() else it }

    private fun supportedManifestPath(path: String): Boolean =
        path.split('/').all(::supportedPathName)

    private fun supportedPathName(name: String): Boolean {
        if (name.isEmpty() || name.any { it == '\u0000' || it == '/' || it == '\\' }) return false
        if (!platformName().startsWith("windows")) return true
        if (name.last() == '.' || name.last() == ' ') return false
        if (name.any { it.code in 0..31 || it in "<>:\"|?*" }) return false
        val base = name.substringBefore('.').uppercase()
        return base !in WINDOWS_RESERVED_NAMES
    }

    private fun expandAbsolutePath(value: String): Path {
        val path = if (value == "~" || value.startsWith("~/") || value.startsWith("~\\")) {
            val home = environment("HOME") ?: environment("USERPROFILE")
                ?: throw FileTransferException(
                    OperationErrorCode.INVALID_REQUEST,
                    "Cannot expand ~ because the home directory is unavailable",
                )
            (home + value.drop(1)).toPath(normalize = true)
        } else {
            value.toPath(normalize = true)
        }
        if (!path.isAbsolute) {
            throw FileTransferException(
                OperationErrorCode.INVALID_REQUEST,
                "File paths must be absolute or begin with ~",
            )
        }
        return path
    }

    private fun encodeComponent(value: String): String = value.encodeToByteArray().joinToString("") { byte ->
        val unsigned = byte.toInt() and 0xff
        val character = unsigned.toChar()
        if (character.isLetterOrDigit() || character in "-._~") character.toString()
        else "%${unsigned.toString(16).uppercase().padStart(2, '0')}"
    }

    private inner class FileOutgoingContent(
        private val path: Path,
        private val expectedByteCount: Long,
        private val computedIntegrity: CompletableDeferred<FileIntegrity>,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.OctetStream
        override val contentLength: Long = expectedByteCount

        override suspend fun writeTo(channel: ByteWriteChannel) {
            try {
                val hashingSource = HashingSource.sha256(systemFileSystem.source(path))
                var count = 0L
                hashingSource.buffer().use { source ->
                    val buffer = okio.Buffer()
                    while (true) {
                        val read = source.read(buffer, 64 * 1024)
                        if (read < 0) break
                        channel.writeFully(buffer.readByteArray(read))
                        count += read
                    }
                }
                computedIntegrity.complete(FileIntegrity(hashingSource.hash.hex(), count))
            } catch (failure: Throwable) {
                computedIntegrity.completeExceptionally(failure)
                throw failure
            }
        }
    }

    private enum class HttpVerb { GET, PUT, POST }

    companion object {
        private val WINDOWS_RESERVED_NAMES = buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL"))
            (1..9).forEach {
                add("COM$it")
                add("LPT$it")
            }
        }
    }
}

internal class FileTransferException(
    val code: OperationErrorCode,
    message: String,
) : IllegalStateException(message)
