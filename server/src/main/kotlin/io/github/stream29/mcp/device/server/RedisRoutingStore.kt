package io.github.stream29.mcp.device.server

import io.github.stream29.mcp.device.protocol.ConnectionId
import io.github.stream29.mcp.device.protocol.DeviceId
import io.github.stream29.mcp.device.protocol.FileManifest
import io.github.stream29.mcp.device.protocol.FileTransferPlan
import io.github.stream29.mcp.device.protocol.FileTransferRecord
import io.github.stream29.mcp.device.protocol.FileTransferStatus
import io.github.stream29.mcp.device.protocol.InstanceId
import io.github.stream29.mcp.device.protocol.OperationErrorCode
import io.github.stream29.mcp.device.protocol.OperationId
import io.github.stream29.mcp.device.protocol.ProtocolJson
import io.github.stream29.mcp.device.protocol.TerminalSessionId
import io.github.stream29.mcp.device.protocol.TransferId
import io.github.stream29.mcp.device.protocol.UserId
import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class RedisRoutingStore(redisUrl: String) : RoutingStore, AutoCloseable {
    private val client = RedisClient.create(redisUrl)
    private val connection = client.connect()
    private val commands = connection.sync()

    override suspend fun claimDevice(deviceId: DeviceId, owner: DeviceOwner): Boolean = io {
        commands.set(ownerKey(deviceId), encode(owner), SetArgs().nx().px(ttl(owner))) == "OK"
    }

    override suspend fun renewDevice(deviceId: DeviceId, owner: DeviceOwner): Boolean = io {
        commands.eval<Long>(
            COMPARE_AND_EXPIRE,
            io.lettuce.core.ScriptOutputType.INTEGER,
            arrayOf(ownerKey(deviceId)),
            encode(owner),
            ttl(owner).toString(),
        ) == 1L
    }

    override suspend fun releaseDevice(deviceId: DeviceId, owner: DeviceOwner): Boolean = io {
        commands.eval<Long>(
            COMPARE_AND_DELETE,
            io.lettuce.core.ScriptOutputType.INTEGER,
            arrayOf(ownerKey(deviceId)),
            encode(owner),
        ) == 1L
    }

    override suspend fun deviceOwner(deviceId: DeviceId): DeviceOwner? = io {
        commands.get(ownerKey(deviceId))?.let(::decode)
    }

    override suspend fun putOperationOrigin(
        operationId: OperationId,
        origin: InstanceId,
        expiresAtEpochMillis: Long,
    ) {
        io {
            val ttl = (expiresAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(1)
            commands.set(originKey(operationId), origin.value, SetArgs().px(ttl))
        }
    }

    override suspend fun operationOrigin(operationId: OperationId): InstanceId? = io {
        commands.get(originKey(operationId))?.let(::InstanceId)
    }

    override suspend fun removeOperationOrigin(operationId: OperationId) {
        io { commands.del(originKey(operationId)) }
    }

    override suspend fun putEphemeral(key: String, value: String, ttlMillis: Long) {
        require(key.isNotBlank() && ttlMillis > 0)
        io { commands.set(ephemeralKey(key), value, SetArgs().px(ttlMillis)) }
    }

    override suspend fun ephemeral(key: String): String? = io {
        commands.get(ephemeralKey(key))
    }

    override suspend fun consumeEphemeral(key: String): String? = io {
        commands.eval<String>(
            GET_AND_DELETE,
            io.lettuce.core.ScriptOutputType.VALUE,
            arrayOf(ephemeralKey(key)),
        )
    }

    override suspend fun compareAndSetEphemeral(
        key: String,
        expected: String,
        value: String,
        ttlMillis: Long,
    ): Boolean {
        require(key.isNotBlank() && ttlMillis > 0)
        return io {
            commands.eval<Long>(
                COMPARE_AND_SET_WITH_EXPIRY,
                io.lettuce.core.ScriptOutputType.INTEGER,
                arrayOf(ephemeralKey(key)),
                expected,
                value,
                ttlMillis.toString(),
            ) == 1L
        }
    }

    override suspend fun putTerminalRoute(sessionId: TerminalSessionId, route: TerminalRoute, ttlMillis: Long?) {
        io {
            val value = "${route.userId.value}|${route.deviceId.value}"
            if (ttlMillis == null) {
                commands.set(terminalKey(sessionId), value)
            } else {
                commands.set(terminalKey(sessionId), value, SetArgs().px(ttlMillis))
            }
        }
    }

    override suspend fun terminalRoute(sessionId: TerminalSessionId): TerminalRoute? = io {
        commands.get(terminalKey(sessionId))?.split('|')?.takeIf { it.size == 2 }?.let {
            TerminalRoute(UserId(it[0]), DeviceId(it[1]))
        }
    }

    override suspend fun removeTerminalRoute(sessionId: TerminalSessionId) {
        io { commands.del(terminalKey(sessionId)) }
    }

    override suspend fun createTransfer(record: FileTransferRecord): Boolean = io {
        commands.eval<Long>(
            CREATE_TRANSFER,
            io.lettuce.core.ScriptOutputType.INTEGER,
            arrayOf(transferKey(record.transferId)),
            ProtocolJson.encodeToString(FileTransferRecord.serializer(), record),
            TRANSFER_TTL_MILLIS.toString(),
        ) == 1L
    }

    override suspend fun putTransferManifest(transferId: TransferId, manifest: FileManifest): Boolean = io {
        commands.eval<Long>(
            PUT_MANIFEST,
            io.lettuce.core.ScriptOutputType.INTEGER,
            arrayOf(transferKey(transferId)),
            ProtocolJson.encodeToString(FileManifest.serializer(), manifest),
            TRANSFER_TTL_MILLIS.toString(),
        ) == 1L
    }

    override suspend fun putTransferPlan(transferId: TransferId, plan: FileTransferPlan): Boolean = io {
        commands.eval<Long>(
            PUT_PLAN,
            io.lettuce.core.ScriptOutputType.INTEGER,
            arrayOf(transferKey(transferId)),
            ProtocolJson.encodeToString(FileTransferPlan.serializer(), plan),
            TRANSFER_TTL_MILLIS.toString(),
        ) == 1L
    }

    override suspend fun transfer(transferId: TransferId): FileTransferRecord? = io {
        val record = commands.hget(transferKey(transferId), META_RECORD) ?: return@io null
        val successfulFiles = commands.hkeys(transferKey(transferId)).count { it.startsWith(FILE_PREFIX) }
        ProtocolJson.decodeFromString(FileTransferRecord.serializer(), record).copy(successfulFiles = successfulFiles)
    }

    override suspend fun transferManifest(transferId: TransferId): FileManifest? = io {
        commands.hget(transferKey(transferId), META_MANIFEST)
            ?.let { ProtocolJson.decodeFromString(FileManifest.serializer(), it) }
    }

    override suspend fun transferPlan(transferId: TransferId): FileTransferPlan? = io {
        commands.hget(transferKey(transferId), META_PLAN)
            ?.let { ProtocolJson.decodeFromString(FileTransferPlan.serializer(), it) }
    }

    override suspend fun updateTransfer(
        transferId: TransferId,
        status: FileTransferStatus,
        errorCode: OperationErrorCode?,
        message: String?,
    ): Boolean = io {
        commands.eval<Long>(
            UPDATE_TRANSFER,
            io.lettuce.core.ScriptOutputType.INTEGER,
            arrayOf(transferKey(transferId)),
            status.name,
            errorCode?.name.orEmpty(),
            message.orEmpty(),
            TRANSFER_TTL_MILLIS.toString(),
        ) == 1L
    }

    override suspend fun markTransferFileSuccess(transferId: TransferId, relativePath: String): Boolean = io {
        commands.eval<Long>(
            MARK_FILE_SUCCESS,
            io.lettuce.core.ScriptOutputType.INTEGER,
            arrayOf(transferKey(transferId)),
            "$FILE_PREFIX$relativePath",
            TRANSFER_TTL_MILLIS.toString(),
        ) == 1L
    }

    override suspend fun failRunningTransfer(
        transferId: TransferId,
        errorCode: OperationErrorCode,
        message: String,
    ): Boolean = io {
        commands.eval<Long>(
            FAIL_RUNNING_TRANSFER,
            io.lettuce.core.ScriptOutputType.INTEGER,
            arrayOf(transferKey(transferId)),
            errorCode.name,
            message,
            TRANSFER_TTL_MILLIS.toString(),
        ) == 1L
    }

    override suspend fun removeTransfer(transferId: TransferId) {
        io { commands.del(transferKey(transferId)) }
    }

    override suspend fun isReady(): Boolean = io {
        runCatching {
            connection.isOpen && commands.ping().equals("PONG", ignoreCase = true)
        }.getOrDefault(false)
    }

    override fun close() {
        connection.close()
        client.shutdown()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun encode(owner: DeviceOwner): String = "${owner.instanceId.value}|${owner.connectionId.value}"

    private fun decode(value: String): DeviceOwner? {
        val parts = value.split('|')
        if (parts.size != 2) return null
        return DeviceOwner(InstanceId(parts[0]), ConnectionId(parts[1]), System.currentTimeMillis() + 1)
    }

    private fun ttl(owner: DeviceOwner): Long = (owner.expiresAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(1)

    private fun ownerKey(deviceId: DeviceId) = "device-as-mcp:device:${deviceId.value}:owner"
    private fun originKey(operationId: OperationId) = "device-as-mcp:operation:${operationId.value}:origin"
    private fun ephemeralKey(key: String) = "device-as-mcp:ephemeral:$key"
    private fun terminalKey(sessionId: TerminalSessionId) = "device-as-mcp:terminal:${sessionId.value}:route"
    private fun transferKey(transferId: TransferId) = "device-as-mcp:transfer:${transferId.value}"

    companion object {
        private const val TRANSFER_TTL_MILLIS = 30 * 60 * 1_000L
        private const val META_RECORD = "meta:record"
        private const val META_MANIFEST = "meta:manifest"
        private const val META_PLAN = "meta:plan"
        private const val FILE_PREFIX = "file:"
        private const val CREATE_TRANSFER = """
            if redis.call('exists', KEYS[1]) == 1 then return 0 end
            redis.call('hset', KEYS[1], 'meta:record', ARGV[1])
            redis.call('pexpire', KEYS[1], ARGV[2])
            return 1
        """
        private const val PUT_MANIFEST = """
            if redis.call('exists', KEYS[1]) == 0 then return 0 end
            if redis.call('hexists', KEYS[1], 'meta:manifest') == 1 then return 0 end
            redis.call('hset', KEYS[1], 'meta:manifest', ARGV[1])
            redis.call('pexpire', KEYS[1], ARGV[2])
            return 1
        """
        private const val PUT_PLAN = """
            if redis.call('exists', KEYS[1]) == 0 then return 0 end
            if redis.call('hexists', KEYS[1], 'meta:plan') == 1 then return 0 end
            redis.call('hset', KEYS[1], 'meta:plan', ARGV[1])
            redis.call('pexpire', KEYS[1], ARGV[2])
            return 1
        """
        private const val UPDATE_TRANSFER = """
            local value = redis.call('hget', KEYS[1], 'meta:record')
            if not value then return 0 end
            local record = cjson.decode(value)
            record['status'] = ARGV[1]
            if ARGV[2] == '' then record['errorCode'] = nil else record['errorCode'] = ARGV[2] end
            if ARGV[3] == '' then record['message'] = nil else record['message'] = ARGV[3] end
            redis.call('hset', KEYS[1], 'meta:record', cjson.encode(record))
            redis.call('pexpire', KEYS[1], ARGV[4])
            return 1
        """
        private const val MARK_FILE_SUCCESS = """
            local value = redis.call('hget', KEYS[1], 'meta:record')
            if not value then return 0 end
            local record = cjson.decode(value)
            if record['status'] ~= 'RUNNING' then return 0 end
            redis.call('hset', KEYS[1], ARGV[1], 'success')
            redis.call('pexpire', KEYS[1], ARGV[2])
            return 1
        """
        private const val FAIL_RUNNING_TRANSFER = """
            local value = redis.call('hget', KEYS[1], 'meta:record')
            if not value then return 0 end
            local record = cjson.decode(value)
            if record['status'] ~= 'RUNNING' then return 0 end
            record['status'] = 'FAILED'
            record['errorCode'] = ARGV[1]
            record['message'] = ARGV[2]
            redis.call('hset', KEYS[1], 'meta:record', cjson.encode(record))
            redis.call('pexpire', KEYS[1], ARGV[3])
            return 1
        """
        private const val COMPARE_AND_EXPIRE = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('pexpire', KEYS[1], ARGV[2])
            end
            return 0
        """
        private const val COMPARE_AND_DELETE = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
        """
        private const val GET_AND_DELETE = """
            local value = redis.call('get', KEYS[1])
            if value then redis.call('del', KEYS[1]) end
            return value
        """
        private const val COMPARE_AND_SET_WITH_EXPIRY = """
            if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
        """
    }
}
