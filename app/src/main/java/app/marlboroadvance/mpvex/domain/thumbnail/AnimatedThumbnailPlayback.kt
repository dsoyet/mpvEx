package app.marlboroadvance.mpvex.domain.thumbnail

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** A cancellable presentation-only loop; never calls the decoder or detector. */
internal suspend fun runAnimatedThumbnailLoop(
  frameCount: Int,
  intervalMillis: Int,
  initialIndex: Int,
  onFrame: (Int) -> Unit,
  waitForNext: suspend (Long) -> Unit = { delay(it) },
) {
  if (frameCount <= 1) return
  var index = initialIndex.coerceAtLeast(0) % frameCount
  while (true) {
    currentCoroutineContext().ensureActive()
    waitForNext(intervalMillis.coerceIn(500, 3000).toLong())
    currentCoroutineContext().ensureActive()
    index = (index + 1) % frameCount
    onFrame(index)
  }
}
