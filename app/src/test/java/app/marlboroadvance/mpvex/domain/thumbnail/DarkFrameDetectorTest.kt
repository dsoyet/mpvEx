package app.marlboroadvance.mpvex.domain.thumbnail

import org.junit.Assert.*
import org.junit.Test

class DarkFrameDetectorTest {
  private fun solid(color: Int) = LumaSampler.sample(1000, 1000) { _, _ -> color }!!

  @Test fun blackAndNearBlack() {
    assertTrue(solid(0).isDark)
    assertEquals(0.0, solid(0).averageLuma, 0.0001)
    assertEquals(1.0, solid(0).darkPixelRatio, 0.0)
    assertTrue(solid(0x0e0e0e).isDark)
  }

  @Test fun whiteNormalAndNightScene() {
    assertFalse(solid(0xffffff).isDark)
    assertEquals(255.0, solid(0xffffff).averageLuma, 0.0001)
    assertFalse(solid(0x808080).isDark)
    // A dim night scene should not be rejected solely because most pixels are below 20.
    assertFalse(solid(0x121212).isDark)
    val night = LumaSampler.sample(32, 32) { x, _ -> if (x < 4) 0x606060 else 0x080808 }!!
    assertFalse(night.isDark)
  }

  @Test fun ninetyFivePercentBlack() {
    val stats = LumaSampler.sample(20, 20) { x, _ -> if (x == 0) 0xffffff else 0 }!!
    assertTrue(stats.isDark)
    assertEquals(0.95, stats.darkPixelRatio, 0.0001)
    assertEquals(12.75, stats.averageLuma, 0.0001)
  }

  @Test fun smallAndInvalidDimensionsAndSampleBudget() {
    assertTrue(LumaSampler.sample(1, 1) { x, y -> assertEquals(0, x + y); 0 }!!.isDark)
    assertNull(LumaSampler.sample(0, 1) { _, _ -> error("Must not sample") })
    assertNull(LumaSampler.sample(1, -1) { _, _ -> error("Must not sample") })
    var count = 0
    LumaSampler.sample(3840, 2160) { x, y ->
      assertTrue(x in 0 until 3840 && y in 0 until 2160)
      count++; 0
    }
    assertEquals(1024, count)
  }

  @Test fun scorePrefersLowerDarkRatioThenHigherLuma() {
    assertTrue(LumaStatistics(1.0, 0.95).betterThan(LumaStatistics(14.0, 1.0)))
    assertTrue(LumaStatistics(14.0, 1.0).betterThan(LumaStatistics(1.0, 1.0)))
    assertFalse(LumaStatistics(1.0, 1.0).betterThan(LumaStatistics(1.0, 1.0)))
  }
}
