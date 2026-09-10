package com.facefusion.mobile

import android.graphics.Bitmap

/**
 * The bitmap as ARGB_8888, without copying one that already is.
 *
 * ⚠ Every path that hands pixels to the native side does the same three steps -- copy to
 * ARGB_8888, `getPixels` into an IntArray, `argbToBgr` -- and the copy was unconditional.
 * On a 50 MP camera photo that is a needless **200 MB**, on top of the 200 MB bitmap, the
 * 200 MB IntArray and the 150 MB BGR buffer. It is half of why big photos crashed the app
 * on an 8 Elite Gen 5.
 *
 * `decodeOriented` asks ImageDecoder for a software, mutable bitmap, which is ARGB_8888
 * already, so in practice this now copies nothing at all. The copy is kept for the cases
 * that are not: a hardware bitmap has no pixel array to read, and RGB_565 would be read
 * back as the wrong colours rather than failing.
 *
 * ⚠ NON-NULL, unlike `Bitmap.copy`, and it falls back to the bitmap itself if the copy
 * fails. That is not a shortcut: `getPixels` works on any config EXCEPT hardware, so a
 * failed copy leaves the caller no worse off than it was, while returning null would turn
 * a memory hiccup into a refusal. Callers that still write `?:` are harmless -- Kotlin
 * warns the branch is unreachable and keeps their old behaviour.
 */
internal fun Bitmap.asArgb8888(): Bitmap =
    if (config == Bitmap.Config.ARGB_8888) this
    else copy(Bitmap.Config.ARGB_8888, false) ?: this

/**
 * A display-sized copy for the preview panes, long edge ≤ [maxEdge].
 *
 * ⚠ THE SCROLL JANK: the panes render the full pipeline frame, which for a photo target is
 * up to 4096 on the long edge -- a 64 MB ARGB bitmap drawn into a pane roughly the width of
 * a phone screen. The bitmap itself lives on as `originalFrame`/`swappedFrame` because Save
 * and the pipeline need the real resolution, but nothing the pane DRAWS needs more than the
 * pane's own size, and drawing the small one costs a fraction of the texture upload and
 * sampling. This is the single biggest win against "page scroll stutters after a swap".
 *
 * Scale is returned (as a 0..1 fraction of the ORIGINAL) so the caller can shrink
 * face-box coordinates by the same factor -- see [scaleBoxesToThumb].
 */
internal fun Bitmap.displayThumb(maxEdge: Int = 720): Pair<Bitmap, Float> {
    val long = maxOf(width, height)
    if (long <= maxEdge) return this to 1f
    val s = maxEdge.toFloat() / long
    val w = (width * s).toInt().coerceAtLeast(1)
    val h = (height * s).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, w, h, true) to s
}

/**
 * Shrink a face-box array (five floats per face, in the ORIGINAL bitmap's pixel
 * coordinates) by [scale], so the boxes still line up on the thumbnail.
 *
 * A new array is returned rather than mutating: `faceBoxes` is shared state on the
 * Activity, and the pane reads it during draw -- mutating in place would race the
 * composition pass that handed it over.
 */
internal fun FloatArray.scaleBoxesToThumb(scale: Float): FloatArray {
    if (scale >= 1f) return this
    val out = FloatArray(size)
    for (i in indices) out[i] = this[i] * scale
    return out
}
