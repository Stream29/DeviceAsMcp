package io.github.stream29.mcp.device.web

import io.github.stream29.mcp.device.protocol.ProtocolJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebApiJsonTest {
    @Test
    fun ignoresFieldsAddedAfterTheWebClientWasLoaded() {
        val response = """
            {
              "id": "device-1",
              "name": "workstation",
              "platform": "linux-x64",
              "online": true,
              "description": "field added by a newer server"
            }
        """.trimIndent()

        val device = WebApiJson.decodeFromString<PreviousDeviceSummary>(response)

        assertEquals(
            PreviousDeviceSummary(
                id = "device-1",
                name = "workstation",
                platform = "linux-x64",
                online = true,
            ),
            device,
        )
        assertTrue(WebApiJson.configuration.ignoreUnknownKeys)
        assertFalse(ProtocolJson.configuration.ignoreUnknownKeys)
        assertFailsWith<SerializationException> {
            ProtocolJson.decodeFromString<PreviousDeviceSummary>(response)
        }
    }
}

@Serializable
private data class PreviousDeviceSummary(
    val id: String,
    val name: String,
    val platform: String,
    val online: Boolean,
)
