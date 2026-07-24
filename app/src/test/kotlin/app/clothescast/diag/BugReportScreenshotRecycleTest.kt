package app.clothescast.diag

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Covers the bug-report screenshot buffer cleanup. The capture allocates a
 * full-window ARGB_8888 bitmap (10-30 MB on current phones), and the original
 * code dropped it unrecycled whenever PixelCopy failed or the caller was
 * cancelled mid-capture — transient memory pressure that lingered until the
 * next GC. Every path that doesn't hand the bitmap to the caller must recycle
 * it; the one path that does must hand it over live.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BugReportScreenshotRecycleTest {
    private fun newBitmap(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    @Test
    fun successfulCopyReturnsTheBitmapUnrecycled(): Unit = runBlocking {
        val bitmap = newBitmap()

        val result = BugReport.awaitPixelCopyInto(bitmap) { onResult -> onResult(true) }

        (result === bitmap) shouldBe true
        bitmap.isRecycled shouldBe false
    }

    @Test
    fun failedCopyRecyclesTheBitmap(): Unit = runBlocking {
        val bitmap = newBitmap()

        val result = BugReport.awaitPixelCopyInto(bitmap) { onResult -> onResult(false) }

        result.shouldBeNull()
        bitmap.isRecycled shouldBe true
    }

    @Test
    fun synchronousRequestFailureRecyclesTheBitmap(): Unit = runBlocking {
        val bitmap = newBitmap()

        val result = BugReport.awaitPixelCopyInto(bitmap) { throw IllegalStateException("boom") }

        result.shouldBeNull()
        bitmap.isRecycled shouldBe true
    }

    @Test
    fun cancellationBeforeTheResultRecyclesTheBitmapWhenTheCopyLands(): Unit = runBlocking {
        val bitmap = newBitmap()
        var deliverResult: ((Boolean) -> Unit)? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            BugReport.awaitPixelCopyInto(bitmap) { onResult -> deliverResult = onResult }
        }
        job.cancelAndJoin()

        // PixelCopy may still be writing into the buffer until its callback
        // fires, so cancellation alone must not recycle...
        bitmap.isRecycled shouldBe false

        // ...but once the (now unwanted) result lands, the buffer is freed.
        deliverResult!!(true)
        bitmap.isRecycled shouldBe true
    }

    @Test
    fun cancellationBeforeAFailedResultRecyclesTheBitmap(): Unit = runBlocking {
        val bitmap = newBitmap()
        var deliverResult: ((Boolean) -> Unit)? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            BugReport.awaitPixelCopyInto(bitmap) { onResult -> deliverResult = onResult }
        }
        job.cancelAndJoin()

        deliverResult!!(false)
        bitmap.isRecycled shouldBe true
    }
}
