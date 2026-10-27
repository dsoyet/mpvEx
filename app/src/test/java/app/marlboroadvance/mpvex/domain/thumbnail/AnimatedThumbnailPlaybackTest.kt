package app.marlboroadvance.mpvex.domain.thumbnail

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AnimatedThumbnailPlaybackTest {
  @Test fun loopsThroughAllFramesWithoutDecodingAgain() = runBlocking {
    val frames = mutableListOf<Int>()
    var waits = 0
    try {
      runAnimatedThumbnailLoop(3, 1500, 0, { frames += it }, { interval ->
        assertEquals(1500L, interval)
        if (++waits == 5) throw CancellationException()
      })
    } catch (_: CancellationException) { }
    assertEquals(listOf(1, 2, 0, 1), frames)
  }

  @Test fun singleOrNoFrameNeverStartsTimer() = runBlocking {
    for (count in 0..1) {
      runAnimatedThumbnailLoop(count, 1500, 0, { error("No switching") }, { error("No timer") })
    }
  }

  @Test fun departureOrBackgroundCancelsTimerAndReturnResumesCurrentIndex() = runBlocking {
    val waiting = CompletableDeferred<Unit>()
    var emitted = 0
    val timer = launch(start = CoroutineStart.UNDISPATCHED) {
      runAnimatedThumbnailLoop(5, 1500, 2, { emitted++ }, { waiting.complete(Unit); awaitCancellation() })
    }
    waiting.await()
    timer.cancelAndJoin()
    assertEquals(0, emitted)
    var next = -1
    var waits = 0
    try {
      runAnimatedThumbnailLoop(5, 1500, 2, { next = it }, { if (++waits > 1) throw CancellationException() })
    } catch (_: CancellationException) { }
    assertEquals(3, next)
  }
}
