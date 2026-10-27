package app.marlboroadvance.mpvex.domain.thumbnail

import org.junit.Assert.*
import org.junit.Test

class AnimatedThumbnailConfigTest {
  @Test fun defaultPositionsSpanEntireRange() {
    assertEquals(listOf(720.0, 2160.0, 3600.0, 5040.0, 6480.0), AnimatedThumbnailConfig().positions(7200000))
    assertEquals(listOf(10.0, 30.0, 50.0, 70.0, 90.0), AnimatedThumbnailConfig().positions(100000))
  }

  @Test fun customCountAndRange() {
    assertEquals(listOf(20.0, 50.0, 80.0), AnimatedThumbnailConfig(frameCount = 3, startPercent = 20, endPercent = 80).positions(100000))
    assertEquals(listOf(0.0, 99.9), AnimatedThumbnailConfig(frameCount = 2, startPercent = 0, endPercent = 100).positions(100000))
    assertEquals(10, AnimatedThumbnailConfig(frameCount = 10).positions(100000).size)
  }

  @Test fun shortUnknownAndInvalidInput() {
    assertEquals(emptyList<Double>(), AnimatedThumbnailConfig().positions(0))
    assertEquals(emptyList<Double>(), AnimatedThumbnailConfig().positions(-1))
    assertEquals(listOf(0.0), AnimatedThumbnailConfig().positions(50))
    val config = AnimatedThumbnailConfig(frameCount = 100, intervalMillis = -1, startPercent = 99, endPercent = -10).normalized()
    assertEquals(10, config.frameCount)
    assertEquals(500, config.intervalMillis)
    assertEquals(40, config.startPercent)
    assertEquals(60, config.endPercent)
    assertEquals(2, config.copy(frameCount = -1).normalized().frameCount)
    assertEquals(3000, config.copy(intervalMillis = Int.MAX_VALUE).normalized().intervalMillis)
    config.positions(Long.MAX_VALUE).forEach { assertTrue(it.isFinite() && it >= 0) }
  }

  @Test fun retryCandidatesAreBoundedAndDistinct() {
    val config = AnimatedThumbnailConfig()
    assertEquals(listOf(10.0, 12.0, 8.0), config.candidates(10.0, 100000))
    assertEquals(listOf(99.9, 97.9), config.candidates(99.9, 100000))
    assertEquals(listOf(0.0), config.candidates(0.0, 50))
    assertEquals(emptyList<Double>(), config.candidates(Double.NaN, 100000))
    assertEquals(emptyList<Double>(), config.candidates(1.0, 0))
    assertEquals(listOf(10.0), config.copy(skipDarkFrames = false).candidates(10.0, 100000))
  }

  @Test fun keysIncludeIdentityAndGenerationSettingsOnly() {
    val config = AnimatedThumbnailConfig()
    fun key(c: AnimatedThumbnailConfig = config, identity: String = "/first.mp4", size: Long = 100, modified: Long = 10, duration: Long = 1000) =
      c.cacheKey(identity, size, modified, duration)
    assertEquals(key(), key(config.copy()))
    assertEquals(key(), key(config.copy(intervalMillis = 500)))
    assertEquals(key(), key(config.copy(enabled = false)))
    assertNotEquals(key(), key(identity = "/second.mp4"))
    assertNotEquals(key(), key(size = 200))
    assertNotEquals(key(), key(modified = 11))
    assertNotEquals(key(), key(duration = 2000))
    assertNotEquals(key(), key(config.copy(frameCount = 6)))
    assertNotEquals(key(), key(config.copy(startPercent = 20)))
    assertNotEquals(key(), key(config.copy(endPercent = 80)))
    assertNotEquals(key(), key(config.copy(skipDarkFrames = false)))
    assertTrue(key().contains("animated-v1|d256"))
  }

  @Test fun onlyVisibleForegroundEnabledCardsWithStaticImagesAreEligible() {
    assertTrue(animatedThumbnailActive(true, true, true, true))
    assertFalse(animatedThumbnailActive(false, true, true, true))
    assertFalse(animatedThumbnailActive(true, false, true, true))
    assertFalse(animatedThumbnailActive(true, true, false, true))
    assertFalse(animatedThumbnailActive(true, true, true, false))
  }
}
