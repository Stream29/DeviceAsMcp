package io.github.stream29.mcp.device.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutingTest {
    @Test
    fun parsesSemanticRoutesWithOptionalTrailingSlash() {
        assertEquals(AppRoute.LOGIN, appRouteForPath("/login"))
        assertEquals(AppRoute.DEVICES, appRouteForPath("/devices/"))
        assertEquals(AppRoute.AUTH_KEYS, appRouteForPath("/auth-keys"))
        assertNull(appRouteForPath("/unknown"))
    }

    @Test
    fun canonicalizesRootAndAuthenticationBoundaries() {
        assertEquals(AppRoute.LOGIN, canonicalAppRoute("/", authenticated = false))
        assertEquals(AppRoute.DEVICES, canonicalAppRoute("/", authenticated = true))
        assertEquals(AppRoute.LOGIN, canonicalAppRoute("/devices", authenticated = false))
        assertEquals(AppRoute.DEVICES, canonicalAppRoute("/login", authenticated = true))
        assertEquals(AppRoute.AUTH_KEYS, canonicalAppRoute("/auth-keys", authenticated = true))
        assertNull(canonicalAppRoute("/unknown", authenticated = true))
    }
}
