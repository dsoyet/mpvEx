package app.marlboroadvance.mpvex.ui.browser.cards

import android.graphics.Bitmap
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.marlboroadvance.mpvex.domain.media.model.Video
import app.marlboroadvance.mpvex.domain.thumbnail.AnimatedThumbnailRepository
import app.marlboroadvance.mpvex.domain.thumbnail.animatedThumbnailActive
import app.marlboroadvance.mpvex.domain.thumbnail.runAnimatedThumbnailLoop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

@Composable
fun rememberVisibleVideoItem(state: LazyListState, key: Any): Boolean {
  val visible by remember(state, key) { derivedStateOf { state.layoutInfo.visibleItemsInfo.any { it.key == key } } }
  return visible
}

@Composable
fun rememberVisibleVideoItem(state: LazyGridState, key: Any): Boolean {
  val visible by remember(state, key) { derivedStateOf { state.layoutInfo.visibleItemsInfo.any { it.key == key } } }
  return visible
}

@Composable
internal fun rememberAnimatedVideoFrame(video: Video, static: Bitmap?, visible: Boolean, show: Boolean): Bitmap? {
  val repository = koinInject<AnimatedThumbnailRepository>()
  val config by repository.config.collectAsState()
  val epoch by repository.cacheEpoch.collectAsState()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, _ ->
      resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  val key = config.cacheKey(video.path.ifBlank { video.uri.toString() }, video.size, video.dateModified, video.duration)
  var frames by remember(key, epoch) { mutableStateOf<List<Bitmap>>(emptyList()) }
  var index by remember(key, epoch) { mutableIntStateOf(0) }
  val active = animatedThumbnailActive(config.enabled && show, visible, resumed, static != null)

  LaunchedEffect(key, epoch, active) {
    if (active) {
      // Lazy layouts may briefly place/prefetch items while flinging. Do not decode those items.
      delay(400)
      try {
        frames = repository.getFrames(video, config)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        frames = emptyList()
      }
    }
  }
  LaunchedEffect(key, epoch, active, frames, config.intervalMillis) {
    if (active && frames.size > 1) {
      runAnimatedThumbnailLoop(frames.size, config.intervalMillis, index, { index = it })
    }
  }
  return if (active) frames.getOrNull(index % frames.size.coerceAtLeast(1)) ?: static else static
}
