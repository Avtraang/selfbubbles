package io.github.avtraang.selfbubbles.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Five sizes: 22, 17, 15, 13, 11. No other font sizes anywhere in the app.
// One exception: avatar initials are sized from the avatar (see InitialsAvatar), not from this scale.

// Start from Material's own style so its line-height handling carries over:
// each line is centered in its line height and nothing is trimmed, so a 22sp line is 22sp tall.
private val Base = Typography().bodyLarge

private val Title = Base.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp)
private val Body = Base.copy(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp)
private val Secondary = Base.copy(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
private val Meta = Base.copy(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
private val Micro = Base.copy(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.1.sp)

internal val AppTypography = Typography(
    // Not used by this app; mapped to Title so nothing falls back to Material's defaults.
    displayLarge = Title, displayMedium = Title, displaySmall = Title,
    headlineLarge = Title, headlineMedium = Title,
    headlineSmall = Title,                                   // dialog titles
    titleLarge = Title,                                      // screen titles in the top bar
    titleMedium = Body.copy(fontWeight = FontWeight.Medium), // conversation name in the header
    titleSmall = Secondary.copy(fontWeight = FontWeight.Medium),
    bodyLarge = Body,                                        // bubble text, thread name, text fields
    bodyMedium = Secondary,                                  // thread preview, warning strip, dialogs
    bodySmall = Meta,                                        // reply quotes, captions
    labelLarge = Secondary.copy(fontWeight = FontWeight.Medium), // buttons, New chat, menu items
    labelMedium = Meta,                                      // timestamps, pinned names, network label
    labelSmall = Micro,                                      // reaction counts, delivery status
)
