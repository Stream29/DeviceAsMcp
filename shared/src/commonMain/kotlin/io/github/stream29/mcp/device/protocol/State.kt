package io.github.stream29.mcp.device.protocol

class FirstResultRegistry<K, V> {
    private val pending = mutableMapOf<K, V?>()
    private val completed = mutableSetOf<K>()

    fun register(key: K): Boolean = if (key in pending || key in completed) {
        false
    } else {
        pending[key] = null
        true
    }

    fun complete(key: K, value: V): CompletionResult = when {
        key in completed -> CompletionResult.DUPLICATE
        key !in pending -> CompletionResult.UNKNOWN
        else -> {
            pending[key] = value
            completed += key
            CompletionResult.ACCEPTED
        }
    }

    fun consume(key: K): V? = pending.remove(key)

    fun forget(key: K) {
        pending.remove(key)
        completed.remove(key)
    }
}

enum class CompletionResult { ACCEPTED, DUPLICATE, UNKNOWN }

class BoundedTextBuffer(private val maxBytes: Int = TERMINAL_OUTPUT_LIMIT_BYTES) {
    init { require(maxBytes > 0) }

    private var text: String = ""
    private var byteCount: Int = 0
    var discardedBytes: Long = 0
        private set

    val truncated: Boolean get() = discardedBytes > 0

    fun append(value: String) {
        if (value.isEmpty()) return
        text += value
        byteCount += value.encodeToByteArray().size
        trim()
    }

    fun consume(): String = text.also {
        text = ""
        byteCount = 0
    }

    fun consumeDiscardedBytes(): Long = discardedBytes.also { discardedBytes = 0 }

    private fun trim() {
        while (byteCount > maxBytes && text.isNotEmpty()) {
            val removedCharacters = if (
                text.length >= 2 &&
                text[0].code in 0xD800..0xDBFF &&
                text[1].code in 0xDC00..0xDFFF
            ) {
                2
            } else {
                1
            }
            val removed = text.take(removedCharacters).encodeToByteArray().size
            text = text.drop(removedCharacters)
            discardedBytes += removed
            byteCount -= removed
        }
    }
}

class TerminalOutputBuffer(private val maxBytes: Int = TERMINAL_OUTPUT_LIMIT_BYTES) {
    init { require(maxBytes > 0) }

    private enum class Stream { STDOUT, STDERR }

    private data class Segment(
        val stream: Stream,
        var text: String,
        var byteCount: Int,
    )

    private val segments = ArrayDeque<Segment>()
    private var byteCount = 0
    private var discardedBytes = 0L

    fun appendStdout(value: String) = append(Stream.STDOUT, value)

    fun appendStderr(value: String) = append(Stream.STDERR, value)

    fun consume(): TerminalBufferedOutput {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        segments.forEach { segment ->
            when (segment.stream) {
                Stream.STDOUT -> stdout.append(segment.text)
                Stream.STDERR -> stderr.append(segment.text)
            }
        }
        return TerminalBufferedOutput(
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            truncated = discardedBytes > 0,
            discardedBytes = discardedBytes,
        ).also {
            segments.clear()
            byteCount = 0
            discardedBytes = 0
        }
    }

    private fun append(stream: Stream, value: String) {
        if (value.isEmpty()) return
        val bytes = value.encodeToByteArray().size
        val last = segments.lastOrNull()
        if (last?.stream == stream) {
            last.text += value
            last.byteCount += bytes
        } else {
            segments.addLast(Segment(stream, value, bytes))
        }
        byteCount += bytes
        trim()
    }

    private fun trim() {
        while (byteCount > maxBytes && segments.isNotEmpty()) {
            val segment = segments.first()
            val removed = removeUtf8Prefix(segment.text, byteCount - maxBytes)
            segment.text = segment.text.drop(removed.characters)
            segment.byteCount -= removed.bytes
            byteCount -= removed.bytes
            discardedBytes += removed.bytes
            if (segment.text.isEmpty()) segments.removeFirst()
        }
    }

    private fun removeUtf8Prefix(value: String, minimumBytes: Int): RemovedPrefix {
        var characters = 0
        var bytes = 0
        while (characters < value.length && bytes < minimumBytes) {
            val width = if (
                characters + 1 < value.length &&
                value[characters].code in 0xD800..0xDBFF &&
                value[characters + 1].code in 0xDC00..0xDFFF
            ) {
                2
            } else {
                1
            }
            bytes += value.substring(characters, characters + width).encodeToByteArray().size
            characters += width
        }
        return RemovedPrefix(characters, bytes)
    }

    private data class RemovedPrefix(val characters: Int, val bytes: Int)
}

data class TerminalBufferedOutput(
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
    val discardedBytes: Long,
)
