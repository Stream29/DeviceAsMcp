package io.github.stream29.mcp.device.daemon

internal class Utf8StreamDecoder(private val output: suspend (String) -> Unit) {
    private var pending = byteArrayOf()

    suspend fun accept(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val combined = pending + bytes
        val complete = completePrefixLength(combined)
        if (complete > 0) output(combined.copyOfRange(0, complete).decodeToString())
        pending = combined.copyOfRange(complete, combined.size)
    }

    suspend fun finish() {
        if (pending.isNotEmpty()) output(pending.decodeToString())
        pending = byteArrayOf()
    }

    private fun completePrefixLength(bytes: ByteArray): Int {
        var index = 0
        var complete = 0
        while (index < bytes.size) {
            val leading = bytes[index].toInt() and 0xff
            val width = when (leading) {
                in 0x00..0x7f -> 1
                in 0xc2..0xdf -> 2
                in 0xe0..0xef -> 3
                in 0xf0..0xf4 -> 4
                else -> 1
            }
            if (index + width > bytes.size) break
            if (
                width > 1 &&
                (index + 1 until index + width).any {
                    (bytes[it].toInt() and 0xc0) != 0x80
                }
            ) {
                index++
                complete = index
            } else {
                index += width
                complete = index
            }
        }
        return complete
    }
}
