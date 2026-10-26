package app.marlboroadvance.mpvex.domain.thumbnail

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ThumbnailGenerationTest {
  @Test fun stopsAtFirstAcceptableFrame() = runBlocking {
    var calls = 0
    val result = selectThumbnailFrame(listOf(30.0, 40.0, 20.0), true, {},
      { calls++; it }, { LumaStatistics(80.0, 0.0) }, {})
    assertEquals(30.0, result)
    assertEquals(1, calls)
  }

  @Test fun skipsFailedAndDarkFrames() = runBlocking {
    val discarded = mutableListOf<Int>()
    val result = selectThumbnailFrame(listOf(1.0, 2.0, 3.0, 4.0), true, {},
      { if (it == 1.0) null else it.toInt() },
      { if (it == 2) LumaStatistics(0.0, 1.0) else LumaStatistics(70.0, 0.0) },
      { discarded.add(it) })
    assertEquals(3, result)
    assertEquals(listOf(2), discarded)
  }

  @Test fun allDarkReturnsBestFrameWithoutExtraDecode() = runBlocking {
    var calls = 0
    val discarded = mutableListOf<Int>()
    val stats = mapOf(1 to LumaStatistics(0.0, 1.0), 2 to LumaStatistics(10.0, 0.96), 3 to LumaStatistics(14.0, 0.96))
    val result = selectThumbnailFrame(listOf(1.0, 2.0, 3.0), true, {},
      { calls++; it.toInt() }, { stats[it] }, { discarded.add(it) })
    assertEquals(3, result)
    assertEquals(3, calls)
    assertEquals(listOf(1, 2), discarded)
  }

  @Test fun allFailuresAndEmptyCandidatesReturnNull() = runBlocking {
    assertNull(selectThumbnailFrame<Int>(listOf(1.0, 2.0), true, {}, { null }, { null }, {}))
    assertNull(selectThumbnailFrame<Int>(emptyList(), true, {}, { error("No decode") }, { null }, {}))
  }

  @Test fun disabledDetectionUsesOneFrame() = runBlocking {
    var calls = 0
    assertEquals(1, selectThumbnailFrame(listOf(1.0, 2.0), false, {},
      { calls++; 1 }, { error("No detection") }, {}))
    assertEquals(1, calls)
  }

  @Test fun cancellationBetweenCandidatesDiscardsOwnedFrame() = runBlocking {
    var checks = 0
    var discarded = 0
    try {
      selectThumbnailFrame(listOf(1.0, 2.0), true,
        { if (++checks == 2) throw CancellationException() },
        { 1 }, { LumaStatistics(0.0, 1.0) }, { discarded++ })
      fail("Cancellation must propagate")
    } catch (_: CancellationException) {
      assertEquals(1, discarded)
    }
  }

  @Test fun sameConfigurationSharesWorkAndCancelledWaiterDoesNotCancelOthers() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val requests = ThumbnailRequests<String>(scope)
      val gate = CompletableDeferred<Unit>()
      var calls = 0
      val first = async(start = CoroutineStart.UNDISPATCHED) { requests.await("same") { calls++; gate.await(); "frame" } }
      val second = async(start = CoroutineStart.UNDISPATCHED) { requests.await("same") { error("Duplicate decode") } }
      first.cancelAndJoin()
      gate.complete(Unit)
      assertEquals("frame", second.await())
      assertEquals(1, calls)
    } finally { scope.cancel() }
  }

  @Test fun oldRequestCannotOverwriteNewConfiguration() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val requests = ThumbnailRequests<String>(scope)
      val oldGate = CompletableDeferred<Unit>()
      val cache = mutableMapOf<String, String>()
      val oldKey = ThumbnailConfig().cacheKey("video", "disk|d1024")
      val newKey = ThumbnailConfig(positionPercent = 40).cacheKey("video", "disk|d1024")
      val old = async(start = CoroutineStart.UNDISPATCHED) {
        requests.await(oldKey) { oldGate.await(); "old".also { cache[oldKey] = it } }
      }
      assertEquals("new", requests.await(newKey) { "new".also { cache[newKey] = it } })
      oldGate.complete(Unit)
      old.await()
      assertEquals("new", cache[newKey])
      assertEquals("old", cache[oldKey])
    } finally { scope.cancel() }
  }

  @Test fun configurationChangeCancelsObsoleteWorkAndPreservesCurrentRequest() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val requests = ThumbnailRequests<String>(scope)
      val config = ThumbnailConfig(positionPercent = 40)
      val gate = CompletableDeferred<Unit>()
      val old = async(start = CoroutineStart.UNDISPATCHED) {
        requests.await(ThumbnailConfig().cacheKey("video", "disk")) { gate.await(); "old" }
      }
      val current = async(start = CoroutineStart.UNDISPATCHED) {
        requests.await(config.cacheKey("video", "disk")) { gate.await(); "current" }
      }
      requests.cancelExcept(config)
      try {
        old.await()
        fail("Old operation must be cancelled")
      } catch (_: CancellationException) {
        gate.complete(Unit)
        assertEquals("current", current.await())
      }
    } finally { scope.cancel() }
  }
}
