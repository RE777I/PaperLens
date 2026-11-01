package com.paperlens.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paperlens.app.app
import com.paperlens.app.data.AccentColor
import com.paperlens.app.data.AppSettings
import com.paperlens.app.data.ThemeMode

/** Brand accent; tool icons use their own tints (see ui/tools/Tools.kt). */
val Brand = Color(0xFF1565C0)

// Light grey header areas, white content sheets; the accent colours buttons and highlights.
private fun light(accent: AccentColor): ColorScheme {
    val primary = Color(accent.light)
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = lerp(primary, Color.White, 0.84f),
        onPrimaryContainer = lerp(primary, Color.Black, 0.6f),
        secondary = Color(0xFF14A38B),
        onSecondary = Color.White,
        secondaryContainer = lerp(primary, Color.White, 0.88f),
        onSecondaryContainer = lerp(primary, Color.Black, 0.55f),
        background = Color(0xFFF4F5F7),
        surface = Color.White,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF7F8FA),
        surfaceContainer = Color(0xFFF2F3F5),
        surfaceContainerHigh = Color(0xFFECEEF1),
        surfaceContainerHighest = Color(0xFFE6E8EB),
        outlineVariant = Color(0xFFD5D8DD),
    )
}

private fun dark(accent: AccentColor): ColorScheme {
    val primary = Color(accent.dark)
    val base = Color(accent.light)
    return darkColorScheme(
        primary = primary,
        onPrimary = lerp(base, Color.Black, 0.55f),
        primaryContainer = lerp(base, Color.Black, 0.35f),
        onPrimaryContainer = lerp(primary, Color.White, 0.6f),
        secondary = Color(0xFF4DB6AC),
        secondaryContainer = lerp(base, Color.Black, 0.5f),
        onSecondaryContainer = lerp(primary, Color.White, 0.6f),
        background = Color(0xFF111316),
        surface = Color(0xFF1A1C1F),
        surfaceContainerLowest = Color(0xFF0E1012),
        surfaceContainerLow = Color(0xFF1D2023),
        surfaceContainer = Color(0xFF212427),
        surfaceContainerHigh = Color(0xFF2A2D31),
        surfaceContainerHighest = Color(0xFF33363A),
    )
}

/** AMOLED: true black behind everything, slightly lighter cards so they still stand out. */
private fun ColorScheme.pureBlack() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF181818),
    surfaceContainerHighest = Color(0xFF202020),
)

/** The app theme, from Me → Appearance (mode, accent, wallpaper colours, AMOLED black). */
@Composable
fun PaperLensTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings by context.app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val dynamic = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> dark(settings.accent)
        else -> light(settings.accent)
    }
    if (dark && settings.pureBlack) scheme = scheme.pureBlack()

    // Status and navigation bar icons follow the app theme, not just the phone's.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
