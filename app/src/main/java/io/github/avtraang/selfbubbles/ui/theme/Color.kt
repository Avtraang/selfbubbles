package io.github.avtraang.selfbubbles.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// The only file in the app that may contain hex colors.

private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)

// Network colors (same in light and dark). Owner decision: keep the app's existing
// iMessage blue and SMS green rather than the spec's increased-contrast pair.
private val BlueFill = Color(0xFF007AFF)   // iMessage bubble, send button, New chat button, unread dot, links, tinted icons
private val GreenFill = Color(0xFF34C759)  // SMS/RCS bubble, send button on text threads, the list's green dot

// Dark neutrals
private val DarkGray1 = Color(0xFF1C1C1E)  // search field, sheets, menus
private val DarkGray2 = Color(0xFF2C2C2E)  // received bubble, dialogs
private val DarkGray3 = Color(0xFF3A3A3C)  // dividers, reaction chips, photo borders
private val DarkOutline = Color(0xFF636366)
private val DarkTextSecondary = Color(0xFF98989D)
private val DarkRed = Color(0xFFFF6165)

// Light neutrals
private val LightGray1 = Color(0xFFF2F2F7)
private val LightGray2 = Color(0xFFE5E5EA)
private val LightGray3 = Color(0xFFD1D1D6)
private val LightOutline = Color(0xFF8E8E93)
private val LightTextSecondary = Color(0xFF636366)
private val LightRed = Color(0xFFE9152D)

// Avatars without a photo: the app's existing fills, picked by a stable key (name hash today).
// The spec's six deep hues are not adopted for now.
private val AvatarFills = listOf(
    Color(0xFF5E5CE6), Color(0xFF6C7A89), Color(0xFF8E8E93), Color(0xFF4A6FA5),
    Color(0xFF9A6FB0), Color(0xFF556B8D), Color(0xFF7D7AAF), Color(0xFF667C8A),
)

// Sender names in group threads: the app's existing palette, brighter than the avatar fills
// and tuned for text. The same list serves both themes.
private val SenderNames = listOf(
    Color(0xFF00897B), Color(0xFFD81B60), Color(0xFF3949AB), Color(0xFFF4511E),
    Color(0xFF8E24AA), Color(0xFF43A047), Color(0xFF0097A7), Color(0xFFEF6C00),
    Color(0xFF1E88E5), Color(0xFF6D4C41),
)

// Owner decision: one blue. primary (the blue you read: text buttons, tinted icons, links) and
// primaryContainer (the blue you fill: bubbles, send, New chat) are both BlueFill, with white on
// each. Material's filled Button and Switch therefore come out white-on-blue as well.
internal val DarkColors = darkColorScheme(
    primary = BlueFill,
    onPrimary = White,
    primaryContainer = BlueFill,
    onPrimaryContainer = White,
    inversePrimary = BlueFill,
    secondary = DarkTextSecondary,
    onSecondary = Black,
    secondaryContainer = DarkGray3,
    onSecondaryContainer = White,
    tertiary = DarkTextSecondary,
    onTertiary = Black,
    tertiaryContainer = DarkGray3,
    onTertiaryContainer = White,
    background = Black,
    onBackground = White,
    surface = Black,
    onSurface = White,
    surfaceVariant = DarkGray3,
    onSurfaceVariant = DarkTextSecondary,
    surfaceTint = Black,
    inverseSurface = LightGray1,
    inverseOnSurface = Black,
    error = DarkRed,
    onError = Black,
    errorContainer = Color(0xFF5C1A1D),
    onErrorContainer = Color(0xFFFFD9DA),
    outline = DarkOutline,
    outlineVariant = DarkGray3,
    scrim = Black,
    surfaceBright = DarkGray2,
    surfaceDim = Black,
    surfaceContainerLowest = Black,
    surfaceContainerLow = DarkGray1,
    surfaceContainer = DarkGray1,
    surfaceContainerHigh = DarkGray2,
    surfaceContainerHighest = DarkGray3,
    // The "fixed" roles need Material 3 1.4 or later. They are unused here and set only so no purple
    // default can appear. On Material 3 1.3, delete these three lines.
    primaryFixed = BlueFill, primaryFixedDim = BlueFill, onPrimaryFixed = White, onPrimaryFixedVariant = White,
    secondaryFixed = DarkGray3, secondaryFixedDim = DarkGray3, onSecondaryFixed = White, onSecondaryFixedVariant = White,
    tertiaryFixed = DarkGray3, tertiaryFixedDim = DarkGray3, onTertiaryFixed = White, onTertiaryFixedVariant = White,
)

internal val LightColors = lightColorScheme(
    primary = BlueFill,
    onPrimary = White,
    primaryContainer = BlueFill,
    onPrimaryContainer = White,
    inversePrimary = BlueFill,
    secondary = LightTextSecondary,
    onSecondary = White,
    secondaryContainer = LightGray2,
    onSecondaryContainer = Black,
    tertiary = LightTextSecondary,
    onTertiary = White,
    tertiaryContainer = LightGray2,
    onTertiaryContainer = Black,
    background = White,
    onBackground = Black,
    surface = White,
    onSurface = Black,
    surfaceVariant = LightGray2,
    onSurfaceVariant = LightTextSecondary,
    surfaceTint = White,
    inverseSurface = DarkGray1,
    inverseOnSurface = White,
    error = LightRed,
    onError = White,
    errorContainer = Color(0xFFFFE1E3),
    onErrorContainer = Color(0xFF7A0010),
    outline = LightOutline,
    outlineVariant = LightGray3,
    scrim = Black,
    surfaceBright = White,
    surfaceDim = LightGray2,
    surfaceContainerLowest = White,
    surfaceContainerLow = LightGray1,
    surfaceContainer = LightGray1,
    surfaceContainerHigh = LightGray2,
    surfaceContainerHighest = LightGray3,
    // Material 3 1.4 or later, as above.
    primaryFixed = BlueFill, primaryFixedDim = BlueFill, onPrimaryFixed = White, onPrimaryFixedVariant = White,
    secondaryFixed = LightGray2, secondaryFixedDim = LightGray2, onSecondaryFixed = Black, onSecondaryFixedVariant = Black,
    tertiaryFixed = LightGray2, tertiaryFixedDim = LightGray2, onTertiaryFixed = Black, onTertiaryFixedVariant = Black,
)

/** Colors Material 3 has no slot for: bubbles, the warning strip, sender names, avatars without a photo. */
@Immutable
data class MessageColors(
    val bubbleIMessage: Color,
    val onBubbleIMessage: Color,
    val bubbleSms: Color,
    val onBubbleSms: Color,
    val bubbleReceived: Color,
    val onBubbleReceived: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val senderNames: List<Color>,
    val avatarFills: List<Color>,
    val onAvatar: Color,
    val onScrim: Color,             // glyphs over colorScheme.scrim: play buttons, the fullscreen viewer's close
    val pdfPage: Color,             // paper behind a rendered PDF page: white in both themes, like the page itself
)

internal val DarkMessageColors = MessageColors(
    bubbleIMessage = BlueFill,
    onBubbleIMessage = White,
    bubbleSms = GreenFill,
    onBubbleSms = White,
    bubbleReceived = DarkGray2,
    onBubbleReceived = White,
    warningContainer = Color(0xFF332600),
    onWarningContainer = Color(0xFFFFD600),
    senderNames = SenderNames,
    avatarFills = AvatarFills,
    onAvatar = White,
    onScrim = White,
    pdfPage = White,
)

internal val LightMessageColors = MessageColors(
    bubbleIMessage = BlueFill,
    onBubbleIMessage = White,
    bubbleSms = GreenFill,
    onBubbleSms = White,
    bubbleReceived = LightGray2,
    onBubbleReceived = Black,
    warningContainer = Color(0xFFFFF1C2),
    onWarningContainer = Color(0xFF5C3D00),
    senderNames = SenderNames,
    avatarFills = AvatarFills,
    onAvatar = White,
    onScrim = White,
    pdfPage = White,
)

internal val LocalMessageColors = staticCompositionLocalOf { DarkMessageColors }
