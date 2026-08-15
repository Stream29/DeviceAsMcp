package io.github.stream29.mcp.device.web

internal enum class AppRoute(
    val path: String,
    val title: String,
    val requiresAuthentication: Boolean,
) {
    LOGIN("/login", "Sign in · DeviceAsMcp", false),
    DEVICES("/devices", "Devices · DeviceAsMcp", true),
    AUTH_KEYS("/connect", "Connect to MCP · DeviceAsMcp", true),
}

internal fun appRouteForPath(path: String): AppRoute? {
    val normalized = when {
        path.isBlank() -> "/"
        path == "/" -> path
        else -> path.trimEnd('/')
    }
    return when (normalized) {
        LEGACY_AUTH_KEYS_PATH -> AppRoute.AUTH_KEYS
        else -> AppRoute.entries.firstOrNull { it.path == normalized }
    }
}

internal fun canonicalAppRoute(path: String, authenticated: Boolean): AppRoute? {
    val route = appRouteForPath(path)
    return when {
        path == "/" -> if (authenticated) AppRoute.DEVICES else AppRoute.LOGIN
        route?.requiresAuthentication == true && !authenticated -> AppRoute.LOGIN
        route == AppRoute.LOGIN && authenticated -> AppRoute.DEVICES
        else -> route
    }
}

private const val LEGACY_AUTH_KEYS_PATH = "/auth-keys"
