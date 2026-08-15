@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.mcp.device.web

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.mcp.device.protocol.AuthKeySummary
import io.github.stream29.mcp.device.protocol.CreatedAuthKey
import io.github.stream29.mcp.device.protocol.DeviceSummary

internal enum class AuthMode {
    SIGN_IN,
    REGISTER,
}

private enum class McpAuthorizationMode {
    OAUTH,
    ACCESS_KEY,
}

@Composable
internal fun LoginScreen(
    authorizeTarget: String?,
    busy: Boolean,
    error: String?,
    onSubmit: (AuthMode, String, String) -> Unit,
    onGitHub: () -> Unit,
    onClearError: () -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
    ) {
        val expanded = maxWidth >= 900.dp && maxHeight >= 620.dp
        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                LoginHero(Modifier.weight(0.9f).fillMaxHeight())
                Box(
                    Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    LoginPanel(
                        authorizeTarget = authorizeTarget,
                        busy = busy,
                        error = error,
                        onSubmit = onSubmit,
                        onGitHub = onGitHub,
                        onClearError = onClearError,
                        modifier = Modifier.padding(48.dp).widthIn(max = 500.dp),
                    )
                }
            }
        } else {
            Box(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter,
            ) {
                LoginPanel(
                    authorizeTarget = authorizeTarget,
                    busy = busy,
                    error = error,
                    onSubmit = onSubmit,
                    onGitHub = onGitHub,
                    onClearError = onClearError,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 32.dp)
                        .widthIn(max = 500.dp),
                    showCompactBrand = true,
                )
            }
        }
    }
}

@Composable
private fun LoginPanel(
    authorizeTarget: String?,
    busy: Boolean,
    error: String?,
    onSubmit: (AuthMode, String, String) -> Unit,
    onGitHub: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
    showCompactBrand: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LoginForm(
            authorizeTarget = authorizeTarget,
            busy = busy,
            error = error,
            onSubmit = onSubmit,
            onClearError = onClearError,
            modifier = Modifier.fillMaxWidth(),
            showCompactBrand = showCompactBrand,
        )
        OutlinedButton(
            enabled = !busy,
            onClick = onGitHub,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text("Login by GitHub")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoginHero(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(
                    colors.primaryContainer,
                    colors.tertiaryContainer,
                ),
            ),
        ),
    ) {
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 560.dp).padding(64.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(contentDescription = "DeviceAsMcp")
                Spacer(Modifier.width(14.dp))
                Text(
                    "DeviceAsMcp",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onPrimaryContainer,
                )
            }
            Text(
                "Your devices, ready for remote MCP.",
                style = MaterialTheme.typography.displaySmall,
                color = colors.onPrimaryContainer,
            )
            Text(
                "Connect trusted machines, run remote tools, and manage access from one place.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onPrimaryContainer.copy(alpha = 0.82f),
                modifier = Modifier.widthIn(max = 480.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HeroPill("Authenticated")
                HeroPill("Cross-platform")
                HeroPill("Remote MCP")
            }
        }
    }
}

@Composable
private fun HeroPill(label: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun LoginForm(
    authorizeTarget: String?,
    busy: Boolean,
    error: String?,
    onSubmit: (AuthMode, String, String) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
    showCompactBrand: Boolean = false,
) {
    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val canSubmit = username.isNotBlank() &&
        password.isNotBlank() &&
        (mode == AuthMode.SIGN_IN || password.length >= MIN_REGISTRATION_PASSWORD_LENGTH)

    fun submit() {
        if (canSubmit && !busy) {
            onSubmit(mode, username.trim(), password)
        }
    }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (showCompactBrand) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(contentDescription = "DeviceAsMcp")
                    Spacer(Modifier.width(12.dp))
                    Text("DeviceAsMcp", style = MaterialTheme.typography.titleLarge)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (mode == AuthMode.SIGN_IN) "Welcome back" else "Create your account",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    if (authorizeTarget == null) {
                        if (mode == AuthMode.SIGN_IN) {
                            "Sign in to manage your devices and MCP connections."
                        } else {
                            "Create an account to enroll your first device."
                        }
                    } else {
                        "Sign in to continue the MCP authorization request."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (authorizeTarget != null) {
                InfoSurface(
                    title = "MCP authorization pending",
                    text = "After sign-in, you will return to the client that requested access.",
                    symbol = AppSymbol.CONNECT,
                )
            }

            error?.let {
                ErrorBanner(message = it, onDismiss = onClearError)
            }

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { value ->
                        if (value.length <= MAX_USERNAME_LENGTH) username = value
                        if (error != null) onClearError()
                    },
                    label = { Text("Username") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Username"
                    },
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { value ->
                        if (value.length <= MAX_PASSWORD_LENGTH) password = value
                        if (error != null) onClearError()
                    },
                    label = { Text("Password") },
                    supportingText = if (mode == AuthMode.REGISTER) {
                        {
                            Text(
                                if (password.isEmpty() || password.length >= MIN_REGISTRATION_PASSWORD_LENGTH) {
                                    "Use at least 8 characters."
                                } else {
                                    "${MIN_REGISTRATION_PASSWORD_LENGTH - password.length} more characters required."
                                },
                            )
                        }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Password"
                    },
                )
            }

            Button(
                enabled = canSubmit && !busy,
                onClick = ::submit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = androidx.compose.material3.LocalContentColor.current,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (mode == AuthMode.SIGN_IN) "Sign in" else "Create account")
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (mode == AuthMode.SIGN_IN) "New to DeviceAsMcp?" else "Already have an account?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    enabled = !busy,
                    onClick = {
                        mode = if (mode == AuthMode.SIGN_IN) AuthMode.REGISTER else AuthMode.SIGN_IN
                        password = ""
                        onClearError()
                    },
                ) {
                    Text(if (mode == AuthMode.SIGN_IN) "Create account" else "Sign in")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManagementShell(
    route: AppRoute,
    userName: String,
    loading: Boolean,
    error: String?,
    snackbarHostState: SnackbarHostState,
    onNavigate: (AppRoute) -> Unit,
    onSignOut: () -> Unit,
    onDismissError: () -> Unit,
    content: @Composable (AppRoute, Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < NAVIGATION_RAIL_BREAKPOINT
        if (compact) {
            ManagementScaffold(
                route = route,
                userName = userName,
                loading = loading,
                error = error,
                compact = true,
                snackbarHostState = snackbarHostState,
                onNavigate = onNavigate,
                onSignOut = onSignOut,
                onDismissError = onDismissError,
                content = content,
            )
        } else {
            Row(Modifier.fillMaxSize()) {
                AppNavigationRail(
                    route = route,
                    onNavigate = onNavigate,
                )
                ManagementScaffold(
                    route = route,
                    userName = userName,
                    loading = loading,
                    error = error,
                    compact = false,
                    snackbarHostState = snackbarHostState,
                    onNavigate = onNavigate,
                    onSignOut = onSignOut,
                    onDismissError = onDismissError,
                    modifier = Modifier.weight(1f),
                    content = content,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagementScaffold(
    route: AppRoute,
    userName: String,
    loading: Boolean,
    error: String?,
    compact: Boolean,
    snackbarHostState: SnackbarHostState,
    onNavigate: (AppRoute) -> Unit,
    onSignOut: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (AppRoute, Modifier) -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (compact) {
                            BrandMark(
                                modifier = Modifier.size(36.dp),
                                contentDescription = "DeviceAsMcp",
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            "DeviceAsMcp",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                },
                actions = {
                    if (!compact && userName.isNotBlank()) {
                        Text(
                            userName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 180.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(
                        enabled = !loading,
                        onClick = onSignOut,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        AppIcon(
                            AppSymbol.LOGOUT,
                            Modifier.size(20.dp),
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Sign out")
                    }
                    Spacer(Modifier.width(if (compact) 4.dp else 16.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            )
        },
        bottomBar = {
            if (compact) {
                AppNavigationBar(route = route, onNavigate = onNavigate)
            }
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(12.dp),
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(visible = error != null) {
                    error?.let {
                        ErrorBanner(
                            message = it,
                            onDismiss = onDismissError,
                            modifier = Modifier.fillMaxWidth().padding(
                                horizontal = if (compact) 16.dp else 32.dp,
                                vertical = 12.dp,
                            ),
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Crossfade(
                        targetState = route,
                        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 180),
                        label = "Management destination",
                    ) { displayedRoute ->
                        content(displayedRoute, Modifier.fillMaxSize())
                    }
                }
            }
            if (loading) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun AppNavigationBar(
    route: AppRoute,
    onNavigate: (AppRoute) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        AppDestinations.forEach { destination ->
            NavigationBarItem(
                selected = route == destination.route,
                onClick = { onNavigate(destination.route) },
                icon = {
                    AppIcon(
                        destination.symbol,
                        Modifier.size(24.dp),
                        contentDescription = null,
                    )
                },
                label = { Text(destination.label) },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
private fun AppNavigationRail(
    route: AppRoute,
    onNavigate: (AppRoute) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        header = {
            BrandMark(
                modifier = Modifier.padding(top = 12.dp, bottom = 28.dp),
                contentDescription = "DeviceAsMcp",
            )
        },
    ) {
        AppDestinations.forEach { destination ->
            NavigationRailItem(
                selected = route == destination.route,
                onClick = { onNavigate(destination.route) },
                icon = {
                    AppIcon(
                        destination.symbol,
                        Modifier.size(24.dp),
                        contentDescription = null,
                    )
                },
                label = { Text(destination.railLabel) },
                alwaysShowLabel = true,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun DevicesScreen(
    devices: List<DeviceSummary>,
    selectedPlatform: InstallPlatform,
    generatedCommand: GeneratedInstallCommand?,
    busy: Boolean,
    onPlatformSelected: (InstallPlatform) -> Unit,
    onGenerateCommand: () -> Unit,
    onRename: (DeviceSummary, String) -> Unit,
    onRevoke: (DeviceSummary) -> Unit,
    onCopyCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ManagementPage(modifier) {
        PageHeader(
            title = "Devices",
            description = "Enroll the machines you want to expose through remote MCP.",
            supporting = if (devices.isEmpty()) {
                "No devices enrolled"
            } else {
                "${devices.count(DeviceSummary::online)} online · ${devices.size} total"
            },
        )

        SupportingPaneLayout(
            supportingFirstOnCompact = devices.isEmpty(),
            main = {
                DeviceInventory(
                    devices = devices,
                    busy = busy,
                    onRename = onRename,
                    onRevoke = onRevoke,
                )
            },
            supporting = {
                DeviceInstaller(
                    selectedPlatform = selectedPlatform,
                    generatedCommand = generatedCommand,
                    busy = busy,
                    onPlatformSelected = onPlatformSelected,
                    onGenerateCommand = onGenerateCommand,
                    onCopyCommand = onCopyCommand,
                )
            },
        )
    }
}

@Composable
private fun DeviceInventory(
    devices: List<DeviceSummary>,
    busy: Boolean,
    onRename: (DeviceSummary, String) -> Unit,
    onRevoke: (DeviceSummary) -> Unit,
) {
    var pendingRevocation by remember { mutableStateOf<DeviceSummary?>(null) }

    pendingRevocation?.let { device ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingRevocation = null },
            icon = {
                AppIcon(
                    AppSymbol.DEVICES,
                    Modifier.size(24.dp),
                    contentDescription = null,
                )
            },
            title = { Text("Revoke this device?") },
            text = {
                Text(
                    "“${device.name}” will disconnect immediately and its saved credentials will stop working. " +
                        "Re-enrollment is required to connect it again.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        pendingRevocation = null
                        onRevoke(device)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Revoke device")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { pendingRevocation = null },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    SectionCard(
        title = "Your devices",
        description = if (devices.isEmpty()) {
            "Enrolled devices appear here."
        } else {
            "Rename or revoke devices and see whether their daemon is connected."
        },
        symbol = AppSymbol.DEVICES,
    ) {
        if (devices.isEmpty()) {
            EmptyState(
                symbol = AppSymbol.DEVICES,
                title = "No devices connected",
                text = "Choose a platform in Add a device and run the generated command on that machine.",
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                devices.forEach { device ->
                    DeviceItem(
                        device = device,
                        enabled = !busy,
                        onRename = { onRename(device, it) },
                        onRevoke = { pendingRevocation = device },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceItem(
    device: DeviceSummary,
    enabled: Boolean,
    onRename: (String) -> Unit,
    onRevoke: () -> Unit,
) {
    var editing by remember(device.id, device.name) { mutableStateOf(false) }
    var name by remember(device.id, device.name) { mutableStateOf(device.name) }
    val normalized = name.trim()
    val canSave = enabled &&
        normalized.isNotEmpty() &&
        normalized.length <= MAX_DEVICE_NAME_LENGTH &&
        normalized != device.name

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    AppIcon(
                        AppSymbol.DEVICES,
                        Modifier.padding(10.dp).size(24.dp),
                        contentDescription = null,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        platformLabel(device.platform),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OnlineStatus(device.online)
            }

            AnimatedVisibility(visible = editing) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            if (it.length <= MAX_DEVICE_NAME_LENGTH) name = it
                        },
                        label = { Text("Device name") },
                        singleLine = true,
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (canSave) onRename(normalized)
                            },
                        ),
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = "Device name"
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            enabled = enabled,
                            onClick = {
                                name = device.name
                                editing = false
                            },
                        ) {
                            Text("Cancel")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = canSave,
                            onClick = { onRename(normalized) },
                        ) {
                            Text("Save name")
                        }
                    }
                }
            }

            if (!editing) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        enabled = enabled,
                        onClick = onRevoke,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Revoke device")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = enabled,
                        onClick = { editing = true },
                    ) {
                        Text("Rename device")
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineStatus(online: Boolean) {
    val container = if (online) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = if (online) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier.semantics {
            stateDescription = if (online) "Online" else "Offline"
        },
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(7.dp).clip(CircleShape).background(content),
            )
            Text(
                if (online) "Online" else "Offline",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceInstaller(
    selectedPlatform: InstallPlatform,
    generatedCommand: GeneratedInstallCommand?,
    busy: Boolean,
    onPlatformSelected: (InstallPlatform) -> Unit,
    onGenerateCommand: () -> Unit,
    onCopyCommand: (String) -> Unit,
) {
    SectionCard(
        title = "Add a device",
        description = "Generate a secure, single-use command for the target machine.",
        symbol = AppSymbol.DOWNLOAD,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            "Platform",
            style = MaterialTheme.typography.labelLarge,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InstallPlatform.entries.forEach { platform ->
                FilterChip(
                    selected = platform == selectedPlatform,
                    enabled = !busy,
                    onClick = { onPlatformSelected(platform) },
                    label = { Text(platform.selectorLabel) },
                    leadingIcon = if (platform == selectedPlatform) {
                        {
                            AppIcon(
                                AppSymbol.CHECK,
                                Modifier.size(18.dp),
                                contentDescription = null,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        Button(
            enabled = !busy,
            onClick = onGenerateCommand,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            AppIcon(
                AppSymbol.DOWNLOAD,
                Modifier.size(20.dp),
                contentDescription = null,
            )
            Spacer(Modifier.width(10.dp))
            Text("Generate install command")
        }

        AnimatedVisibility(visible = generatedCommand != null) {
            generatedCommand?.let { command ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoSurface(
                        title = "Single-use command",
                        text = "Expires ${formatRelativeExpiry(command.expiresAtEpochMillis)}. " +
                            "It downloads and verifies the daemon from GitHub.",
                        symbol = AppSymbol.KEY,
                    )
                    CodeBlock(command.command)
                    OutlinedButton(
                        onClick = { onCopyCommand(command.command) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        AppIcon(
                            AppSymbol.COPY,
                            Modifier.size(20.dp),
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Copy command")
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConnectScreen(
    endpoint: String,
    authKeys: List<AuthKeySummary>,
    createdKey: CreatedAuthKey?,
    busy: Boolean,
    onCreateKey: (String) -> Unit,
    onRevokeKey: (AuthKeySummary) -> Unit,
    onCopyEndpoint: () -> Unit,
    onCopyKey: (String) -> Unit,
    onCopyCodexConfig: (CreatedAuthKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    var authorizationMode by remember { mutableStateOf(McpAuthorizationMode.OAUTH) }

    ManagementPage(modifier) {
        PageHeader(
            title = "Connect to MCP",
            description = "Add this remote server to an MCP client and authorize access to your devices.",
            supporting = "OAuth or access key",
        )

        AuthorizationMethodSelector(
            selected = authorizationMode,
            enabled = !busy,
            onSelected = { authorizationMode = it },
        )

        Crossfade(
            targetState = authorizationMode,
            animationSpec = tween(durationMillis = 220),
            label = "MCP authorization method",
        ) { selected ->
            when (selected) {
                McpAuthorizationMode.OAUTH -> {
                    EndpointSection(
                        endpoint = endpoint,
                        onCopyEndpoint = onCopyEndpoint,
                    )
                }

                McpAuthorizationMode.ACCESS_KEY -> {
                    AccessKeyModeContent(
                        endpoint = endpoint,
                        authKeys = authKeys,
                        createdKey = createdKey,
                        busy = busy,
                        onCreateKey = onCreateKey,
                        onRevokeKey = onRevokeKey,
                        onCopyKey = onCopyKey,
                        onCopyCodexConfig = onCopyCodexConfig,
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthorizationMethodSelector(
    selected: McpAuthorizationMode,
    enabled: Boolean,
    onSelected: (McpAuthorizationMode) -> Unit,
) {
    SectionCard(
        title = "Authorization method",
        description = "Choose exactly one way for your MCP client to authenticate.",
        symbol = AppSymbol.CONNECT,
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) {
            McpAuthorizationMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selected == mode,
                    enabled = enabled,
                    onClick = { onSelected(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = McpAuthorizationMode.entries.size,
                    ),
                    label = {
                        Text(
                            when (mode) {
                                McpAuthorizationMode.OAUTH -> "OAuth"
                                McpAuthorizationMode.ACCESS_KEY -> "Access key"
                            },
                        )
                    },
                )
            }
        }
        Text(
            when (selected) {
                McpAuthorizationMode.OAUTH ->
                    "Recommended. Your MCP client opens a browser so you can approve access without copying a secret."

                McpAuthorizationMode.ACCESS_KEY ->
                    "Use a named long-lived key only when your MCP client cannot complete OAuth."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EndpointSection(
    endpoint: String,
    onCopyEndpoint: () -> Unit,
) {
    SectionCard(
        title = "Connect with OAuth",
        description = "Use this endpoint in an OAuth-capable remote MCP client.",
        symbol = AppSymbol.CONNECT,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "SERVER URL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                )
                SelectionContainer {
                    Text(
                        endpoint,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
                OutlinedButton(
                    onClick = onCopyEndpoint,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    AppIcon(
                        AppSymbol.COPY,
                        Modifier.size(20.dp),
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Copy endpoint")
                }
            }
        }

        Text(
            "How to connect",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        NumberedStep(
            number = 1,
            title = "Add a remote MCP server",
            text = "Choose the Streamable HTTP or remote HTTP transport in your client.",
        )
        NumberedStep(
            number = 2,
            title = "Paste the endpoint",
            text = "Use the server URL shown above. Do not append a device identifier.",
        )
        NumberedStep(
            number = 3,
            title = "Authorize in your browser",
            text = "Your MCP client opens this site so you can sign in and approve access.",
        )
        InfoSurface(
            title = "OAuth is recommended",
            text = "It avoids copying long-lived credentials and follows the remote MCP authorization flow.",
            symbol = AppSymbol.CHECK,
        )
    }
}

@Composable
private fun NumberedStep(
    number: Int,
    title: String,
    text: String,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccessKeyModeContent(
    endpoint: String,
    authKeys: List<AuthKeySummary>,
    createdKey: CreatedAuthKey?,
    busy: Boolean,
    onCreateKey: (String) -> Unit,
    onRevokeKey: (AuthKeySummary) -> Unit,
    onCopyKey: (String) -> Unit,
    onCopyCodexConfig: (CreatedAuthKey) -> Unit,
) {
    var pendingRevocation by remember { mutableStateOf<AuthKeySummary?>(null) }
    var keyName by remember(createdKey?.id) { mutableStateOf("") }
    val normalizedName = keyName.trim()

    pendingRevocation?.let { key ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingRevocation = null },
            icon = {
                AppIcon(
                    AppSymbol.KEY,
                    Modifier.size(24.dp),
                    contentDescription = null,
                )
            },
            title = { Text("Revoke this access key?") },
            text = {
                Text(
                    "Clients using “${key.name}” will immediately lose access. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        pendingRevocation = null
                        onRevokeKey(key)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Revoke key")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { pendingRevocation = null },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    SectionCard(
        title = "Connect with an access key",
        description = "Create a named credential, then copy a ready-to-use client configuration.",
        symbol = AppSymbol.KEY,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        OutlinedTextField(
            value = keyName,
            enabled = !busy,
            onValueChange = { value ->
                keyName = value.take(MAX_ACCESS_KEY_NAME_LENGTH)
            },
            label = { Text("Key name") },
            placeholder = { Text("For example: Codex on laptop") },
            supportingText = {
                Text("${keyName.length}/$MAX_ACCESS_KEY_NAME_LENGTH")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!busy && normalizedName.isNotEmpty()) {
                        onCreateKey(normalizedName)
                    }
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            enabled = !busy && normalizedName.isNotEmpty(),
            onClick = { onCreateKey(normalizedName) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            AppIcon(
                AppSymbol.KEY,
                Modifier.size(20.dp),
                contentDescription = null,
            )
            Spacer(Modifier.width(10.dp))
            Text("Create access key")
        }

        AnimatedVisibility(visible = createdKey != null) {
            createdKey?.let { key ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Copy “${key.name}” now",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "The full key is shown only in this browser session. The Codex configuration includes the endpoint and bearer key.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        CodeBlock(
                            value = key.token,
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        )
                        Button(
                            onClick = { onCopyCodexConfig(key) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            AppIcon(
                                AppSymbol.COPY,
                                Modifier.size(20.dp),
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Copy Codex config")
                        }
                        OutlinedButton(
                            onClick = { onCopyKey(key.token) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            AppIcon(
                                AppSymbol.COPY,
                                Modifier.size(20.dp),
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Copy access key")
                        }
                    }
                }
            }
        }

        InfoSurface(
            title = "MCP endpoint",
            text = endpoint,
            symbol = AppSymbol.CONNECT,
        )

        HorizontalDivider()

        Text(
            "Active keys",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (authKeys.isEmpty()) {
            Text(
                "No access keys. OAuth connections do not appear in this list.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                authKeys.forEach { key ->
                    AccessKeyItem(
                        key = key,
                        enabled = !busy,
                        onRevoke = { pendingRevocation = key },
                    )
                }
            }
        }

        Text(
            "Treat access keys like passwords. Revoke any key you no longer recognize or use.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccessKeyItem(
    key: AuthKeySummary,
    enabled: Boolean,
    onRevoke: () -> Unit,
) {
    OutlinedCard(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppIcon(
                    AppSymbol.KEY,
                    Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentDescription = null,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        key.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Created ${formatDate(key.createdAtEpochMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    enabled = enabled,
                    onClick = onRevoke,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Revoke")
                }
            }
        }
    }
}

@Composable
internal fun NotFoundScreen(
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        ElevatedCard(
            Modifier.padding(20.dp).widthIn(max = 520.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                BrandMark(contentDescription = null)
                Text(
                    "Page not found",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "This management route does not exist.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onHome) {
                    Text("Go to devices")
                }
            }
        }
    }
}

@Composable
private fun ManagementPage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val horizontalPadding = when {
            maxWidth < 600.dp -> 16.dp
            maxWidth < 1000.dp -> 28.dp
            else -> 40.dp
        }
        Box(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 1280.dp)
                    .padding(horizontal = horizontalPadding, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun PageHeader(
    title: String,
    description: String,
    supporting: String,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 620.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PageTitle(title, description)
                SupportingLabel(supporting)
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    PageTitle(title, description)
                }
                SupportingLabel(supporting)
            }
        }
    }
}

@Composable
private fun PageTitle(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 720.dp),
        )
    }
}

@Composable
private fun SupportingLabel(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun SupportingPaneLayout(
    supportingFirstOnCompact: Boolean = false,
    main: @Composable () -> Unit,
    supporting: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= SUPPORTING_PANE_BREAKPOINT) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.weight(1.65f)) { main() }
                Box(Modifier.weight(1f)) { supporting() }
            }
        } else {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (supportingFirstOnCompact) {
                    supporting()
                    main()
                } else {
                    main()
                    supporting()
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    description: String,
    symbol: AppSymbol,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    AppIcon(
                        symbol,
                        Modifier.padding(9.dp).size(22.dp),
                        contentDescription = null,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun EmptyState(
    symbol: AppSymbol,
    title: String,
    text: String,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            AppIcon(
                symbol,
                Modifier.padding(18.dp).size(32.dp),
                contentDescription = null,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 420.dp),
        )
    }
}

@Composable
private fun InfoSurface(
    title: String,
    text: String,
    symbol: AppSymbol,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AppIcon(
                symbol,
                Modifier.size(22.dp),
                contentDescription = null,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Assertive
        },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                AppSymbol.ERROR,
                Modifier.size(22.dp),
                contentDescription = null,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun CodeBlock(
    value: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(14.dp),
        ) {
            SelectionContainer {
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 8,
                )
            }
        }
    }
}

private fun platformLabel(platform: String): String = when (platform.lowercase()) {
    "linux-x64" -> "Linux x64"
    "linux-arm64" -> "Linux ARM64"
    "macos-arm64" -> "macOS Apple Silicon"
    "windows-x64" -> "Windows x64"
    else -> platform
}

private fun formatDate(epochMillis: Long): String =
    js("new Date(Number(epochMillis)).toLocaleDateString(undefined, {dateStyle: 'medium'})")

private fun formatRelativeExpiry(epochMillis: Long): String =
    js(
        "(() => { const remaining = Number(epochMillis) - Date.now(); " +
            "if (remaining <= 0) return 'now'; " +
            "const minutes = Math.max(1, Math.ceil(remaining / 60000)); " +
            "return 'in ' + minutes + ' minute' + (minutes === 1 ? '' : 's'); })()",
    )

private data class AppDestination(
    val route: AppRoute,
    val label: String,
    val railLabel: String,
    val symbol: AppSymbol,
)

private val AppDestinations = listOf(
    AppDestination(
        route = AppRoute.DEVICES,
        label = "Devices",
        railLabel = "Devices",
        symbol = AppSymbol.DEVICES,
    ),
    AppDestination(
        route = AppRoute.AUTH_KEYS,
        label = "Connect to MCP",
        railLabel = "Connect",
        symbol = AppSymbol.CONNECT,
    ),
)

private val NAVIGATION_RAIL_BREAKPOINT = 600.dp
private val SUPPORTING_PANE_BREAKPOINT = 900.dp
private const val MIN_REGISTRATION_PASSWORD_LENGTH = 8
private const val MAX_USERNAME_LENGTH = 100
private const val MAX_PASSWORD_LENGTH = 1_024
