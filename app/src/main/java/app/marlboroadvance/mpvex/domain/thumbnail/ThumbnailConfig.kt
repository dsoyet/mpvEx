package app.marlboroadvance.mpvex.domain.thumbnail

import kotlin.math.max

enum class ThumbnailMode { FIXED, PERCENTAGE, SMART }

/** Immutable, sanitized snapshot used throughout one request, including every cache key. */
data class ThumbnailConfig(
  val mode: ThumbnailMode = ThumbnailMode.PERCENTAGE,
  val positionSeconds: Int = 3,
  val positionPercent: Int = 30,
  val avoidDarkFrames: Boolean = true,
  val maxAttempts: Int = 5,
) {
  fun normalized() = copy(
    positionSeconds = positionSeconds.coerceIn(0, 600),
    positionPercent = (positionPercent.coerceIn(0, 95) / 5) * 5,
    maxAttempts = maxAttempts.coerceIn(1, 5),
  )

  val fingerprint: String
    get() = normalized().let {
      "v2|${it.mode.name}|${it.positionSeconds}|${it.positionPercent}|${it.avoidDarkFrames}|${it.maxAttempts}"
    }

  fun cacheKey(identity: String, dimensions: String): String = "$identity|$dimensions|$fingerprint"

  fun positions(durationMillis: Long): List<Double> {
    val config = normalized()
    val duration = durationMillis / 1000.0
    if (duration <= 0.0) {
      // Unknown duration: preserve the old first-frame fallback for percentage modes.
      return listOf(if (mode == ThumbnailMode.FIXED) config.positionSeconds.toDouble() else 0.0)
    }
    val upper = max(0.0, duration - 0.1)
    if (mode == ThumbnailMode.FIXED) return listOf(config.positionSeconds.toDouble().coerceIn(0.0, upper))
    val offsets = if (mode == ThumbnailMode.SMART && avoidDarkFrames) listOf(0, 10, -10, 20, 30) else listOf(0)
    return offsets.map { offset ->
      (duration * (config.positionPercent + offset).coerceIn(0, 95) / 100.0).coerceIn(0.0, upper)
    }.distinct().take(config.maxAttempts)
  }
}
