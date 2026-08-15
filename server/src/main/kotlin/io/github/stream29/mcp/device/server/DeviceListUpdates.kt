package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.UserId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel

internal interface DeviceListUpdatePublisher {
    suspend fun publishDeviceListUpdate(userId: UserId)
}

internal class LocalDeviceListUpdatePublisher(
    private val subscriptions: DeviceListUpdateSubscriptions,
) : DeviceListUpdatePublisher {
    override suspend fun publishDeviceListUpdate(userId: UserId) {
        subscriptions.notify(userId)
    }
}

internal class DeviceListUpdateSubscriptions {
    private val subscriptions = ConcurrentHashMap<UserId, MutableSet<Subscription>>()

    fun subscribe(userId: UserId): Subscription {
        val subscription = Subscription(userId, this)
        subscriptions.compute(userId) { _, existing ->
            (existing ?: ConcurrentHashMap.newKeySet()).apply {
                add(subscription)
            }
        }
        return subscription
    }

    fun notify(userId: UserId) {
        subscriptions[userId]?.forEach(Subscription::notifyUpdate)
    }

    internal fun subscriberCount(userId: UserId): Int = subscriptions[userId]?.size ?: 0

    private fun remove(subscription: Subscription) {
        subscriptions.computeIfPresent(subscription.userId) { _, existing ->
            existing.remove(subscription)
            existing.takeIf { it.isNotEmpty() }
        }
    }

    internal class Subscription internal constructor(
        internal val userId: UserId,
        private val owner: DeviceListUpdateSubscriptions,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()
        private val updates = Channel<Unit>(Channel.CONFLATED)

        internal fun notifyUpdate() {
            updates.trySend(Unit)
        }

        suspend fun awaitUpdate() {
            updates.receive()
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            owner.remove(this)
            updates.close()
        }
    }
}
