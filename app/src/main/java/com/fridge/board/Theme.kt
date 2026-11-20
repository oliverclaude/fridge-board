package com.fridge.board

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Dark scheme from a leaf seed, mapped onto Material 3 color roles.
// Surfaces step by tone. Primary stays light enough for text on the dark containers.
private val FridgeDark = darkColorScheme(
    primary = Color(0xFFC9E2A6),
    onPrimary = Color(0xFF243412),
    primaryContainer = Color(0xFF3B4F28),
    onPrimaryContainer = Color(0xFFE4F6C8),
    secondary = Color(0xFFD5C7A8),
    onSecondary = Color(0xFF3A3222),
    secondaryContainer = Color(0xFF524833),
    onSecondaryContainer = Color(0xFFF2E4C4),
    tertiary = Color(0xFFB7D4C8),
    onTertiary = Color(0xFF1A332C),
    tertiaryContainer = Color(0xFF314A42),
    onTertiaryContainer = Color(0xFFD3F0E4),
    background = Color(0xFF111310),
    onBackground = Color(0xFFE2E3DC),
    surface = Color(0xFF111310),
    onSurface = Color(0xFFE2E3DC),
    surfaceVariant = Color(0xFF44483E),
    onSurfaceVariant = Color(0xFFC4C8BA),
    surfaceContainerLowest = Color(0xFF0C0E0B),
    surfaceContainerLow = Color(0xFF191C17),
    surfaceContainer = Color(0xFF1D201B),
    surfaceContainerHigh = Color(0xFF272B25),
    surfaceContainerHighest = Color(0xFF32362F),
    outline = Color(0xFF8E9286),
    outlineVariant = Color(0xFF44483E),
    inverseSurface = Color(0xFFE2E3DC),
    inverseOnSurface = Color(0xFF2F312C),
    scrim = Color(0xFF000000),
)

private val FridgeShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val FridgeType = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Normal),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun FridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FridgeDark,
        shapes = FridgeShapes,
        typography = FridgeType,
        content = content,
    )
}
