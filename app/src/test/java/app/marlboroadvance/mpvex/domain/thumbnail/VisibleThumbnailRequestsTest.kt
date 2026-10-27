package app.marlboroadvance.mpvex.domain.thumbnail

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.*
import org.junit.Test

class VisibleThumbnailRequestsTest {
  @Test fun duplicateCardsShareWorkAndLastDepartureCancelsIt() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val requests = VisibleThumbnailRequests<Int>(scope)
      val started = CompletableDeferred<Unit>()
      val cancelled = CompletableDeferred<Unit>()
      var calls = 0
      val first = async(start = CoroutineStart.UNDISPATCHED) {
        requests.await("video") {
          calls++
          started.complete(Unit)
          try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
      }
      started.await()
      val second = async(start = CoroutineStart.UNDISPATCHED) { requests.await("video") { error("Duplicate decode") } }
      first.cancelAndJoin()
      assertFalse(cancelled.isCompleted)
      second.cancelAndJoin()
      cancelled.await()
      assertEquals(1, calls)
      assertEquals(7, requests.await("video") { 7 }) // Re-enter after cancellation.
    } finally { scope.cancel() }
  }

  @Test fun queuedInvisibleItemIsDroppedAndConcurrencyNeverExceedsTwo() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val requests = VisibleThumbnailRequests<Int>(scope)
      val semaphore = Semaphore(2)
      val gate = CompletableDeferred<Unit>()
      var active = 0
      var maximum = 0
      val started = mutableListOf<Int>()
      val jobs = (1..3).map { id ->
        async(start = CoroutineStart.UNDISPATCHED) {
          requests.await("$id") {
            semaphore.withPermit {
              started += id
              active++
              maximum = maxOf(maximum, active)
              try { gate.await(); id } finally { active-- }
            }
          }
        }
      }
      assertEquals(listOf(1, 2), started)
      jobs[2].cancelAndJoin()
      gate.complete(Unit)
      assertEquals(1, jobs[0].await())
      assertEquals(2, jobs[1].await())
      assertEquals(listOf(1, 2), started)
      assertEquals(2, maximum)
    } finally { scope.cancel() }
  }

  @Test fun configChangeAndCacheClearCancelAllPendingWork() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val requests = VisibleThumbnailRequests<Int>(scope)
      val old = (1..3).map { id ->
        async(start = CoroutineStart.UNDISPATCHED) { requests.await("old$id") { awaitCancellation() } }
      }
      requests.cancelAll()
      old.forEach { job ->
        try { job.await(); fail("Must cancel obsolete requests") } catch (_: CancellationException) { }
      }
      assertEquals(3, requests.await("new") { 3 })
    } finally { scope.cancel() }
  }

  @Test fun changingConfigDoesNotCancelNewlyRegisteredCurrentWork() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val requests = VisibleThumbnailRequests<Int>(scope)
      val gate = CompletableDeferred<Unit>()
      val old = async(start = CoroutineStart.UNDISPATCHED) { requests.await("old") { gate.await(); 1 } }
      val current = async(start = CoroutineStart.UNDISPATCHED) { requests.await("current") { gate.await(); 2 } }
      requests.cancelUnless { it == "current" }
      try { old.await(); fail("Old config must cancel") } catch (_: CancellationException) { }
      gate.complete(Unit)
      assertEquals(2, current.await())
    } finally { scope.cancel() }
  }
}
