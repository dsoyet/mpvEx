package app.marlboroadvance.mpvex.domain.thumbnail

data class LumaStatistics(val averageLuma: Double, val darkPixelRatio: Double) {
  val isDark: Boolean get() = darkPixelRatio >= 0.95 && averageLuma <= 15.0

  fun betterThan(other: LumaStatistics): Boolean =
    darkPixelRatio < other.darkPixelRatio ||
      (darkPixelRatio == other.darkPixelRatio && averageLuma > other.averageLuma)
}

/** Samples at most 32 x 32 grid cells, without allocating another image or a pixel array. */
object LumaSampler {
  fun sample(width: Int, height: Int, pixelAt: (Int, Int) -> Int): LumaStatistics? {
    if (width <= 0 || height <= 0) return null
    val columns = minOf(width, 32)
    val rows = minOf(height, 32)
    var sum = 0.0
    var dark = 0
    for (row in 0 until rows) {
      val y = ((row + 0.5) * height / rows).toInt().coerceAtMost(height - 1)
      for (column in 0 until columns) {
        val x = ((column + 0.5) * width / columns).toInt().coerceAtMost(width - 1)
        val pixel = pixelAt(x, y)
        val luma = 0.2126 * ((pixel ushr 16) and 255) +
          0.7152 * ((pixel ushr 8) and 255) + 0.0722 * (pixel and 255)
        sum += luma
        if (luma < 20.0) dark++
      }
    }
    val count = columns * rows
    return LumaStatistics(sum / count, dark.toDouble() / count)
  }
}

/** Decoding stays sequential. Ownership of discarded frames is returned to the caller. */
internal suspend fun <T : Any> selectThumbnailFrame(
  positions: List<Double>,
  avoidDarkFrames: Boolean,
  checkCancellation: () -> Unit,
  decode: suspend (Double) -> T?,
  analyze: (T) -> LumaStatistics?,
  discard: (T) -> Unit,
): T? {
  var best: T? = null
  var bestStats: LumaStatistics? = null
  try {
    for (position in positions) {
      checkCancellation()
      val frame = decode(position) ?: continue
      val stats = if (avoidDarkFrames) analyze(frame) else null
      if (!avoidDarkFrames || stats == null || !stats.isDark) {
        best?.let(discard)
        best = frame
        break
      }
      if (bestStats == null || stats.betterThan(bestStats)) {
        best?.let(discard)
        best = frame
        bestStats = stats
      } else {
        discard(frame)
      }
    }
    checkCancellation()
    return best.also { best = null }
  } finally {
    best?.let(discard)
  }
}
