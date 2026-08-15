package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.UserId
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceListUpdatesTest {
    @Test
    fun targetsUsersAndCoalescesPendingUpdates() = runTest {
        val subscriptions = DeviceListUpdateSubscriptions()
        val user = UserId("user")
        val otherUser = UserId("other-user")
        val subscription = subscriptions.subscribe(user)

        assertEquals(1, subscriptions.subscriberCount(user))
        subscriptions.notify(otherUser)
        subscriptions.notify(user)
        subscriptions.notify(user)

        withTimeout(1_000) { subscription.awaitUpdate() }
        assertNull(withTimeoutOrNull(50) { subscription.awaitUpdate() })

        subscription.close()
        subscription.close()
        assertEquals(0, subscriptions.subscriberCount(user))
    }
}
