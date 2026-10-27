package app.marlboroadvance.mpvex.domain.thumbnail

import android.content.Context
import android.graphics.Bitmap
import app.marlboroadvance.mpvex.domain.media.model.Video
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class AnimatedThumbnailRepository(
  context: Context,
  preferences: AdvancedPreferences,
  private val thumbnails: ThumbnailRepository,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val requests = VisibleThumbnailRequests<List<Bitmap>>(scope)
  private val slots = Semaphore(2)
  private val memory = ThumbnailByteCache<List<Bitmap>>(64L * 1024 * 1024) { frames ->
    frames.sumOf { it.allocationByteCount.toLong() }
  }
  private val disk = AnimatedThumbnailDiskCache(context.cacheDir)
  private val _cacheEpoch = MutableStateFlow(0L)
  val cacheEpoch = _cacheEpoch.asStateFlow()
  val config = preferences.animatedThumbnailConfigChanges.stateIn(
    scope, SharingStarted.Eagerly, preferences.animatedThumbnailConfig(),
  )

  init {
    scope.launch { disk.trim() }
    scope.launch {
      var previous = config.value.enabled to config.value.fingerprint
      config.map { it.enabled to it.fingerprint }.distinctUntilChanged().collect { current ->
        if (current != previous) {
          // A newly visible card may already have subscribed to the new config.
          // Cancel only obsolete snapshots, never a request for the current generation.
          requests.cancelUnless { key ->
            current.first && key.endsWith("|${current.second}|epoch${cacheEpoch.value}")
          }
        }
        previous = current
      }
    }
  }

  /** No folder-prefetch entry point: callers must be visible, foreground cards. */
  suspend fun getFrames(video: Video, snapshot: AnimatedThumbnailConfig): List<Bitmap> = withContext(Dispatchers.IO) {
    val config = snapshot.normalized()
    if (!config.enabled || !thumbnails.supportsThumbnails(video) || config.positions(video.duration).isEmpty()) {
      return@withContext emptyList()
    }
    val epoch = cacheEpoch.value
    val diskKey = config.cacheKey(video.path.ifBlank { video.uri.toString() }, video.size, video.dateModified, video.duration)
    val key = "$diskKey|epoch$epoch"
    memory.get(key)?.let { return@withContext it }
    requests.await(key) {
      memory.get(key) ?: slots.withPermit {
        val coroutineContext = currentCoroutineContext()
        val check = {
          coroutineContext.ensureActive()
          if (epoch != cacheEpoch.value) throw kotlinx.coroutines.CancellationException("Thumbnail cache cleared")
        }
        val ownedFrames = mutableListOf<Bitmap>()
        var published = false
        try {
          check()
          val cached = disk.read(diskKey, check)
          if (cached != null) {
            ownedFrames.addAll(cached)
          } else {
            // Native FastThumbnails exposes individual seeks; its multiple-frame API also loops.
            // Decode sequentially, with bounded retries, at 256px rather than creating a player.
            val rotation = thumbnails.animatedRotation(video)
            val attempted = mutableSetOf<Double>()
            for (position in config.positions(video.duration)) {
              check()
              val candidates = config.candidates(position, video.duration).filterNot { it in attempted }
              val bitmap = selectThumbnailFrame(
                candidates, config.skipDarkFrames, check,
                decode = { attempted.add(it); thumbnails.decodeAnimatedFrame(video, it, rotation) },
                analyze = DarkFrameDetector::analyze,
                discard = { it.recycle() },
              ) ?: continue
              ownedFrames += bitmap
            }
            check()
            disk.write(diskKey, ownedFrames, check)
          }
          check()
          val frames = ownedFrames.toList()
          memory.put(key, frames)
          published = true
          frames
        } finally {
          // Published bitmaps may be rendered even after LRU eviction. Only recycle private work.
          if (!published) ownedFrames.forEach { if (!it.isRecycled) it.recycle() }
        }
      }
    }
  }

  suspend fun clearCache() = withContext(Dispatchers.IO) {
    _cacheEpoch.update { it + 1 }
    requests.cancelUnless { it.endsWith("|epoch${cacheEpoch.value}") }
    memory.clear()
    disk.clear()
  }
}
