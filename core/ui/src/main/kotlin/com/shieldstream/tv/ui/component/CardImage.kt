package com.shieldstream.tv.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.ImageLoader
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.Disposable
import coil.request.ImageRequest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Centre-cropped image for a card of known size, for lazy rows and grids.
 *
 * Coil's `AsyncImage` costs ~30% of main-thread time while a poster grid scrolls on the Nokia 8010:
 * each image starts a coroutine with `snapshotFlow`/`mapLatest`, and waits for its size through a
 * measure-time handshake. This variant knows its decode size up front, shows memory-cached images in
 * the first frame, otherwise enqueues one request (cancelled when the card leaves), and redraws only
 * the draw phase when the image arrives.
 */
@Composable
fun CardImage(
    url: String,
    contentDescription: String?,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }
    val heightPx = with(density) { height.roundToPx() }
    val load = remember(url, widthPx, heightPx) {
        CardImageLoad(
            loader = context.imageLoader,
            request = ImageRequest.Builder(context)
                .data(url)
                .memoryCacheKey(url)
                .size(widthPx, heightPx)
                .build(),
            key = url,
            minWidth = widthPx,
            minHeight = heightPx,
        )
    }
    Box(
        modifier
            .then(
                if (contentDescription != null) {
                    Modifier.semantics {
                        this.contentDescription = contentDescription
                        role = Role.Image
                    }
                } else {
                    Modifier
                },
            )
            // Reading the image here invalidates only the draw phase when it arrives.
            .drawBehind { load.image?.let { drawCentreCrop(it) } },
    )
}

private class CardImageLoad(
    private val loader: ImageLoader,
    private val request: ImageRequest,
    key: String,
    minWidth: Int,
    minHeight: Int,
) : RememberObserver {
    var image by mutableStateOf<ImageBitmap?>(null)
        private set
    private var needsLoad = true
    private var disposable: Disposable? = null

    init {
        loader.memoryCache?.get(MemoryCache.Key(key))?.bitmap?.let { cached ->
            image = cached.asImageBitmap()
            // Accept a cached decode that is at most ~10% smaller than this card; otherwise refine it.
            needsLoad = cached.width < minWidth * 0.9f || cached.height < minHeight * 0.9f
        }
    }

    override fun onRemembered() {
        if (!needsLoad || disposable != null) return
        disposable = loader.enqueue(
            request.newBuilder()
                .target(onSuccess = { result -> image = result.toBitmap().asImageBitmap() })
                .build(),
        )
    }

    override fun onForgotten() {
        disposable?.dispose()
        disposable = null
    }

    override fun onAbandoned() = onForgotten()
}

private fun Drawable.toBitmap(): Bitmap {
    if (this is BitmapDrawable) bitmap?.let { return it }
    val bitmap = Bitmap.createBitmap(intrinsicWidth.coerceAtLeast(1), intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    setBounds(0, 0, bitmap.width, bitmap.height)
    draw(Canvas(bitmap))
    return bitmap
}

/** Draws the centred part of [image] that fills this box, like `ContentScale.Crop`, without clipping. */
internal fun DrawScope.drawCentreCrop(image: ImageBitmap) {
    val src = centreCropSource(image.width, image.height, size.width, size.height) ?: return
    drawImage(
        image = image,
        srcOffset = src.first,
        srcSize = src.second,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        filterQuality = FilterQuality.Low,
    )
}

/** Source rectangle of an image that a centre crop shows in a box of the given size. */
internal fun centreCropSource(imageWidth: Int, imageHeight: Int, boxWidth: Float, boxHeight: Float): Pair<IntOffset, IntSize>? {
    if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) return null
    val scale = max(boxWidth / imageWidth, boxHeight / imageHeight)
    val srcWidth = (boxWidth / scale).roundToInt().coerceIn(1, imageWidth)
    val srcHeight = (boxHeight / scale).roundToInt().coerceIn(1, imageHeight)
    return IntOffset((imageWidth - srcWidth) / 2, (imageHeight - srcHeight) / 2) to IntSize(srcWidth, srcHeight)
}
