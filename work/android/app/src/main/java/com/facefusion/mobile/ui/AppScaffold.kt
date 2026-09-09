package com.facefusion.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
// icons-core only. The extended icon pack is a multi-megabyte dependency for
// one glyph, and PlayArrow reads as a running feed well enough.
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.facefusion.mobile.R

enum class Screen { Swap, Live, Settings }

/**
 * The frame around both screens: brand band above, two destinations below.
 *
 * Two or three destinations is not enough to justify a navigation library -- and adding one
 * would mean resolving a dependency this build cannot be relied on to fetch. A plain enum
 * plus Material3's own NavigationBar is the whole navigation system.
 *
 * Live appears only when [showLive] does, which the caller derives from BuildConfig rather
 * than a flag of its own -- same signal as the app id and the launcher label.
 */
@Composable
fun AppScaffold(
    screen: Screen,
    onScreen: (Screen) -> Unit,
    showLive: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // The brand band. It follows the theme background (day #F7F8FA / night
            // #121212) instead of a fixed teal gradient, so switching the scheme recolors
            // the whole window including the header and status bar. The wordmark inherits
            // onBackground: dark text on the light band, light text on the dark one.
            // No divider and nothing under the mark: the band ends at the wordmark.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        // targetSdk 35 makes the window edge-to-edge on Android 15, and
                        // Scaffold insets its CONTENT but not its topBar -- so without this
                        // the wordmark sits under the status bar and behind the cutout.
                        .statusBarsPadding()
                        // The mark and the wordmark are back, at x1.1 of the size they
                        // were deleted at (mark 20.7 -> 23dp, wordmark 18 -> 19.8sp), and
                        // the band that carries them is x1.3 of the height it had then:
                        // the vertical paddings, the gap and the lift all scale with the
                        // band (5.5 -> 15, 11 -> 14, 7 -> 9, -8 -> -7), so the extra
                        // height reads as a taller band, not as a bigger logo. The offset
                        // lifts the band toward the top edge; offset moves the DRAW only,
                        // so the band's measured height and the content inset under it
                        // stay as they are.
                        .offset(y = (-7).dp)
                        .padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    // In dark mode the brand band is fainter -- the mark and the wordmark
                    // read as chrome rather than content, and the header should not
                    // out-shout the tiles under it on a dark theme.
                    val brandAlpha =
                        if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.69f else 1f
                    AppMark(size = 23.dp, modifier = Modifier.alpha(brandAlpha))
                    Wordmark(
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = brandAlpha),
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = screen == Screen.Swap,
                    onClick = { onScreen(Screen.Swap) },
                    icon = { Icon(Icons.Default.Face, stringResource(R.string.nav_swap)) },
                    label = { Text(stringResource(R.string.nav_swap)) },
                )
                if (showLive) NavigationBarItem(
                    selected = screen == Screen.Live,
                    onClick = { onScreen(Screen.Live) },
                    icon = { Icon(Icons.Default.PlayArrow, stringResource(R.string.nav_live)) },
                    label = { Text(stringResource(R.string.nav_live)) },
                )
                NavigationBarItem(
                    selected = screen == Screen.Settings,
                    onClick = { onScreen(Screen.Settings) },
                    icon = { Icon(Icons.Default.Settings, stringResource(R.string.nav_settings)) },
                    label = { Text(stringResource(R.string.nav_settings)) },
                )
            }
        },
        content = content,
    )
}
