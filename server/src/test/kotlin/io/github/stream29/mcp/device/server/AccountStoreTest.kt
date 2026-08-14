package io.github.stream29.mcp.device.server

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountStoreTest {
    @Test
    fun managementSessionsExpireAndEnrollmentTokensAreSingleUse() = runTest {
        var now = 1_000L
        val accounts = InMemoryAccountStore { now }
        val user = assertNotNull(accounts.register("alice", "correct horse battery staple"))
        val session = accounts.issueSession(user)
        val enrollment = accounts.createEnrollmentToken(user.id)

        assertNotNull(accounts.userByToken(session))
        assertNotNull(accounts.consumeEnrollmentToken(enrollment.token))
        assertNull(accounts.consumeEnrollmentToken(enrollment.token))

        now += 30L * 24 * 60 * 60 * 1_000 + 1
        assertNull(accounts.userByToken(session))
    }

    @Test
    fun githubIdentityUsesStableProviderIdInsteadOfLogin() = runTest {
        val accounts = InMemoryAccountStore()

        val original = accounts.findOrCreateGithubUser("1001", "octocat")
        val renamed = accounts.findOrCreateGithubUser("1001", "new-octocat")
        val recycledLogin = accounts.findOrCreateGithubUser("2002", "octocat")

        assertEquals(original.id, renamed.id)
        assertEquals("new-octocat", renamed.githubLogin)
        assertNotEquals(original.id, recycledLogin.id)
        assertEquals("octocat", recycledLogin.githubLogin)
    }

    @Test
    fun deviceRenameRequiresOwnership() = runTest {
        val accounts = InMemoryAccountStore()
        val owner = assertNotNull(accounts.register("owner", "correct horse battery staple"))
        val other = assertNotNull(accounts.register("other", "correct horse battery staple"))
        val device = accounts.enrollDevice(owner.id, "original", "linux-x64")

        assertTrue(accounts.renameDevice(owner.id, device.deviceId, "  workstation  "))
        assertEquals("workstation", accounts.devices(owner.id).single().name)
        assertFalse(accounts.renameDevice(other.id, device.deviceId, "not allowed"))
        assertEquals("workstation", accounts.devices(owner.id).single().name)
    }
}
