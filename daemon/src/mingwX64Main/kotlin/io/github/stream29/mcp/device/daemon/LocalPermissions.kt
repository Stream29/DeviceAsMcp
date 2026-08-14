package io.github.stream29.mcp.device.daemon

import io.github.stream29.mcp.device.daemon.conpty.damcp_secure_local_path
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.wcstr
import okio.Path

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureLocalPath(path: Path, directory: Boolean) {
    val result = memScoped {
        damcp_secure_local_path(
            path = path.toString().wcstr.ptr,
            directory = if (directory) 1 else 0,
        )
    }
    check(result == 0u) {
        "Failed to restrict local credential ACL: Windows error $result"
    }
}
