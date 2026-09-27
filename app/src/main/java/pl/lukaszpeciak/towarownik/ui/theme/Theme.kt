package pl.lukaszpeciak.towarownik.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object TowarownikColorTokens {
    val Background = Color(0xFF17110F)
    val Surface = Color(0xFF211815)
    val SurfaceRaised = Color(0xFF2B201C)
    val SurfaceHighlight = Color(0xFF352720)
    val Outline = Color(0xFF49362E)
    val TextPrimary = Color(0xFFF3E8DE)
    val TextSecondary = Color(0xFFBDAA9E)
    val Accent = Color(0xFFE58A3F)
    val AccentLight = Color(0xFFF2AC68)
    val Success = Color(0xFF91A77F)
    val Error = Color(0xFFDE7468)
}

@Immutable
internal data class TowarownikSemanticColors(
    val surfaceRaised: Color,
    val surfaceHighlight: Color,
    val accentLight: Color,
    val success: Color,
    val errorMuted: Color,
)

private val WarmModularSemanticColors = TowarownikSemanticColors(
    surfaceRaised = TowarownikColorTokens.SurfaceRaised,
    surfaceHighlight = TowarownikColorTokens.SurfaceHighlight,
    accentLight = TowarownikColorTokens.AccentLight,
    success = TowarownikColorTokens.Success,
    errorMuted = TowarownikColorTokens.Error.copy(alpha = 0.16f),
)

private val LocalTowarownikSemanticColors =
    staticCompositionLocalOf { WarmModularSemanticColors }

internal val MaterialTheme.towarownikColors: TowarownikSemanticColors
    @Composable
    get() = LocalTowarownikSemanticColors.current

private val WarmModularColorScheme = darkColorScheme(
    primary = TowarownikColorTokens.Accent,
    onPrimary = TowarownikColorTokens.Background,
    primaryContainer = TowarownikColorTokens.SurfaceHighlight,
    onPrimaryContainer = TowarownikColorTokens.TextPrimary,
    secondary = TowarownikColorTokens.AccentLight,
    onSecondary = TowarownikColorTokens.Background,
    secondaryContainer = TowarownikColorTokens.SurfaceRaised,
    onSecondaryContainer = TowarownikColorTokens.TextPrimary,
    tertiary = TowarownikColorTokens.Success,
    onTertiary = TowarownikColorTokens.Background,
    tertiaryContainer = TowarownikColorTokens.SurfaceRaised,
    onTertiaryContainer = TowarownikColorTokens.TextPrimary,
    background = TowarownikColorTokens.Background,
    onBackground = TowarownikColorTokens.TextPrimary,
    surface = TowarownikColorTokens.Surface,
    onSurface = TowarownikColorTokens.TextPrimary,
    surfaceVariant = TowarownikColorTokens.SurfaceRaised,
    onSurfaceVariant = TowarownikColorTokens.TextSecondary,
    surfaceTint = Color.Transparent,
    outline = TowarownikColorTokens.Outline,
    outlineVariant = TowarownikColorTokens.SurfaceHighlight,
    error = TowarownikColorTokens.Error,
    onError = TowarownikColorTokens.Background,
    errorContainer = TowarownikColorTokens.SurfaceHighlight,
    onErrorContainer = TowarownikColorTokens.TextPrimary,
    inverseSurface = TowarownikColorTokens.TextPrimary,
    inverseOnSurface = TowarownikColorTokens.Background,
    inversePrimary = TowarownikColorTokens.Accent,
    surfaceDim = TowarownikColorTokens.Background,
    surfaceBright = TowarownikColorTokens.SurfaceHighlight,
    surfaceContainerLowest = TowarownikColorTokens.Background,
    surfaceContainerLow = TowarownikColorTokens.Surface,
    surfaceContainer = TowarownikColorTokens.Surface,
    surfaceContainerHigh = TowarownikColorTokens.SurfaceRaised,
    surfaceContainerHighest = TowarownikColorTokens.SurfaceHighlight,
)

private val BaseTypography = Typography()

private val WarmModularTypography = Typography(
    headlineSmall = BaseTypography.headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = BaseTypography.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = BaseTypography.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = BaseTypography.bodyLarge.copy(
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = BaseTypography.bodyMedium.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = BaseTypography.bodySmall.copy(
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = BaseTypography.labelLarge.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
    ),
    labelMedium = BaseTypography.labelMedium.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
    ),
    labelSmall = BaseTypography.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
)

private val WarmModularShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun TowarownikTheme(
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTowarownikSemanticColors provides WarmModularSemanticColors,
    ) {
        MaterialTheme(
            colorScheme = WarmModularColorScheme,
            typography = WarmModularTypography,
            shapes = WarmModularShapes,
            content = content,
        )
    }
}
