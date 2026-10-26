package app.marlboroadvance.mpvex.domain.thumbnail

import android.graphics.Bitmap

object DarkFrameDetector {
  /** Invalid, recycled or unreadable hardware bitmaps must not crash thumbnail generation. */
  fun analyze(bitmap: Bitmap): LumaStatistics? = try {
    if (bitmap.isRecycled) null else LumaSampler.sample(bitmap.width, bitmap.height, bitmap::getPixel)
  } catch (_: IllegalArgumentException) {
    null
  } catch (_: IllegalStateException) {
    null
  }
}
