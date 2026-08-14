package io.github.stream29.mcp.device.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.stream29.mcp.device.protocol.AuthKeySummary
import io.github.stream29.mcp.device.protocol.AuthenticatedUser
import io.github.stream29.mcp.device.protocol.CreatedAuthKey
import io.github.stream29.mcp.device.protocol.DeviceCredential
import io.github.stream29.mcp.device.protocol.DeviceEnrollmentToken
import io.github.stream29.mcp.device.protocol.DeviceId
import io.github.stream29.mcp.device.protocol.DeviceSummary
import io.github.stream29.mcp.device.protocol.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.sql.ResultSet
import java.util.Base64
import java.util.UUID

internal class PostgresAccountStore(
    jdbcUrl: String,
    username: String,
    password: String,
) : AccountStore, AutoCloseable {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.username = username
        this.password = password
        maximumPoolSize = 8
        minimumIdle = 1
    })
    private val random = SecureRandom()

    init { migrate() }

    override suspend fun register(username: String, password: String): AuthenticatedUser? = io {
        val normalized = username.trim().lowercase()
        if (normalized.length !in 1..100 || password.length !in 8..1_024) return@io null
        val id = UserId(UUID.randomUUID().toString())
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "insert into app_user(id, username, password_hash) values (?, ?, ?) on conflict do nothing",
            ).use {
                it.setObject(1, UUID.fromString(id.value))
                it.setString(2, normalized)
                it.setString(3, BCrypt.hashpw(password, BCrypt.gensalt(12)))
                if (it.executeUpdate() != 1) return@io null
            }
        }
        AuthenticatedUser(id, normalized)
    }

    override suspend fun authenticate(username: String, password: String): AuthenticatedUser? = io {
        if (username.length > 100 || password.length > 1_024) return@io null
        dataSource.connection.use { connection ->
            connection.prepareStatement("select id, username, password_hash, github_login from app_user where username = ?").use {
                it.setString(1, username.trim().lowercase())
                it.executeQuery().use { result ->
                    if (!result.next() || !BCrypt.checkpw(password, result.getString("password_hash"))) return@io null
                    result.toUser()
                }
            }
        }
    }

    override suspend fun userByToken(token: String): AuthenticatedUser? = io {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select u.id, u.username, u.github_login
                from access_token t join app_user u on u.id = t.user_id
                where t.token_hash = ? and t.kind = 'session'
                  and t.revoked_at is null and (t.expires_at is null or t.expires_at > now())
                """.trimIndent(),
            ).use {
                it.setString(1, sha256(token))
                it.executeQuery().use { result -> if (result.next()) result.toUser() else null }
            }
        }
    }

    override suspend fun mcpPrincipal(token: String): McpTokenPrincipal? = io {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select u.id, u.username, u.github_login, t.audience, t.scopes
                from access_token t join app_user u on u.id = t.user_id
                where t.token_hash = ? and t.kind in ('auth_key', 'oauth_access')
                  and t.revoked_at is null and (t.expires_at is null or t.expires_at > now())
                  and t.audience is not null and t.scopes is not null
                """.trimIndent(),
            ).use {
                it.setString(1, sha256(token))
                it.executeQuery().use { result -> if (result.next()) result.toMcpPrincipal() else null }
            }
        }
    }

    override suspend fun issueSession(user: AuthenticatedUser): String = createToken(
        user.id,
        null,
        "session",
        null,
        emptySet(),
        System.currentTimeMillis() + SESSION_TTL_MILLIS,
    ).token

    override suspend fun revokeSession(token: String) {
        io {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "update access_token set revoked_at = now() where token_hash = ? and kind = 'session' and revoked_at is null",
                ).use {
                    it.setString(1, sha256(token))
                    it.executeUpdate()
                }
            }
        }
    }

    override suspend fun issueMcpAccessToken(
        userId: UserId,
        audience: String,
        scopes: Set<String>,
        expiresAtEpochMillis: Long,
    ): String =
        createToken(userId, null, "oauth_access", audience, scopes, expiresAtEpochMillis).token

    override suspend fun createAuthKey(
        userId: UserId,
        name: String,
        audience: String,
        scopes: Set<String>,
    ): CreatedAuthKey {
        require(name.trim().length in 1..100) { "Auth key name must contain 1 to 100 characters" }
        val token = createToken(
            userId,
            name.ifBlank { "auth key" },
            "auth_key",
            audience,
            scopes,
            null,
        )
        return CreatedAuthKey(token.id, token.name ?: "auth key", token.token)
    }

    override suspend fun authKeys(userId: UserId): List<AuthKeySummary> = io {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select id, name, extract(epoch from created_at) * 1000 as created_ms from access_token where user_id = ? and kind = 'auth_key' and revoked_at is null order by created_at desc",
            ).use {
                it.setObject(1, UUID.fromString(userId.value))
                it.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(AuthKeySummary(result.getObject("id").toString(), result.getString("name"), result.getLong("created_ms")))
                    }
                }
            }
        }
    }

    override suspend fun revokeAuthKey(userId: UserId, keyId: String): Boolean = io {
        dataSource.connection.use { connection ->
            connection.prepareStatement("update access_token set revoked_at = now() where id = ? and user_id = ? and kind = 'auth_key' and revoked_at is null").use {
                it.setObject(1, UUID.fromString(keyId))
                it.setObject(2, UUID.fromString(userId.value))
                it.executeUpdate() == 1
            }
        }
    }

    override suspend fun createEnrollmentToken(userId: UserId): DeviceEnrollmentToken = io {
        val token = randomToken()
        val expiresAt = System.currentTimeMillis() + ENROLLMENT_TTL_MILLIS
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "insert into enrollment_token(token_hash, user_id, expires_at) values (?, ?, ?)",
            ).use {
                it.setString(1, sha256(token))
                it.setObject(2, UUID.fromString(userId.value))
                it.setTimestamp(3, java.sql.Timestamp(expiresAt))
                it.executeUpdate()
            }
        }
        DeviceEnrollmentToken(token, expiresAt)
    }

    override suspend fun consumeEnrollmentToken(token: String): UserId? = io {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val userId = connection.prepareStatement(
                    """
                    delete from enrollment_token
                    where token_hash = ? and expires_at > now()
                    returning user_id
                    """.trimIndent(),
                ).use {
                    it.setString(1, sha256(token))
                    it.executeQuery().use { result ->
                        if (result.next()) UserId(result.getObject(1).toString()) else null
                    }
                }
                connection.commit()
                userId
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    override suspend fun enrollDevice(userId: UserId, name: String, platform: String): DeviceCredential = io {
        require(name.trim().length in 1..100) { "Device name must contain 1 to 100 characters" }
        require(platform.trim().length in 1..100) { "Platform must contain 1 to 100 characters" }
        val id = DeviceId(UUID.randomUUID().toString())
        val secret = randomToken()
        dataSource.connection.use { connection ->
            connection.prepareStatement("insert into device(id, user_id, name, platform, secret_hash) values (?, ?, ?, ?, ?)").use {
                it.setObject(1, UUID.fromString(id.value))
                it.setObject(2, UUID.fromString(userId.value))
                it.setString(3, name)
                it.setString(4, platform)
                it.setString(5, sha256(secret))
                it.executeUpdate()
            }
        }
        DeviceCredential(id, secret)
    }

    override suspend fun devices(userId: UserId): List<DeviceSummary> = io {
        dataSource.connection.use { connection ->
            connection.prepareStatement("select id, name, platform from device where user_id = ? order by created_at").use {
                it.setObject(1, UUID.fromString(userId.value))
                it.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(DeviceSummary(DeviceId(result.getObject("id").toString()), result.getString("name"), result.getString("platform"), false))
                    }
                }
            }
        }
    }

    override suspend fun renameDevice(userId: UserId, deviceId: DeviceId, name: String): Boolean = io {
        val normalized = name.trim()
        require(normalized.length in 1..100) {
            "Device name must contain 1 to 100 characters"
        }
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "update device set name = ? where id = ? and user_id = ?",
            ).use {
                it.setString(1, normalized)
                it.setObject(2, UUID.fromString(deviceId.value))
                it.setObject(3, UUID.fromString(userId.value))
                it.executeUpdate() == 1
            }
        }
    }

    override suspend fun deviceUser(deviceId: DeviceId): UserId? = io {
        dataSource.connection.use { connection ->
            connection.prepareStatement("select user_id from device where id = ?").use {
                it.setObject(1, UUID.fromString(deviceId.value))
                it.executeQuery().use { result -> if (result.next()) UserId(result.getObject(1).toString()) else null }
            }
        }
    }

    override suspend fun authenticateDevice(deviceId: DeviceId, secret: String): Boolean = io {
        dataSource.connection.use { connection ->
            connection.prepareStatement("select 1 from device where id = ? and secret_hash = ?").use {
                it.setObject(1, UUID.fromString(deviceId.value))
                it.setString(2, sha256(secret))
                it.executeQuery().use(ResultSet::next)
            }
        }
    }

    override suspend fun findOrCreateGithubUser(
        githubId: String,
        githubLogin: String,
    ): AuthenticatedUser = io {
        require(githubId.isNotBlank() && githubId.length <= 100) { "GitHub user ID is invalid" }
        val normalized = githubLogin.trim().lowercase()
        require(normalized.length in 1..100) { "GitHub login is invalid" }
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "select id, username, github_login from app_user where github_id = ?",
                ).use {
                    it.setString(1, githubId)
                    it.executeQuery().use { result ->
                        if (result.next()) {
                            val id = result.getObject("id")
                            connection.prepareStatement(
                                "update app_user set github_login = null where github_login = ? and id <> ?",
                            ).use { update ->
                                update.setString(1, normalized)
                                update.setObject(2, id)
                                update.executeUpdate()
                            }
                            connection.prepareStatement(
                                "update app_user set github_login = ? where id = ?",
                            ).use { update ->
                                update.setString(1, normalized)
                                update.setObject(2, id)
                                update.executeUpdate()
                            }
                            connection.commit()
                            return@io AuthenticatedUser(
                                UserId(id.toString()),
                                result.getString("username"),
                                normalized,
                            )
                        }
                    }
                }
                connection.prepareStatement(
                    "update app_user set github_login = null where github_login = ?",
                ).use {
                    it.setString(1, normalized)
                    it.executeUpdate()
                }
                var username = normalized
                var suffix = 1
                while (true) {
                    val id = UUID.randomUUID()
                    val inserted = connection.prepareStatement(
                        """
                        insert into app_user(id, username, password_hash, github_login, github_id)
                        values (?, ?, ?, ?, ?) on conflict do nothing
                        """.trimIndent(),
                    ).use {
                        it.setObject(1, id)
                        it.setString(2, username)
                        it.setString(3, BCrypt.hashpw(randomToken(), BCrypt.gensalt(12)))
                        it.setString(4, normalized)
                        it.setString(5, githubId)
                        it.executeUpdate() == 1
                    }
                    if (inserted) {
                        connection.commit()
                        return@io AuthenticatedUser(UserId(id.toString()), username, normalized)
                    }
                    connection.prepareStatement(
                        "select id, username, github_login from app_user where github_id = ?",
                    ).use {
                        it.setString(1, githubId)
                        it.executeQuery().use { result ->
                            if (result.next()) {
                                connection.commit()
                                return@io result.toUser()
                            }
                        }
                    }
                    connection.prepareStatement(
                        "update app_user set github_login = null where github_login = ?",
                    ).use {
                        it.setString(1, normalized)
                        it.executeUpdate()
                    }
                    username = "$normalized-github-${suffix++}"
                }
                error("unreachable")
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    override suspend fun isReady(): Boolean = io {
        runCatching {
            !dataSource.isClosed && dataSource.connection.use { it.isValid(2) }
        }.getOrDefault(false)
    }

    override fun close() = dataSource.close()

    private data class Token(val id: String, val name: String?, val token: String)

    private suspend fun createToken(
        userId: UserId,
        name: String?,
        kind: String,
        audience: String?,
        scopes: Set<String>,
        expiresAtEpochMillis: Long?,
    ): Token = io {
        require((audience == null && scopes.isEmpty()) || (audience?.isNotBlank() == true && scopes.isNotEmpty()))
        val id = UUID.randomUUID()
        val token = randomToken()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "insert into access_token(id, user_id, name, token_hash, kind, audience, scopes, expires_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use {
                it.setObject(1, id)
                it.setObject(2, UUID.fromString(userId.value))
                it.setString(3, name)
                it.setString(4, sha256(token))
                it.setString(5, kind)
                it.setString(6, audience)
                it.setString(7, scopes.takeIf { values -> values.isNotEmpty() }?.sorted()?.joinToString(" "))
                it.setObject(8, expiresAtEpochMillis?.let { value -> java.sql.Timestamp(value) })
                it.executeUpdate()
            }
        }
        Token(id.toString(), name, token)
    }

    private fun migrate() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("""
                    create table if not exists app_user(
                        id uuid primary key,
                        username text not null unique,
                        password_hash text not null,
                        github_login text unique,
                        github_id text unique,
                        created_at timestamptz not null default now()
                    )
                """.trimIndent())
                statement.executeUpdate("alter table app_user add column if not exists github_id text")
                statement.executeUpdate(
                    "create unique index if not exists app_user_github_id_idx on app_user(github_id)",
                )
                statement.executeUpdate("""
                    create table if not exists access_token(
                        id uuid primary key,
                        user_id uuid not null references app_user(id) on delete cascade,
                        name text,
                        token_hash text not null unique,
                        kind text not null,
                        audience text,
                        scopes text,
                        created_at timestamptz not null default now(),
                        expires_at timestamptz,
                        revoked_at timestamptz
                    )
                """.trimIndent())
                statement.executeUpdate("alter table access_token add column if not exists audience text")
                statement.executeUpdate("alter table access_token add column if not exists scopes text")
                statement.executeUpdate("""
                    create table if not exists device(
                        id uuid primary key,
                        user_id uuid not null references app_user(id) on delete cascade,
                        name text not null,
                        platform text not null,
                        secret_hash text not null,
                        created_at timestamptz not null default now()
                    )
                """.trimIndent())
                statement.executeUpdate("""
                    create table if not exists enrollment_token(
                        token_hash text primary key,
                        user_id uuid not null references app_user(id) on delete cascade,
                        expires_at timestamptz not null,
                        created_at timestamptz not null default now()
                    )
                """.trimIndent())
                statement.executeUpdate("create index if not exists access_token_hash_idx on access_token(token_hash)")
                statement.executeUpdate("create index if not exists device_user_idx on device(user_id)")
            }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun randomToken(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun ResultSet.toUser() = AuthenticatedUser(UserId(getObject("id").toString()), getString("username"), getString("github_login"))
    private fun ResultSet.toMcpPrincipal() = McpTokenPrincipal(
        user = toUser(),
        audience = getString("audience"),
        scopes = getString("scopes").split(' ').filter(String::isNotBlank).toSet(),
    )

    companion object {
        private const val ENROLLMENT_TTL_MILLIS = 10 * 60 * 1_000L
        private const val SESSION_TTL_MILLIS = 30L * 24 * 60 * 60 * 1_000
    }
}
