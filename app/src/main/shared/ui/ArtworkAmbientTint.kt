package com.winlator.cmod.shared.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The colour a game's artwork contributes to the screen behind it.
 *
 * A launcher that shows the same flat panel for every game feels like a utility; letting the
 * artwork tint the surroundings while you move through a shelf is most of what makes browsing
 * feel like a media browser instead of a file list. This is deliberately a *tint* rather than a
 * wallpaper: the image itself is still blurred underneath for texture, and the scrim above it
 * keeps label contrast.
 */
internal object ArtworkTint {
    /** Small enough to sample in a few milliseconds; the tint does not need detail. */
    private const val SAMPLE_SIZE = 96
    private const val CACHE_ENTRIES = 96

    private val cache = LruCache<String, Color>(CACHE_ENTRIES)

    /**
     * Picks a colour worth tinting a dark screen with.
     *
     * Palette's "vibrant" swatches are usually the recognisable colour of a cover (a logo, a
     * key art accent), which reads much better as an ambient wash than the arithmetic mean of
     * the pixels - covers are mostly dark or desaturated, so an average tends to muddy grey.
     */
    internal fun fromBitmap(bitmap: Bitmap): Color? {
        val palette =
            Palette
                .from(bitmap)
                .clearFilters()
                .generate()
        val swatch =
            palette.vibrantSwatch
                ?: palette.lightVibrantSwatch
                ?: palette.darkVibrantSwatch
                ?: palette.dominantSwatch
                ?: palette.mutedSwatch
                ?: return null
        return readableOnDark(Color(swatch.rgb))
    }

    /**
     * Keeps the tint inside a range that reads on the dark background: near-black covers lift
     * towards grey so they are visible at all, and near-white covers drop so labels stay legible.
     */
    internal fun readableOnDark(color: Color): Color {
        val luminance = color.luminance()
        return when {
            luminance < 0.10f -> lerp(color, Color.White, 0.22f)
            luminance > 0.72f -> lerp(color, Color.Black, 0.34f)
            else -> color
        }
    }

    /**
     * Samples [model] once and caches the result by identity. Failures are cached as [Color.Unspecified]
     * so a missing or undecodable cover is not retried on every recomposition.
     */
    internal suspend fun of(
        context: Context,
        model: Any,
    ): Color? = withContext(Dispatchers.IO) {
        val key = model.toString()
        cache.get(key)?.let { return@withContext it.takeIf { it != Color.Unspecified } }

        val request =
            ImageRequest
                .Builder(context)
                .data(model)
                .size(SAMPLE_SIZE)
                // Palette needs to read the pixels, which a hardware bitmap does not allow.
                .allowHardware(false)
                .build()
        val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
        val color = bitmap?.let { fromBitmap(it) }
        cache.put(key, color ?: Color.Unspecified)
        color
    }
}

/**
 * The ambient tint for [model], animated between games.
 *
 * The previous colour is kept while the next one is being sampled, so moving along a shelf
 * crossfades instead of flashing to the background and back.
 */
@Composable
internal fun rememberAmbientTint(
    model: Any?,
    fallback: Color = Color.Unspecified,
): Color {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(fallback) }

    LaunchedEffect(model) {
        val resolved = model?.let { ArtworkTint.of(context.applicationContext, it) }
        if (resolved != null) shown = resolved
    }

    val animated by animateColorAsState(
        targetValue = shown,
        animationSpec = tween(durationMillis = 420),
        label = "ambientTint",
    )
    return animated
}
