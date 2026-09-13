package com.facefusion.mobile.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.facefusion.mobile.R

/**
 * The row of source faces, on EITHER screen.
 *
 * ⚠ ONE implementation, used by both. The two screens draw the same row of the same list
 * against the same native slots, and the only honest reason to have written it twice would
 * be for them to diverge -- which, for a control whose indices ARE the native slot
 * numbers, is a way for the picture and the pipeline to disagree about who is who.
 *
 * [onKeepOriginal] null hides the "keep the original face" tile. It is not a source and
 * means nothing outside assign mode, so it appears exactly when it can be used rather than
 * sitting there permanently as a disabled thing to wonder about.
 */
@Composable
fun SourceRow(
    thumbs: List<Bitmap>,
    active: Int,
    keepOriginalBrush: Boolean,
    onSelect: (Int) -> Unit,
    onKeepOriginal: (() -> Unit)? = null,
    enabled: Boolean = true,
    showLabels: Boolean = true,
    tileSize: Dp = 72.dp,
    modifier: Modifier = Modifier,
) {
    if (thumbs.isEmpty()) return
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onKeepOriginal != null) {
            val keepLabel = stringResource(R.string.assign_keep_original)
            Tile(
                label = keepLabel,
                selected = keepOriginalBrush,
                enabled = enabled,
                showLabel = false,
                tileSize = tileSize,
                onClick = onKeepOriginal,
            ) {
                Icon(
                    painterResource(R.drawable.ic_person_off),
                    contentDescription = keepLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
                Text(
                    keepLabel,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                )
            }
        }
        thumbs.forEachIndexed { index, thumb ->
            val label = stringResource(R.string.live_source_label, index + 1)
            Tile(
                label = label,
                selected = index == active && !keepOriginalBrush,
                enabled = enabled,
                showLabel = showLabels,
                tileSize = tileSize,
                onClick = { onSelect(index) },
            ) {
                Image(
                    thumb.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

/** One 62 dp square with a caption under it, selected or not. */
@Composable
private fun Tile(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    showLabel: Boolean = true,
    tileSize: Dp = 72.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(tileSize).clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(tileSize)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    BorderStroke(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    shape,
                ),
            contentAlignment = Alignment.Center,
        ) { content() }
        if (showLabel) {
            Text(
                label,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
