package io.github.stream29.mcp.device.protocol

object DeviceListUpdateEvent {
    const val NAME = "device_list.update"
}

object DeviceListUpdateTopology {
    const val EXCHANGE = "device_as_mcp.device_list_updates.v1"
    const val QUEUE_PREFIX = "device_as_mcp.device_list_updates."
    const val ROUTING_KEY = DeviceListUpdateEvent.NAME
    const val USER_ID_HEADER = "user-id"

    fun queue(instanceId: InstanceId): String = QUEUE_PREFIX + instanceId.value
}
