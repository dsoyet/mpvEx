package app.marlboroadvance.mpvex.domain.thumbnail

data class AnimatedThumbnailConfig(
  val enabled: Boolean = true,
  val frameCount: Int = 5,
  val intervalMillis: Int = 1500,
  val startPercent: Int = 10,
  val endPercent: Int = 90,
  val skipDarkFrames: Boolean = true,
) {
  fun normalized() = copy(
    frameCount = frameCount.coerceIn(2, 10),
    intervalMillis = intervalMillis.coerceIn(500, 3000),
    startPercent = startPercent.coerceIn(0, 40),
    endPercent = endPercent.coerceIn(60, 100),
  )

  // Timing and enable/disable only affect presentation, never decoded content.
  val fingerprint: String get() = normalized().let {
    "animated-v1|d256|${it.frameCount}|${it.startPercent}|${it.endPercent}|${it.skipDarkFrames}"
  }

  fun cacheKey(identity: String, size: Long, modified: Long, duration: Long): String =
    "$identity|$size|$modified|$duration|$fingerprint"

  fun positions(durationMillis: Long): List<Double> {
    if (durationMillis <= 0) return emptyList()
    val config = normalized()
    val duration = durationMillis / 1000.0
    val upper = (duration - 0.1).coerceAtLeast(0.0)
    return (0 until config.frameCount).map { i ->
      val percent = config.startPercent + i * (config.endPercent - config.startPercent).toDouble() / (config.frameCount - 1)
      (duration * percent / 100.0).coerceIn(0.0, upper)
    }.distinct()
  }

  fun candidates(position: Double, durationMillis: Long): List<Double> {
    if (durationMillis <= 0 || !position.isFinite()) return emptyList()
    val duration = durationMillis / 1000.0
    val upper = (duration - 0.1).coerceAtLeast(0.0)
    val offsets = if (skipDarkFrames) listOf(0.0, 0.02, -0.02) else listOf(0.0)
    return offsets.map { (position + duration * it).coerceIn(0.0, upper) }.distinct()
  }
}

/** The same eligibility gate is used for generation and the frame-switching coroutine. */
internal fun animatedThumbnailActive(enabled: Boolean, visible: Boolean, resumed: Boolean, hasStatic: Boolean) =
  enabled && visible && resumed && hasStatic
