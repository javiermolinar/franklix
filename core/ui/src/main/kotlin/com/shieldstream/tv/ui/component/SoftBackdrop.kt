package com.shieldstream.tv.ui.component

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import com.shieldstream.tv.ui.theme.AppColors

/** Decode size for softened backdrops. A quarter of TMDB's w1280, so decoding can subsample exactly. */
private const val SoftWidth = 320
private const val SoftHeight = 180

/**
 * Box-blur radius in decoded pixels (three passes). 4 was too soft on the Nokia; 3 keeps shapes
 * readable while still quieting detail behind the rows.
 */
private const val SoftRadius = 3

/** Decode size for sharp baked backdrops: TMDB's w1280, so the common case decodes without scaling. */
private const val SharpWidth = 1280
private const val SharpHeight = 720

/**
 * Full-screen backdrop that is blurred once while loading, Muzei-style. The blur runs on Coil's
 * background decoder on a 320×180 bitmap; the result is an ordinary bitmap, so drawing and
 * scrolling cost nothing extra per frame. Avoid GPU `Modifier.blur` for this: it needs API 31 and
 * re-blurs a full-screen layer whenever that layer redraws.
 */
@Composable
fun SoftBackdropImage(url: String, modifier: Modifier = Modifier, scrims: List<BackdropScrim> = emptyList()) {
    val context = LocalContext.current
    val request = remember(url, scrims) {
        ImageRequest.Builder(context)
            .data(url)
            .size(SoftWidth, SoftHeight)
            .transformations(
                if (scrims.isEmpty()) SoftenTransformation
                else BakedScrimsTransformation(SoftWidth, SoftHeight, SoftRadius, scrims),
            )
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/**
 * Sharp full-screen backdrop with [scrims] baked into an opaque 1280×720 bitmap. Use it instead of an
 * image plus full-screen gradient overlays on screens that scroll over a fixed backdrop: every scroll
 * frame redraws the whole screen, and each extra full-screen layer costs GPU time on the Nokia 8010.
 * The baking runs once per image on Coil's background decoder.
 */
@Composable
fun ScrimmedBackdropImage(url: String, scrims: List<BackdropScrim>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(url, scrims) {
        ImageRequest.Builder(context)
            .data(url)
            .size(SharpWidth, SharpHeight)
            .transformations(BakedScrimsTransformation(SharpWidth, SharpHeight, blurRadius = 0, scrims))
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

private object SoftenTransformation : Transformation {
    override val cacheKey: String = "soften-$SoftWidth-$SoftRadius"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val source = if (input.width > SoftWidth) {
            val height = (input.height * SoftWidth.toFloat() / input.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(input, SoftWidth, height, true)
        } else {
            input
        }
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        boxBlur(pixels, width, height, SoftRadius)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }
}

/**
 * A linear scrim of [color] across the full backdrop, as `(position, alpha)` stops. [brush] draws it
 * at full resolution; [SoftBackdropImage] can instead bake it into the softened bitmap.
 */
data class BackdropScrim(
    val horizontal: Boolean,
    val stops: List<Pair<Float, Float>>,
    val color: Color = AppColors.Background,
) {
    fun brush(): Brush {
        val colorStops = stops.map { (position, alpha) -> position to color.copy(alpha = alpha) }.toTypedArray()
        return if (horizontal) Brush.horizontalGradient(*colorStops) else Brush.verticalGradient(*colorStops)
    }

    /** Scrim alpha at [fraction] of the width or height, clamped outside the first and last stops. */
    fun alphaAt(fraction: Float): Float {
        if (stops.isEmpty()) return 0f
        if (fraction <= stops.first().first) return stops.first().second
        stops.zipWithNext().forEach { (start, end) ->
            if (fraction <= end.first) {
                val span = end.first - start.first
                if (span <= 0f) return end.second
                return start.second + (end.second - start.second) * (fraction - start.first) / span
            }
        }
        return stops.last().second
    }
}

/**
 * Optionally softens the backdrop and composites [scrims] into an opaque [width]×[height] bitmap.
 * Drawing the same scrims as full-screen gradients over the image cost about 22 ms of GPU time per
 * frame on the Nokia 8010, because Compose redraws the whole screen on every row scroll; the baked
 * bitmap is one opaque draw. The bitmap is centre-cropped to the target aspect ratio so the baked
 * scrims line up with a full-screen, cropped image. [blurRadius] 0 keeps it sharp.
 */
private class BakedScrimsTransformation(
    private val width: Int,
    private val height: Int,
    private val blurRadius: Int,
    private val scrims: List<BackdropScrim>,
) : Transformation {
    override val cacheKey: String = if (blurRadius > 0) {
        "soften-scrims-$width-$blurRadius-${scrims.hashCode()}"
    } else {
        "scrims-$width-$height-${scrims.hashCode()}"
    }

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val scale = maxOf(width.toFloat() / input.width, height.toFloat() / input.height)
        val scaledWidth = (input.width * scale).toInt().coerceAtLeast(width)
        val scaledHeight = (input.height * scale).toInt().coerceAtLeast(height)
        val scaled = Bitmap.createScaledBitmap(input, scaledWidth, scaledHeight, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(
            pixels, 0, width,
            (scaledWidth - width) / 2, (scaledHeight - height) / 2, width, height,
        )
        boxBlur(pixels, width, height, blurRadius)
        applyScrims(pixels, width, height, scrims, AppColors.Background.toArgb())
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        // Opaque bitmaps draw without blending, so the GPU can skip the background fills beneath.
        output.setHasAlpha(false)
        return output
    }
}

/**
 * Flattens ARGB [pixels] over [background], then blends each scrim in order, matching gradients drawn
 * over the image. The result is fully opaque.
 */
internal fun applyScrims(pixels: IntArray, width: Int, height: Int, scrims: List<BackdropScrim>, background: Int) {
    // A horizontal scrim depends only on x and a vertical one only on y.
    val alphas = scrims.map { scrim ->
        if (scrim.horizontal) FloatArray(width) { x -> scrim.alphaAt((x + 0.5f) / width) }
        else FloatArray(height) { y -> scrim.alphaAt((y + 0.5f) / height) }
    }
    val colors = scrims.map { floatArrayOf(it.color.red * 255f, it.color.green * 255f, it.color.blue * 255f) }
    val bgR = (background shr 16) and 0xFF
    val bgG = (background shr 8) and 0xFF
    val bgB = background and 0xFF
    for (y in 0 until height) {
        for (x in 0 until width) {
            val i = y * width + x
            val p = pixels[i]
            val a = (p ushr 24) / 255f
            var r = ((p shr 16) and 0xFF) * a + bgR * (1f - a)
            var g = ((p shr 8) and 0xFF) * a + bgG * (1f - a)
            var b = (p and 0xFF) * a + bgB * (1f - a)
            for (s in scrims.indices) {
                val alpha = alphas[s][if (scrims[s].horizontal) x else y]
                val c = colors[s]
                r = r * (1f - alpha) + c[0] * alpha
                g = g * (1f - alpha) + c[1] * alpha
                b = b * (1f - alpha) + c[2] * alpha
            }
            pixels[i] = (0xFF shl 24) or
                ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (b + 0.5f).toInt().coerceIn(0, 255)
        }
    }
}

/**
 * Approximates a Gaussian blur with [passes] horizontal and vertical box blurs. Edges clamp, so
 * the borders do not darken. Operates in place on ARGB pixels.
 */
internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int = 3) {
    if (radius <= 0 || width <= 0 || height <= 0) return
    val scratch = IntArray(pixels.size)
    repeat(passes) {
        blurLine(pixels, scratch, lines = height, length = width, lineStride = width, step = 1, radius = radius)
        blurLine(scratch, pixels, lines = width, length = height, lineStride = 1, step = width, radius = radius)
    }
}

private fun blurLine(src: IntArray, dst: IntArray, lines: Int, length: Int, lineStride: Int, step: Int, radius: Int) {
    val window = radius * 2 + 1
    val last = length - 1
    for (line in 0 until lines) {
        val base = line * lineStride
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (k in -radius..radius) {
            val p = src[base + k.coerceIn(0, last) * step]
            a += p ushr 24
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        for (i in 0..last) {
            dst[base + i * step] = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val out = src[base + (i - radius).coerceIn(0, last) * step]
            val enter = src[base + (i + radius + 1).coerceIn(0, last) * step]
            a += (enter ushr 24) - (out ushr 24)
            r += ((enter shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            g += ((enter shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            b += (enter and 0xFF) - (out and 0xFF)
        }
    }
}
