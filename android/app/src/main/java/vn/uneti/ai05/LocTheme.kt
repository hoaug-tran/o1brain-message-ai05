package vn.uneti.ai05

import android.content.SharedPreferences
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DisplayFace = FontFamily.Default
private val Light =
    lightColorScheme(
        primary = Color(0xFF164D50),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFC9E8DF),
        onPrimaryContainer = Color(0xFF103A3C),
        secondary = Color(0xFF4C6463),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD0E8E5),
        onSecondaryContainer = Color(0xFF233C3A),
        tertiary = Color(0xFF984322),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDAC9),
        onTertiaryContainer = Color(0xFF56220F),
        background = Color(0xFFF6F8F7),
        onBackground = Color(0xFF202C2B),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF202C2B),
        surfaceVariant = Color(0xFFE2E9E6),
        onSurfaceVariant = Color(0xFF505D57),
        surfaceContainerLow = Color(0xFFF6F8F7),
        surfaceContainer = Color(0xFFEAF0ED),
        surfaceContainerHigh = Color(0xFFE3E7DE),
        surfaceContainerHighest = Color(0xFFDDE3D9),
        outline = Color(0xFF717C74),
        outlineVariant = Color(0xFFCDD2C6),
    )
private val Dark =
    darkColorScheme(
        primary = Color(0xFFA0D5C9),
        onPrimary = Color(0xFF073735),
        primaryContainer = Color(0xFF204D4A),
        onPrimaryContainer = Color(0xFFC9E8DF),
        secondary = Color(0xFFB0CCC7),
        onSecondary = Color(0xFF1C3532),
        secondaryContainer = Color(0xFF334B47),
        onSecondaryContainer = Color(0xFFD0E8E5),
        tertiary = Color(0xFFFFB596),
        onTertiary = Color(0xFF5B260F),
        tertiaryContainer = Color(0xFF71381F),
        onTertiaryContainer = Color(0xFFFFDAC9),
        background = Color(0xFF101C1C),
        onBackground = Color(0xFFE2E9DF),
        surface = Color(0xFF142020),
        onSurface = Color(0xFFE2E9DF),
        surfaceVariant = Color(0xFF30413C),
        onSurfaceVariant = Color(0xFFB8C6BC),
        surfaceContainerLow = Color(0xFF182625),
        surfaceContainer = Color(0xFF1F2E2C),
        surfaceContainerHigh = Color(0xFF293A35),
        surfaceContainerHighest = Color(0xFF34463F),
        outline = Color(0xFF89998D),
        outlineVariant = Color(0xFF42534A),
    )

object LocMotion {
    val Chat = tween<IntOffset>(180, easing = FastOutSlowInEasing)
    val Placeholder = infiniteRepeatable(tween<Float>(650), repeatMode = RepeatMode.Reverse)
    const val PlaceholderPulseMillis = 5_000L
}

@Composable
fun LocTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("appearance", 0) }
    var theme by remember { mutableStateOf(preferences.getString("theme", "system")) }
    DisposableEffect(preferences) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
                if (key == "theme") theme = store.getString("theme", "system")
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val dark =
        when (theme) {
            "light" -> false
            "dark" -> true
            else -> isSystemInDarkTheme()
        }
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val type = Typography()
    val corner = RoundedCornerShape(8.dp)
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography =
            type.copy(
                headlineLarge =
                    type.headlineLarge.copy(
                        fontFamily = DisplayFace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                headlineMedium =
                    type.headlineMedium.copy(
                        fontFamily = DisplayFace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                titleLarge =
                    type.titleLarge.copy(fontFamily = DisplayFace, fontWeight = FontWeight.SemiBold),
            ),
        shapes =
            Shapes(
                extraSmall = corner,
                small = corner,
                medium = corner,
                large = corner,
                extraLarge = corner,
            ),
        content = content,
    )
}
