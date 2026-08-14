package io.github.stream29.mcp.device.daemon

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import okio.Path
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.chmod

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureLocalPath(path: Path, directory: Boolean) {
    val mode = if (directory) {
        S_IRUSR or S_IWUSR or S_IXUSR
    } else {
        S_IRUSR or S_IWUSR
    }
    check(chmod(path.toString(), mode.convert()) == 0) {
        "Failed to restrict local credential permissions"
    }
}
