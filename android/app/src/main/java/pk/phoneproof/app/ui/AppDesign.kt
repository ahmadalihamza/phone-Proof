package pk.phoneproof.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DayColors = lightColorScheme(
    primary = Color(0xFF176B54), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EEE7), onPrimaryContainer = Color(0xFF174D3C),
    secondary = Color(0xFF56645C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF0EA), onSecondaryContainer = Color(0xFF293B30),
    tertiary = Color(0xFF6B6552),
    background = Color(0xFFF5F6F2), onBackground = Color(0xFF1A211C),
    surface = Color.White, onSurface = Color(0xFF1A211C),
    surfaceVariant = Color(0xFFEEF1EC), onSurfaceVariant = Color(0xFF616B63),
    surfaceContainer = Color(0xFFF0F2EE), surfaceContainerLow = Color(0xFFFAFBF8),
    surfaceContainerLowest = Color.White, surfaceContainerHigh = Color(0xFFE9EDE7),
    outline = Color(0xFF858E86), outlineVariant = Color(0xFFDEE4DB),
    error = Color(0xFFB43F39), errorContainer = Color(0xFFFCE9E6), onErrorContainer = Color(0xFF7B2822),
)
private val NightColors = darkColorScheme(
    primary = Color(0xFF8ED2B4), onPrimary = Color(0xFF063A28),
    primaryContainer = Color(0xFF214C3A), onPrimaryContainer = Color(0xFFB4EFD1),
    secondary = Color(0xFFB8C7BC), secondaryContainer = Color(0xFF303E34), onSecondaryContainer = Color(0xFFB4EFD1),
    background = Color(0xFF111612), onBackground = Color(0xFFE4EAE2),
    surface = Color(0xFF1A211B), onSurface = Color(0xFFE4EAE2),
    surfaceVariant = Color(0xFF273128), onSurfaceVariant = Color(0xFFADB8AC),
    surfaceContainer = Color(0xFF1D261F), surfaceContainerLow = Color(0xFF171E18),
    surfaceContainerLowest = Color(0xFF0D120E), surfaceContainerHigh = Color(0xFF28322A),
    outline = Color(0xFF7B897B), outlineVariant = Color(0xFF344135),
    error = Color(0xFFFFB4A9), errorContainer = Color(0xFF5B2621), onErrorContainer = Color(0xFFFFDAD4),
)

@Composable
fun PhoneProofTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) NightColors else DayColors,
        typography = base.copy(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.7).sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 27.sp, lineHeight = 33.sp, letterSpacing = (-0.4).sp),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}

enum class AppSymbol { Inspect, Reports, Account, Back, Next, Phone, Scan, Check, Alert, Info, Sync, More, Close, Play, Cloud }

private val appVectors = AppSymbol.entries.associateWith { symbol ->
    ImageVector.Builder(symbol.name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            when (symbol) {
                AppSymbol.Inspect -> { moveTo(9f,3f);lineTo(5f,3f);lineTo(5f,21f);lineTo(19f,21f);lineTo(19f,3f);lineTo(15f,3f);moveTo(9f,2f);lineTo(15f,2f);lineTo(15f,6f);lineTo(9f,6f);close();moveTo(8f,13f);lineTo(11f,16f);lineTo(16f,10f) }
                AppSymbol.Reports -> { moveTo(6f,3f);lineTo(15f,3f);lineTo(19f,7f);lineTo(19f,21f);lineTo(5f,21f);lineTo(5f,3f);close();moveTo(14f,3f);lineTo(14f,8f);lineTo(19f,8f);moveTo(9f,12f);lineTo(15f,12f);moveTo(9f,16f);lineTo(15f,16f) }
                AppSymbol.Account -> { moveTo(16f,7f);arcTo(4f,4f,0f,true,true,8f,7f);arcTo(4f,4f,0f,true,true,16f,7f);moveTo(4f,21f);curveTo(4f,12f,20f,12f,20f,21f) }
                AppSymbol.Back -> { moveTo(15f,5f);lineTo(8f,12f);lineTo(15f,19f) }
                AppSymbol.Next -> { moveTo(9f,5f);lineTo(16f,12f);lineTo(9f,19f) }
                AppSymbol.Phone -> { moveTo(8f,2f);lineTo(16f,2f);quadTo(18f,2f,18f,4f);lineTo(18f,20f);quadTo(18f,22f,16f,22f);lineTo(8f,22f);quadTo(6f,22f,6f,20f);lineTo(6f,4f);quadTo(6f,2f,8f,2f);moveTo(10f,5f);lineTo(14f,5f);moveTo(11f,19f);lineTo(13f,19f) }
                AppSymbol.Scan -> { moveTo(8f,3f);lineTo(3f,3f);lineTo(3f,8f);moveTo(16f,3f);lineTo(21f,3f);lineTo(21f,8f);moveTo(3f,16f);lineTo(3f,21f);lineTo(8f,21f);moveTo(16f,21f);lineTo(21f,21f);lineTo(21f,16f);moveTo(7f,12f);lineTo(17f,12f) }
                AppSymbol.Check -> { moveTo(5f,12f);lineTo(10f,17f);lineTo(20f,7f) }
                AppSymbol.Alert -> { moveTo(12f,3f);lineTo(22f,20f);lineTo(2f,20f);close();moveTo(12f,9f);lineTo(12f,13f);moveTo(12f,16f);lineTo(12f,16.2f) }
                AppSymbol.Info -> { moveTo(21f,12f);arcTo(9f,9f,0f,true,true,3f,12f);arcTo(9f,9f,0f,true,true,21f,12f);moveTo(12f,11f);lineTo(12f,17f);moveTo(12f,7f);lineTo(12f,7.2f) }
                AppSymbol.Sync -> { moveTo(20f,10f);curveTo(18f,2f,7f,2f,4f,9f);moveTo(4f,9f);lineTo(4f,4f);moveTo(4f,9f);lineTo(9f,9f);moveTo(4f,14f);curveTo(6f,22f,17f,22f,20f,15f);moveTo(20f,15f);lineTo(20f,20f);moveTo(20f,15f);lineTo(15f,15f) }
                AppSymbol.More -> { moveTo(12f,5f);lineTo(12f,5.1f);moveTo(12f,12f);lineTo(12f,12.1f);moveTo(12f,19f);lineTo(12f,19.1f) }
                AppSymbol.Close -> { moveTo(6f,6f);lineTo(18f,18f);moveTo(18f,6f);lineTo(6f,18f) }
                AppSymbol.Play -> { moveTo(8f,4f);lineTo(20f,12f);lineTo(8f,20f);close() }
                AppSymbol.Cloud -> { moveTo(7f,18f);curveTo(0f,18f,1f,9f,7f,9f);curveTo(8f,1f,19f,2f,19f,10f);curveTo(25f,11f,23f,18f,18f,18f);moveTo(9f,16f);lineTo(12f,19f);lineTo(17f,14f) }
            }
        }
    }.build()
}

@Composable
fun AppIcon(symbol: AppSymbol, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(appVectors.getValue(symbol), description, modifier.size(22.dp), tint)
}
