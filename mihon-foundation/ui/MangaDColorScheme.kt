package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Manga D reference palette, ported from the earlier Compose UI. */
internal object MangaDColorScheme : BaseColorScheme() {
    override val darkScheme = darkColorScheme(
        primary = Color(0xFF9BA8EE),
        onPrimary = Color(0xFF141829),
        primaryContainer = Color(0xFF252E4C),
        onPrimaryContainer = Color(0xFFD6DEFF),
        secondary = Color(0xFF9BA8EE),
        onSecondary = Color(0xFF141829),
        secondaryContainer = Color(0xFF2B3246),
        onSecondaryContainer = Color(0xFFD6DEFF),
        tertiary = Color(0xFF81C784),
        onTertiary = Color(0xFF182A1E),
        background = Color(0xFF0E1117),
        onBackground = Color(0xFFF2F4F8),
        surface = Color(0xFF181B22),
        onSurface = Color(0xFFF2F4F8),
        surfaceVariant = Color(0xFF1E222D),
        onSurfaceVariant = Color(0xFFA0A6B5),
        outline = Color(0xFF686E7E),
        outlineVariant = Color(0xFF282C37),
        surfaceContainerLowest = Color(0xFF0E1117),
        surfaceContainerLow = Color(0xFF151921),
        surfaceContainer = Color(0xFF181B22),
        surfaceContainerHigh = Color(0xFF1E222D),
        surfaceContainerHighest = Color(0xFF252E3D),
    )

    // Required by BaseColorScheme; the default Manga D theme is forced dark.
    // Other user-selected themes retain their own light/dark behavior.
    override val lightScheme = lightColorScheme(
        primary = Color(0xFF5365B8),
        onPrimary = Color.White,
        background = Color(0xFFF6F7FC),
        onBackground = Color(0xFF171A23),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF171A23),
    )
}
