@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.mcp.device.web

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.browser.window
import org.w3c.dom.events.Event

internal val LocalReducedMotion = compositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = Color(0xff4355b9),
    onPrimary = Color(0xffffffff),
    primaryContainer = Color(0xffdee0ff),
    onPrimaryContainer = Color(0xff001159),
    inversePrimary = Color(0xffbac3ff),
    secondary = Color(0xff5b5d72),
    onSecondary = Color(0xffffffff),
    secondaryContainer = Color(0xffe0e1f9),
    onSecondaryContainer = Color(0xff181a2c),
    tertiary = Color(0xff77536d),
    onTertiary = Color(0xffffffff),
    tertiaryContainer = Color(0xffffd7f2),
    onTertiaryContainer = Color(0xff2d1227),
    background = Color(0xfffbf8ff),
    onBackground = Color(0xff1b1b21),
    surface = Color(0xfffbf8ff),
    onSurface = Color(0xff1b1b21),
    surfaceVariant = Color(0xffe3e1ec),
    onSurfaceVariant = Color(0xff46464f),
    inverseSurface = Color(0xff303036),
    inverseOnSurface = Color(0xfff2f0f7),
    error = Color(0xffba1a1a),
    onError = Color(0xffffffff),
    errorContainer = Color(0xffffdad6),
    onErrorContainer = Color(0xff410002),
    outline = Color(0xff777680),
    outlineVariant = Color(0xffc7c5d0),
    surfaceBright = Color(0xfffbf8ff),
    surfaceDim = Color(0xffdbd9e0),
    surfaceContainerLowest = Color(0xffffffff),
    surfaceContainerLow = Color(0xfff5f2fa),
    surfaceContainer = Color(0xffefedf4),
    surfaceContainerHigh = Color(0xffe9e7ee),
    surfaceContainerHighest = Color(0xffe3e1e8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xffbac3ff),
    onPrimary = Color(0xff0b2473),
    primaryContainer = Color(0xff2a3c9f),
    onPrimaryContainer = Color(0xffdee0ff),
    inversePrimary = Color(0xff4355b9),
    secondary = Color(0xffc4c5dd),
    onSecondary = Color(0xff2d2f42),
    secondaryContainer = Color(0xff444559),
    onSecondaryContainer = Color(0xffe0e1f9),
    tertiary = Color(0xffe6bad7),
    onTertiary = Color(0xff45263d),
    tertiaryContainer = Color(0xff5d3c54),
    onTertiaryContainer = Color(0xffffd7f2),
    background = Color(0xff121318),
    onBackground = Color(0xffe4e1e9),
    surface = Color(0xff121318),
    onSurface = Color(0xffe4e1e9),
    surfaceVariant = Color(0xff46464f),
    onSurfaceVariant = Color(0xffc7c5d0),
    inverseSurface = Color(0xffe4e1e9),
    inverseOnSurface = Color(0xff303036),
    error = Color(0xffffb4ab),
    onError = Color(0xff690005),
    errorContainer = Color(0xff93000a),
    onErrorContainer = Color(0xffffdad6),
    outline = Color(0xff91909a),
    outlineVariant = Color(0xff46464f),
    surfaceBright = Color(0xff38383e),
    surfaceDim = Color(0xff121318),
    surfaceContainerLowest = Color(0xff0d0e13),
    surfaceContainerLow = Color(0xff1b1b21),
    surfaceContainer = Color(0xff1f1f25),
    surfaceContainerHigh = Color(0xff29292f),
    surfaceContainerHighest = Color(0xff34343a),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.35.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
internal fun DeviceAsMcpTheme(content: @Composable () -> Unit) {
    val preferences = rememberBrowserPreferences()
    MaterialTheme(
        colorScheme = if (preferences.darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalReducedMotion provides preferences.reducedMotion,
            content = content,
        )
    }
}

@Composable
private fun rememberBrowserPreferences(): BrowserPreferences {
    val darkQuery = remember { window.matchMedia("(prefers-color-scheme: dark)") }
    val motionQuery = remember { window.matchMedia("(prefers-reduced-motion: reduce)") }
    var preferences by remember {
        mutableStateOf(
            BrowserPreferences(
                darkTheme = darkQuery.matches,
                reducedMotion = motionQuery.matches,
            ),
        )
    }

    DisposableEffect(darkQuery, motionQuery) {
        val listener: (Event) -> Unit = {
            preferences = BrowserPreferences(
                darkTheme = darkQuery.matches,
                reducedMotion = motionQuery.matches,
            )
        }
        darkQuery.addEventListener("change", listener)
        motionQuery.addEventListener("change", listener)
        onDispose {
            darkQuery.removeEventListener("change", listener)
            motionQuery.removeEventListener("change", listener)
        }
    }
    return preferences
}

@Composable
internal fun BrandMark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val semanticModifier = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    }
    Surface(
        modifier = semanticModifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        AppIcon(
            symbol = AppSymbol.CONNECT,
            modifier = Modifier.padding(9.dp).size(22.dp),
        )
    }
}

@Composable
internal fun AppIcon(
    symbol: AppSymbol,
    modifier: Modifier = Modifier,
    color: Color = androidx.compose.material3.LocalContentColor.current,
    contentDescription: String? = null,
) {
    val semanticModifier = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    }
    Canvas(semanticModifier) {
        when (symbol) {
            AppSymbol.DEVICES -> drawDevices(color)
            AppSymbol.CONNECT -> drawConnect(color)
            AppSymbol.DOWNLOAD -> drawDownload(color)
            AppSymbol.KEY -> drawKey(color)
            AppSymbol.COPY -> drawCopy(color)
            AppSymbol.LOGOUT -> drawLogout(color)
            AppSymbol.CHECK -> drawCheck(color)
            AppSymbol.ERROR -> drawError(color)
        }
    }
}

internal enum class AppSymbol {
    DEVICES,
    CONNECT,
    DOWNLOAD,
    KEY,
    COPY,
    LOGOUT,
    CHECK,
    ERROR,
}

private data class BrowserPreferences(
    val darkTheme: Boolean,
    val reducedMotion: Boolean,
)

private fun DrawScope.iconStroke(width: Float = 1.9f) = Stroke(
    width = width * size.minDimension / 24f,
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
)

private fun DrawScope.point(x: Float, y: Float): Offset = Offset(
    x = x * size.width / 24f,
    y = y * size.height / 24f,
)

private fun DrawScope.scaledSize(width: Float, height: Float): Size = Size(
    width = width * size.width / 24f,
    height = height * size.height / 24f,
)

private fun DrawScope.drawDevices(color: Color) {
    val stroke = iconStroke()
    val radius = size.minDimension / 24f * 1.5f
    drawRoundRect(
        color = color,
        topLeft = point(2f, 4f),
        size = scaledSize(15f, 11f),
        cornerRadius = CornerRadius(radius),
        style = stroke,
    )
    drawLine(color, point(7f, 19f), point(12f, 19f), stroke.width, StrokeCap.Round)
    drawLine(color, point(9.5f, 15f), point(9.5f, 19f), stroke.width, StrokeCap.Round)
    drawRoundRect(
        color = color,
        topLeft = point(16f, 8f),
        size = scaledSize(6f, 12f),
        cornerRadius = CornerRadius(radius),
        style = stroke,
    )
    drawLine(color, point(18.5f, 17.5f), point(19.5f, 17.5f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawConnect(color: Color) {
    val stroke = iconStroke()
    drawLine(color, point(6f, 12f), point(12f, 7f), stroke.width, StrokeCap.Round)
    drawLine(color, point(12f, 7f), point(18f, 12f), stroke.width, StrokeCap.Round)
    drawLine(color, point(12f, 7f), point(12f, 18f), stroke.width, StrokeCap.Round)
    drawCircle(color, radius = size.minDimension / 24f * 2.6f, center = point(6f, 12f), style = stroke)
    drawCircle(color, radius = size.minDimension / 24f * 2.6f, center = point(18f, 12f), style = stroke)
    drawCircle(color, radius = size.minDimension / 24f * 2.6f, center = point(12f, 19f), style = stroke)
    drawCircle(color, radius = size.minDimension / 24f * 2.6f, center = point(12f, 5f), style = stroke)
}

private fun DrawScope.drawDownload(color: Color) {
    val stroke = iconStroke()
    drawLine(color, point(12f, 3f), point(12f, 15f), stroke.width, StrokeCap.Round)
    drawLine(color, point(7.5f, 10.5f), point(12f, 15f), stroke.width, StrokeCap.Round)
    drawLine(color, point(16.5f, 10.5f), point(12f, 15f), stroke.width, StrokeCap.Round)
    val path = Path().apply {
        moveTo(point(4f, 15f).x, point(4f, 15f).y)
        lineTo(point(4f, 20f).x, point(4f, 20f).y)
        lineTo(point(20f, 20f).x, point(20f, 20f).y)
        lineTo(point(20f, 15f).x, point(20f, 15f).y)
    }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.drawKey(color: Color) {
    val stroke = iconStroke()
    drawCircle(color, radius = size.minDimension / 24f * 4f, center = point(8f, 12f), style = stroke)
    drawLine(color, point(12f, 12f), point(21f, 12f), stroke.width, StrokeCap.Round)
    drawLine(color, point(17f, 12f), point(17f, 15f), stroke.width, StrokeCap.Round)
    drawLine(color, point(20f, 12f), point(20f, 14f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawCopy(color: Color) {
    val stroke = iconStroke()
    val radius = size.minDimension / 24f * 1.5f
    drawRoundRect(
        color,
        topLeft = point(7f, 7f),
        size = scaledSize(12f, 13f),
        cornerRadius = CornerRadius(radius),
        style = stroke,
    )
    drawRoundRect(
        color,
        topLeft = point(4f, 4f),
        size = scaledSize(12f, 13f),
        cornerRadius = CornerRadius(radius),
        style = stroke,
    )
}

private fun DrawScope.drawLogout(color: Color) {
    val stroke = iconStroke()
    drawLine(color, point(4f, 4f), point(12f, 4f), stroke.width, StrokeCap.Round)
    drawLine(color, point(4f, 4f), point(4f, 20f), stroke.width, StrokeCap.Round)
    drawLine(color, point(4f, 20f), point(12f, 20f), stroke.width, StrokeCap.Round)
    drawLine(color, point(10f, 12f), point(21f, 12f), stroke.width, StrokeCap.Round)
    drawLine(color, point(17f, 8f), point(21f, 12f), stroke.width, StrokeCap.Round)
    drawLine(color, point(17f, 16f), point(21f, 12f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawCheck(color: Color) {
    val stroke = iconStroke(width = 2.2f)
    drawLine(color, point(5f, 12.5f), point(10f, 17f), stroke.width, StrokeCap.Round)
    drawLine(color, point(10f, 17f), point(19f, 7f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawError(color: Color) {
    val stroke = iconStroke()
    drawCircle(color, radius = size.minDimension / 24f * 9f, center = point(12f, 12f), style = stroke)
    drawLine(color, point(12f, 7f), point(12f, 13f), stroke.width, StrokeCap.Round)
    drawCircle(color, radius = size.minDimension / 24f, center = point(12f, 17f))
}
