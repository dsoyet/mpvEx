package app.marlboroadvance.mpvex.domain.thumbnail

import org.junit.Assert.*
import org.junit.Test

class ThumbnailConfigTest {
  @Test fun percentageDurations() {
    val config = ThumbnailConfig()
    for ((duration, expected) in listOf(0L to 0.0, -1L to 0.0, 5000L to 1.5, 600000L to 180.0, 7200000L to 2160.0)) {
      assertEquals(expected, config.positions(duration).single(), 0.0001)
    }
  }

  @Test fun fixedClampsToDurationAndHandlesUnknown() {
    val config = ThumbnailConfig(mode = ThumbnailMode.FIXED)
    assertEquals(3.0, config.positions(0).single(), 0.0)
    assertEquals(3.0, config.positions(-1).single(), 0.0)
    assertEquals(3.0, config.positions(5000).single(), 0.0)
    assertEquals(4.9, config.copy(positionSeconds = 600).positions(5000).single(), 0.0001)
    assertEquals(0.0, config.copy(positionSeconds = -10).positions(5000).single(), 0.0)
    assertEquals(600.0, config.copy(positionSeconds = Int.MAX_VALUE).positions(7200000).single(), 0.0)
  }

  @Test fun percentEndpointsAndIllegalConfiguration() {
    assertEquals(listOf(0.0), ThumbnailConfig(positionPercent = 0).positions(5000))
    assertEquals(listOf(4.75), ThumbnailConfig(positionPercent = 95).positions(5000))
    assertEquals(listOf(4.75), ThumbnailConfig(positionPercent = Int.MAX_VALUE).positions(5000))
    assertEquals(listOf(0.0), ThumbnailConfig(positionPercent = -1).positions(5000))
    assertEquals(30, ThumbnailConfig(positionPercent = 34).normalized().positionPercent)
  }

  @Test fun smartCandidatesAreBoundedOrderedAndDistinct() {
    val config = ThumbnailConfig(mode = ThumbnailMode.SMART)
    assertEquals(listOf(30.0, 40.0, 20.0, 50.0, 60.0), config.positions(100000))
    assertEquals(listOf(0.0, 10.0, 20.0, 30.0), config.copy(positionPercent = 0).positions(100000))
    assertEquals(listOf(95.0, 85.0), config.copy(positionPercent = 95).positions(100000))
    assertEquals(listOf(0.0), config.positions(50))
    assertEquals(listOf(0.0), config.positions(-1))
    assertEquals(listOf(30.0), config.copy(maxAttempts = 1).positions(100000))
    assertEquals(listOf(30.0), config.copy(maxAttempts = -10).positions(100000))
    assertEquals(5, config.copy(maxAttempts = 100).positions(100000).size)
    assertEquals(listOf(30.0), config.copy(avoidDarkFrames = false).positions(100000))
  }

  @Test fun cachesIncludeConfigurationVersionAndDimensions() {
    val config = ThumbnailConfig()
    val key = config.cacheKey("video", "disk|d1024")
    val changes = listOf(
      config.copy(positionPercent = 35), config.copy(mode = ThumbnailMode.FIXED),
      config.copy(mode = ThumbnailMode.SMART), config.copy(positionSeconds = 4),
      config.copy(avoidDarkFrames = false), config.copy(maxAttempts = 1),
    )
    changes.forEach { assertNotEquals(key, it.cacheKey("video", "disk|d1024")) }
    assertNotEquals(key, "video|disk|d1024") // Legacy cache.
    assertNotEquals(key, config.cacheKey("video", "disk|d512"))
    assertNotEquals(key, config.cacheKey("other-video", "disk|d1024"))
    assertEquals(key, config.copy().cacheKey("video", "disk|d1024"))
  }
}
