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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
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
            // onBackground: dark text on the light band, light text on the dark one. A
            // hairline at the bottom keeps the band's edge visible on the light scheme.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // targetSdk 35 makes the window edge-to-edge on Android 15, and
                            // Scaffold insets its CONTENT but not its topBar -- so without this
                            // the wordmark sits under the status bar and behind the cutout.
                            .statusBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AppMark()
                        Wordmark(Modifier.weight(1f))
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant,
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
                    icon = { Icon(Icons.Default.PlayArrow, "Live") },
                    label = { Text("Live") },
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
