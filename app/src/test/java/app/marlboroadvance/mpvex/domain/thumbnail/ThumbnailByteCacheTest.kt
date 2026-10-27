package app.marlboroadvance.mpvex.domain.thumbnail

import org.junit.Assert.*
import org.junit.Test

class ThumbnailByteCacheTest {
  @Test fun byteBudgetAndAccessOrderControlEviction() {
    val cache = ThumbnailByteCache<String>(10) { it.length.toLong() }
    cache.put("a", "1234")
    cache.put("b", "1234")
    assertEquals("1234", cache.get("a"))
    cache.put("c", "1234")
    assertNull(cache.get("b"))
    assertNotNull(cache.get("a"))
    assertEquals(8L, cache.sizeBytes)
  }

  @Test fun replaceOversizedEntryAndClear() {
    val cache = ThumbnailByteCache<String>(10) { it.length.toLong() }
    cache.put("same", "12345")
    cache.put("same", "12")
    assertEquals(2L, cache.sizeBytes)
    assertEquals("12", cache.get("same"))
    cache.put("large", "12345678901")
    assertNull(cache.get("large"))
    assertEquals(2L, cache.sizeBytes)
    cache.clear()
    assertEquals(0L, cache.sizeBytes)
    assertNull(cache.get("same"))
  }

  @Test fun evictionDoesNotInvalidateAValueRetainedByVisibleCard() {
    val cache = ThumbnailByteCache<List<Int>>(2) { it.size.toLong() }
    val visible = listOf(1, 2)
    cache.put("video", visible)
    val retained = cache.get("video")
    cache.put("other", listOf(3, 4))
    assertNull(cache.get("video"))
    assertSame(visible, retained)
    assertEquals(listOf(1, 2), retained)
  }

  @Test fun tinyOrFailedSequencesCannotAccumulateUnboundedMetadata() {
    val cache = ThumbnailByteCache<List<Int>>(100, maxEntries = 2) { 0 }
    cache.put("a", emptyList())
    cache.put("b", emptyList())
    cache.put("c", emptyList())
    assertNull(cache.get("a"))
    assertNotNull(cache.get("c"))
    assertEquals(2L, cache.sizeBytes)
  }
}
