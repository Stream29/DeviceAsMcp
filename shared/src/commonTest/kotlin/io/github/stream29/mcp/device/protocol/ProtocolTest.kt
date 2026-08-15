package io.github.stream29.mcp.device.protocol

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun operationRoundTrips() {
        val envelope = OperationEnvelope(
            operationId = OperationId("operation-1"),
            deviceId = DeviceId("device-1"),
            payload = OperationPayload.LaunchTerminalSession("echo hello"),
        )

        val encoded = ProtocolJson.encodeToString(envelope)
        val decoded = ProtocolJson.decodeFromString<OperationEnvelope>(encoded)

        assertEquals(envelope, decoded)
        assertIs<OperationPayload.LaunchTerminalSession>(decoded.payload)
        assertTrue(encoded.contains("\"kind\":\"launch_terminal_session\""))
    }

    @Test
    fun operationResultUsesStatusAndResultFields() {
        val result = OperationResultEnvelope(
            operationId = OperationId("operation-1"),
            result = OperationResult.Success(OperationResultPayload.Acknowledged()),
        )

        val encoded = ProtocolJson.encodeToString(result)

        assertTrue(encoded.contains("\"status\":\"success\""))
        assertTrue(encoded.contains("\"result\":{\"type\":\"acknowledged\""))
        assertEquals(result, ProtocolJson.decodeFromString(encoded))
    }

    @Test
    fun filePreflightDoesNotEmbedManifest() {
        val payload: OperationResultPayload = OperationResultPayload.FilePreflight(true)

        val encoded = ProtocolJson.encodeToString(OperationResultPayload.serializer(), payload)

        assertFalse(encoded.contains("manifest"))
        assertEquals(
            payload,
            ProtocolJson.decodeFromString(OperationResultPayload.serializer(), encoded),
        )
    }

    @Test
    fun operationRejectsMismatchedWireKind() {
        val encoded =
            """
            {
              "version":1,
              "operationId":"operation-1",
              "deviceId":"device-1",
              "payload":{"type":"terminal_session_output","sessionId":"session-1"},
              "kind":"terminal_session_input"
            }
            """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            ProtocolJson.decodeFromString<OperationEnvelope>(encoded)
        }
    }

    @Test
    fun rpcDeadlineStaysInTransportMetadata() {
        val request: InstanceRpcRequest = InstanceRpcRequest.DispatchOperation(
            originInstanceId = InstanceId("origin"),
            connectionId = ConnectionId("connection"),
            operation = OperationEnvelope(
                operationId = OperationId("operation"),
                deviceId = DeviceId("device"),
                payload = OperationPayload.TerminalSessionOutput(TerminalSessionId("session")),
            ),
        )

        val encoded = ProtocolJson.encodeToString(request)

        assertFalse(encoded.contains("deadline", ignoreCase = true))
        assertEquals(request, ProtocolJson.decodeFromString(encoded))
    }

    @Test
    fun rpcTopologyUsesInstanceId() {
        val id = InstanceId("instance-1")
        assertEquals("device_as_mcp.instance_rpc.instance-1", InstanceRpcTopology.queue(id))
        assertEquals("instance.instance-1", InstanceRpcTopology.routingKey(id))
    }

    @Test
    fun deviceListUpdateTopologyUsesOneInstanceQueue() {
        val id = InstanceId("instance-1")
        assertEquals("device_list.update", DeviceListUpdateEvent.NAME)
        assertEquals(
            "device_as_mcp.device_list_updates.instance-1",
            DeviceListUpdateTopology.queue(id),
        )
        assertEquals(DeviceListUpdateEvent.NAME, DeviceListUpdateTopology.ROUTING_KEY)
    }

    @Test
    fun manifestRejectsTraversal() {
        assertTrue(isSafeRelativePath("folder/file.txt"))
        assertFalse(isSafeRelativePath("../secret"))
        assertFalse(isSafeRelativePath("/rooted"))
        assertFalse(isSafeRelativePath("folder\\file"))
        assertFailsWith<IllegalArgumentException> {
            FileManifestEntry("file.txt", ManifestEntryType.FILE)
        }
        assertFailsWith<IllegalArgumentException> {
            FileManifestEntry("folder", ManifestEntryType.DIRECTORY, 0)
        }
    }

    @Test
    fun registryCompletesOnlyFirstResult() {
        val registry = FirstResultRegistry<String, String>()
        assertTrue(registry.register("id"))
        assertEquals(CompletionResult.ACCEPTED, registry.complete("id", "first"))
        assertEquals(CompletionResult.DUPLICATE, registry.complete("id", "second"))
        assertEquals("first", registry.consume("id"))
    }

    @Test
    fun boundedBufferDropsOldestUtf8() {
        val buffer = BoundedTextBuffer(4)
        buffer.append("a你b")
        assertEquals("你b", buffer.consume())
        assertTrue(buffer.truncated)
        assertEquals(1, buffer.discardedBytes)
        assertEquals(1, buffer.consumeDiscardedBytes())
        assertEquals(0, buffer.discardedBytes)
    }

    @Test
    fun terminalBufferLimitsCombinedStreams() {
        val buffer = TerminalOutputBuffer(5)
        buffer.appendStdout("abc")
        buffer.appendStderr("你")

        val output = buffer.consume()

        assertEquals("bc", output.stdout)
        assertEquals("你", output.stderr)
        assertTrue(output.truncated)
        assertEquals(1, output.discardedBytes)
        assertFalse(buffer.consume().truncated)
    }

    @Test
    fun rootFileRelayPathIsReserved() {
        assertFalse(isSafeRelativePath(""))
        assertTrue(ROOT_FILE_RELATIVE_PATH.isNotBlank())
    }

    @Test
    fun allMcpToolsHaveSchemas() {
        assertEquals(RemoteMcpTools.names, RemoteMcpTools.inputSchemas.keys)
    }
}
