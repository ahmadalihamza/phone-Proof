package pk.phoneproof.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Keep the on-screen review bounded; defect coordinates remain normalized to the full image. */
internal fun decodeReviewBitmap(bytes: ByteArray): Bitmap {
    require(bytes.size <= 32 * 1024 * 1024) { "Photo is too large to open." }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 24_000_000) {
        "Photo could not be opened."
    }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sample
    })) { "Photo could not be opened." }
}
