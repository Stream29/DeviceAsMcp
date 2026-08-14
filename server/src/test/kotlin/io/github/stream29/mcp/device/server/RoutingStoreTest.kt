package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.ConnectionId
import io.github.stream29.mcp.device.protocol.DeviceId
import io.github.stream29.mcp.device.protocol.InstanceId
import io.github.stream29.mcp.device.protocol.FileManifest
import io.github.stream29.mcp.device.protocol.FileManifestEntry
import io.github.stream29.mcp.device.protocol.FileTransferPlan
import io.github.stream29.mcp.device.protocol.FileTransferRecord
import io.github.stream29.mcp.device.protocol.FileTransferStatus
import io.github.stream29.mcp.device.protocol.ManifestEntryType
import io.github.stream29.mcp.device.protocol.OperationErrorCode
import io.github.stream29.mcp.device.protocol.OperationId
import io.github.stream29.mcp.device.protocol.TerminalSessionId
import io.github.stream29.mcp.device.protocol.TransferId
import io.github.stream29.mcp.device.protocol.UserId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingStoreTest {
    @Test
    fun ownerUsesLeaseAndFencing() = runTest {
        var now = 1_000L
        val store = InMemoryRoutingStore { now }
        val device = DeviceId("device")
        val first = DeviceOwner(InstanceId("one"), ConnectionId("connection-one"), 31_000)
        val stale = first.copy(connectionId = ConnectionId("stale"))

        assertTrue(store.claimDevice(device, first))
        assertFalse(store.claimDevice(device, stale))
        assertFalse(store.renewDevice(device, stale))
        assertEquals(first, store.deviceOwner(device))

        now = 32_000
        assertNull(store.deviceOwner(device))
        assertTrue(store.claimDevice(device, stale.copy(expiresAtEpochMillis = 62_000)))
    }

    @Test
    fun operationOriginExpires() = runTest {
        var now = 0L
        val store = InMemoryRoutingStore { now }
        val operation = OperationId("operation")
        store.putOperationOrigin(operation, InstanceId("origin"), 10)
        assertEquals(InstanceId("origin"), store.operationOrigin(operation))
        now = 11
        assertNull(store.operationOrigin(operation))
    }

    @Test
    fun ephemeralValuesCanBeConsumedOnce() = runTest {
        var now = 0L
        val store = InMemoryRoutingStore { now }
        store.putEphemeral("code", "value", 10)
        assertEquals("value", store.ephemeral("code"))
        assertEquals("value", store.consumeEphemeral("code"))
        assertNull(store.consumeEphemeral("code"))

        store.putEphemeral("expired", "value", 10)
        now = 11
        assertNull(store.ephemeral("expired"))
    }

    @Test
    fun ephemeralTransitionIsAtomicAndRefreshesExpiry() = runTest {
        var now = 0L
        val store = InMemoryRoutingStore { now }
        store.putEphemeral("approval", "pending", 10)

        assertFalse(store.compareAndSetEphemeral("approval", "wrong", "approved", 100))
        assertTrue(store.compareAndSetEphemeral("approval", "pending", "approved", 100))
        assertFalse(store.compareAndSetEphemeral("approval", "pending", "denied", 100))
        assertEquals("approved", store.ephemeral("approval"))

        now = 99
        assertEquals("approved", store.ephemeral("approval"))
        now = 101
        assertNull(store.ephemeral("approval"))
    }

    @Test
    fun terminalRoutesSupportRunningAndEndedLifetimes() = runTest {
        var now = 0L
        val store = InMemoryRoutingStore { now }
        val session = TerminalSessionId("session")
        val route = TerminalRoute(UserId("user"), DeviceId("device"))

        store.putTerminalRoute(session, route, null)
        now = Long.MAX_VALUE / 2
        assertEquals(route, store.terminalRoute(session))

        store.putTerminalRoute(session, route, 10)
        now += 11
        assertNull(store.terminalRoute(session))
    }

    @Test
    fun transferProgressRefreshesTtlAndCoordinatorLossOnlyFailsRunning() = runTest {
        var now = 1_000L
        val store = InMemoryRoutingStore { now }
        val transferId = TransferId("transfer")
        val record = FileTransferRecord(
            transferId = transferId,
            userId = UserId("user"),
            sourceDeviceId = DeviceId("source"),
            sourcePath = "/source",
            destinationDeviceId = DeviceId("destination"),
            destinationPath = "/destination",
            relayInstanceId = InstanceId("relay"),
        )
        val manifest = FileManifest(
            ManifestEntryType.DIRECTORY,
            listOf(FileManifestEntry("file.txt", ManifestEntryType.FILE, 4)),
        )
        val plan = FileTransferPlan(listOf("file.txt"), 0)

        assertTrue(store.createTransfer(record))
        assertTrue(store.putTransferManifest(transferId, manifest))
        assertTrue(store.putTransferPlan(transferId, plan))
        assertFalse(
            store.failRunningTransfer(
                transferId,
                OperationErrorCode.SERVER_INSTANCE_LOST,
                "lost",
            ),
        )
        assertTrue(store.updateTransfer(transferId, FileTransferStatus.RUNNING))
        now += 29 * 60 * 1_000L
        assertTrue(store.markTransferFileSuccess(transferId, "file.txt"))
        now += 2 * 60 * 1_000L
        assertEquals(1, store.transfer(transferId)?.successfulFiles)
        assertTrue(
            store.failRunningTransfer(
                transferId,
                OperationErrorCode.SERVER_INSTANCE_LOST,
                "lost",
            ),
        )
        assertEquals(FileTransferStatus.FAILED, store.transfer(transferId)?.status)
        assertFalse(
            store.failRunningTransfer(
                transferId,
                OperationErrorCode.SERVER_INSTANCE_LOST,
                "again",
            ),
        )
    }
}
