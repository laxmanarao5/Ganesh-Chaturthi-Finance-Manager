package com.vinayaka.finance.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.vinayaka.finance.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFFB54708), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDFC6), onPrimaryContainer = Color(0xFF3B1700),
    secondary = Color(0xFF8C2F39), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDADB), onSecondaryContainer = Color(0xFF3F0010),
    tertiary = Color(0xFF2E7D32), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC8E6C9), onTertiaryContainer = Color(0xFF00210A),
    background = Color(0xFFFFFBF7), onBackground = Color(0xFF211A15),
    surface = Color(0xFFFFFBF7), onSurface = Color(0xFF211A15),
    surfaceVariant = Color(0xFFF4E4D8), onSurfaceVariant = Color(0xFF51443B),
    outline = Color(0xFF85746A), outlineVariant = Color(0xFFD7C2B8),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF7F0),
    surfaceContainer = Color(0xFFFFF1E7), surfaceContainerHigh = Color(0xFFFBEBDF),
    surfaceContainerHighest = Color(0xFFF5E4D7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB68A), onPrimary = Color(0xFF512300),
    primaryContainer = Color(0xFF753400), onPrimaryContainer = Color(0xFFFFDBC7),
    secondary = Color(0xFFFFB3B6), onSecondary = Color(0xFF5E1523),
    secondaryContainer = Color(0xFF7A2A35), onSecondaryContainer = Color(0xFFFFDADB),
    tertiary = Color(0xFF81C995), onTertiary = Color(0xFF00390F),
    tertiaryContainer = Color(0xFF1B5E20), onTertiaryContainer = Color(0xFFC8E6C9),
    background = Color(0xFF17120E), onBackground = Color(0xFFEDE0D8),
    surface = Color(0xFF17120E), onSurface = Color(0xFFEDE0D8),
    surfaceVariant = Color(0xFF51443B), onSurfaceVariant = Color(0xFFD7C2B8),
    outline = Color(0xFF9F8D83), outlineVariant = Color(0xFF51443B),
    surfaceContainerLowest = Color(0xFF120D09), surfaceContainerLow = Color(0xFF1F1A16),
    surfaceContainer = Color(0xFF231E1A), surfaceContainerHigh = Color(0xFF2E2824),
    surfaceContainerHighest = Color(0xFF39332E),
)

/** Money colours: green for money in, red for money out. */
@Immutable
class MoneyColors(val income: Color, val expense: Color)

val LocalMoneyColors = staticCompositionLocalOf { MoneyColors(Color(0xFF2E7D32), Color(0xFFC62828)) }

private val AppTypography = Typography().let {
    it.copy(
        headlineMedium = it.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = it.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = it.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

@Composable
fun VinayakaTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val money = if (dark) MoneyColors(Color(0xFF81C995), Color(0xFFFFB4AB)) else MoneyColors(Color(0xFF2E7D32), Color(0xFFC62828))
    androidx.compose.runtime.CompositionLocalProvider(LocalMoneyColors provides money) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
