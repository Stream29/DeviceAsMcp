package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.DeviceId
import io.github.stream29.mcp.device.protocol.OperationId
import io.github.stream29.mcp.device.protocol.OperationResult
import io.github.stream29.mcp.device.protocol.OperationResultEnvelope
import io.github.stream29.mcp.device.protocol.OperationResultPayload
import io.github.stream29.mcp.device.protocol.UserId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OperationWaitersTest {
    @Test
    fun acceptsOnlyTheFirstResultFromTheExpectedDeviceAndUser() = runTest {
        val waiters = OperationWaiters()
        val operationId = OperationId("operation")
        val userId = UserId("user")
        val deviceId = DeviceId("device")
        val waiter = assertNotNull(waiters.register(operationId, userId, deviceId))
        val result = OperationResultEnvelope(
            operationId,
            OperationResult.Success(OperationResultPayload.Acknowledged()),
        )

        assertEquals(
            ResultAcceptance.UNKNOWN,
            waiters.complete(UserId("other"), deviceId, result),
        )
        assertEquals(ResultAcceptance.ACCEPTED, waiters.complete(userId, deviceId, result))
        assertEquals(result, waiter.await())
        assertEquals(ResultAcceptance.DUPLICATE, waiters.complete(userId, deviceId, result))

        waiters.remove(operationId)
        assertEquals(ResultAcceptance.DUPLICATE, waiters.complete(userId, deviceId, result))
    }
}
