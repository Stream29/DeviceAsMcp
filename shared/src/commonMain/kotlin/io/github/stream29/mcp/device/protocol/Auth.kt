package io.github.stream29.mcp.device.protocol

import kotlinx.serialization.Serializable

@Serializable
data class PasswordLoginRequest(val username: String, val password: String)

@Serializable
data class RegisterRequest(val username: String, val password: String)

@Serializable
data class AuthenticatedUser(val id: UserId, val username: String, val githubLogin: String? = null)

@Serializable
data class AuthSession(val accessToken: String, val user: AuthenticatedUser)

@Serializable
data class DeviceEnrollmentRequest(val name: String, val platform: String)

@Serializable
data class DeviceEnrollmentToken(val token: String, val expiresAtEpochMillis: Long)

@Serializable
data class DaemonEnrollmentRequest(
    val token: String,
    val name: String,
    val platform: String,
)

@Serializable
data class DeviceCredential(val deviceId: DeviceId, val secret: String)

@Serializable
data class DeviceSummary(
    val id: DeviceId,
    val name: String,
    val platform: String,
    val online: Boolean,
)

@Serializable
data class AuthKeySummary(val id: String, val name: String, val createdAtEpochMillis: Long)

@Serializable
data class CreateAuthKeyRequest(val name: String)

@Serializable
data class CreatedAuthKey(val id: String, val name: String, val token: String)
