package pk.phoneproof.app

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pk.phoneproof.app.ui.decodeReviewBitmap
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ReviewBitmapTest {
    @Test fun largePhotoUsesABoundedPreviewWithTheOriginalAspectRatio() {
        val original = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888)
        val bytes = try {
            ByteArrayOutputStream().use { output ->
                check(original.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray()
            }
        } finally { original.recycle() }
        val preview = decodeReviewBitmap(bytes)
        try {
            assertTrue(maxOf(preview.width, preview.height) <= 1600)
            assertEquals(4.0 / 3.0, preview.width.toDouble() / preview.height, 0.001)
            assertTrue(preview.allocationByteCount < 10 * 1024 * 1024)
        } finally { preview.recycle() }
    }

    @Test fun invalidImageIsRejectedBeforeReview() {
        try {
            decodeReviewBitmap(byteArrayOf(1, 2, 3))
            fail("Expected invalid image rejection")
        } catch (_: IllegalArgumentException) { }
    }
}
