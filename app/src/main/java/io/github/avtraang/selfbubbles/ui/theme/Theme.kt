package io.github.avtraang.selfbubbles.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

@Composable
fun MessagesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // No dynamic color: wallpaper colors would replace the iMessage blue.
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val messageColors = if (darkTheme) DarkMessageColors else LightMessageColors
    CompositionLocalProvider(LocalMessageColors provides messageColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}

/** Read bubble and warning colors with `MessagesTheme.colors.bubbleIMessage`. */
object MessagesTheme {
    val colors: MessageColors
        @Composable @ReadOnlyComposable get() = LocalMessageColors.current
}
