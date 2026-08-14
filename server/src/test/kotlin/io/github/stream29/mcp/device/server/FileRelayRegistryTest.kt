package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.FileIntegrity
import io.github.stream29.mcp.device.protocol.TransferId
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileRelayRegistryTest {
    @Test
    fun acceptsIntegrityReportedBeforeUploadPublication() = runTest {
        val registry = FileRelayRegistry()
        val key = RelayFileKey(TransferId("transfer"), "small.txt", 0)
        val integrity = FileIntegrity("a".repeat(64), 5)
        val upload = RelayUpload(ByteReadChannel("small".encodeToByteArray()), 5)
        val recording = async(start = CoroutineStart.UNDISPATCHED) {
            registry.recordSourceIntegrity(key, integrity, 1_000)
        }

        assertTrue(registry.publish(key, upload))
        assertTrue(recording.await())
        assertIs<RelayCompletion.Verified>(registry.complete(key, integrity, 1_000) { true })
    }

    @Test
    fun matchesOneSourceAndDestinationAttempt() = runTest {
        val registry = FileRelayRegistry()
        val key = RelayFileKey(TransferId("transfer"), "file.txt", 0)
        val integrity = FileIntegrity("a".repeat(64), 4)
        val upload = RelayUpload(ByteReadChannel("data".encodeToByteArray()), 4)
        val completion = async { upload.completion.await() }

        assertTrue(registry.publish(key, upload))
        assertSame(upload, registry.await(key, 1_000))
        assertTrue(registry.recordSourceIntegrity(key, integrity))
        assertIs<RelayCompletion.Verified>(registry.complete(key, integrity, 1_000) { true })
        assertIs<RelayCompletion.Verified>(completion.await())
    }

    @Test
    fun rejectsIntegrityMismatch() = runTest {
        val registry = FileRelayRegistry()
        val key = RelayFileKey(TransferId("transfer"), "file.txt", 1)
        val upload = RelayUpload(ByteReadChannel(byteArrayOf()), 1)
        registry.publish(key, upload)
        registry.recordSourceIntegrity(key, FileIntegrity("a".repeat(64), 1))

        val result = registry.complete(key, FileIntegrity("b".repeat(64), 1), 1_000) { true }

        assertIs<RelayCompletion.Rejected>(result)
    }

    @Test
    fun doesNotAcknowledgeSourceWhenProgressCommitFails() = runTest {
        val registry = FileRelayRegistry()
        val key = RelayFileKey(TransferId("transfer"), "file.txt", 0)
        val integrity = FileIntegrity("a".repeat(64), 4)
        val upload = RelayUpload(ByteReadChannel("data".encodeToByteArray()), 4)
        registry.publish(key, upload)
        registry.recordSourceIntegrity(key, integrity)

        val result = registry.complete(key, integrity, 1_000) { false }

        assertIs<RelayCompletion.Rejected>(result)
        assertIs<RelayCompletion.Rejected>(upload.completion.await())
        assertFalse(registry.recordSourceIntegrity(key, integrity))
    }

    @Test
    fun rejectsDestinationWhenSourceDoesNotReportIntegrity() = runTest {
        val registry = FileRelayRegistry()
        val key = RelayFileKey(TransferId("transfer"), "file.txt", 0)
        val upload = RelayUpload(ByteReadChannel("data".encodeToByteArray()), 4)
        registry.publish(key, upload)

        val result = registry.complete(
            key,
            FileIntegrity("a".repeat(64), 4),
            sourceWaitMillis = 1,
        ) { true }

        assertIs<RelayCompletion.Rejected>(result)
        assertIs<RelayCompletion.Rejected>(upload.completion.await())
    }
}
