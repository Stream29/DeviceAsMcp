package io.github.stream29.mcp.device.protocol

import kotlinx.serialization.Serializable

const val MAX_MANIFEST_BYTES: Int = 16 * 1024 * 1024
const val ROOT_FILE_RELATIVE_PATH: String = "__root_file__"

@Serializable
data class FileManifest(
    val rootType: ManifestEntryType,
    val entries: List<FileManifestEntry>,
)

@Serializable
data class FileManifestEntry(
    val relativePath: String,
    val type: ManifestEntryType,
    val size: Long? = null,
) {
    init {
        require(isSafeRelativePath(relativePath)) { "Manifest path must be normalized and relative" }
        require(size == null || size >= 0) { "File size cannot be negative" }
        require((type == ManifestEntryType.FILE) == (size != null)) {
            "Files require a size and directories cannot have one"
        }
    }
}

@Serializable
enum class ManifestEntryType { FILE, DIRECTORY }

@Serializable
data class FileIntegrity(
    val sha256: String,
    val byteCount: Long,
) {
    init {
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
            "SHA-256 must contain 64 hexadecimal characters"
        }
        require(byteCount >= 0) { "Byte count cannot be negative" }
    }
}

@Serializable
data class LaunchFileTransferRequest(
    val sourceDeviceId: DeviceId,
    val sourcePath: String,
    val destinationDeviceId: DeviceId,
    val destinationPath: String,
)

@Serializable
data class LaunchFileTransferResult(val transferId: TransferId)

@Serializable
data class FileTransferSummary(
    val transferId: TransferId,
    val status: FileTransferStatus,
    val successfulFiles: Int,
    val errorCode: OperationErrorCode? = null,
    val message: String? = null,
)

@Serializable
enum class FileTransferStatus { PREPARING, RUNNING, FAILED, ABSENT }

@Serializable
data class FileTransferRecord(
    val transferId: TransferId,
    val userId: UserId,
    val sourceDeviceId: DeviceId,
    val sourcePath: String,
    val destinationDeviceId: DeviceId,
    val destinationPath: String,
    val relayInstanceId: InstanceId,
    val status: FileTransferStatus = FileTransferStatus.PREPARING,
    val successfulFiles: Int = 0,
    val errorCode: OperationErrorCode? = null,
    val message: String? = null,
)

@Serializable
data class FileTransferManifestRequest(val manifest: FileManifest)

@Serializable
data class FileTransferPlan(
    val acceptedFiles: List<String>,
    val skippedEntries: Int,
) {
    init {
        require(skippedEntries >= 0) { "Skipped-entry count cannot be negative" }
        require(acceptedFiles.distinct().size == acceptedFiles.size) { "Accepted file paths must be unique" }
        require(acceptedFiles.all { it == ROOT_FILE_RELATIVE_PATH || isSafeRelativePath(it) }) {
            "Accepted file paths must be normalized and relative"
        }
    }
}

@Serializable
data class FileTransferPlanRequest(val plan: FileTransferPlan)

@Serializable
data class FileTransferContentRequest(
    val relativePath: String,
    val attempt: Int,
    val integrity: FileIntegrity? = null,
)

@Serializable
data class FileTransferFinishRequest(val successfulFiles: Int)

@Serializable
data class FileTransferFailureRequest(
    val errorCode: OperationErrorCode,
    val message: String,
)

@Serializable
data class CancelFileTransferResult(
    val transferId: TransferId,
    val cancelled: Boolean,
)

fun isSafeRelativePath(path: String): Boolean {
    if (path.isBlank() || path.startsWith('/') || path.startsWith('\\')) return false
    if (path.contains('\\')) return false
    val parts = path.split('/')
    return parts.none { it.isBlank() || it == "." || it == ".." }
}
