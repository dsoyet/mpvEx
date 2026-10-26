package app.marlboroadvance.mpvex.domain.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.marlboroadvance.mpvex.domain.media.model.Video
import app.marlboroadvance.mpvex.utils.media.MediaInfoOps
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ThumbnailRepository(
  private val context: Context,
  private val preferences: AdvancedPreferences,
) {
  private val diskCacheDimension = 1024
  private val diskJpegQuality = 85
  private val memoryCache: LruCache<String, Bitmap>
  private val diskDir: File = File(context.filesDir, "thumbnails").apply { mkdirs() }

  private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val ongoingOperations = ThumbnailRequests<Bitmap?>(repositoryScope)
  private val maxconcurrentfolders = 3
  private val generationSlots = Semaphore(3)
  private val diskLock = Any()
  private val _config = MutableStateFlow(preferences.thumbnailConfig())
  val config = _config.asStateFlow()

  private data class FolderState(
    val signature: String,
    @Volatile var nextIndex: Int = 0,
  )

  private val folderStates = ConcurrentHashMap<String, FolderState>()
  private val folderJobs = ConcurrentHashMap<String, Job>()

  private val _thumbnailReadyKeys =
    MutableSharedFlow<String>(
      extraBufferCapacity = 256,
    )
  val thumbnailReadyKeys: SharedFlow<String> = _thumbnailReadyKeys.asSharedFlow()

  init {
    val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
    val cacheSizeKb = maxMemoryKb / 6
    memoryCache =
      object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(
          key: String,
          value: Bitmap,
        ): Int = value.byteCount / 1024
      }
    repositoryScope.launch {
      trimDiskCache()
      preferences.thumbnailConfigChanges.distinctUntilChanged().collect { snapshot ->
        if (_config.value == snapshot) return@collect
        folderJobs.values.forEach { it.cancel() }
        folderJobs.clear()
        folderStates.clear()
        ongoingOperations.cancelExcept(snapshot)
        memoryCache.evictAll()
        _config.value = snapshot
      }
    }
  }

  suspend fun getThumbnail(
    video: Video,
    widthPx: Int,
    heightPx: Int,
    snapshot: ThumbnailConfig = preferences.thumbnailConfig(),
  ): Bitmap? = withContext(Dispatchers.IO) {
    if (isNetworkUrl(video.path)) return@withContext null
    val key = thumbnailKey(video, widthPx, heightPx, snapshot)
    memoryCache.get(key)?.let { return@withContext it }

    // Atomic registration before starting avoids two callers decoding the same disk thumbnail.
    // The shared task belongs to the repository: cancelling one waiter cannot cancel other waiters.
    val operationKey = diskKey(video, snapshot)
    ongoingOperations.await(operationKey) {
      loadFromDisk(video, snapshot = snapshot) ?: generationSlots.withPermit {
        currentCoroutineContext().ensureActive()
        val thumbnail = generateWithFastThumbnails(video, diskCacheDimension, snapshot)
          ?: generateWithMediaStore(video, diskCacheDimension)
        currentCoroutineContext().ensureActive()
        thumbnail?.also { writeToDisk(video, it, snapshot) }
      }
    }?.also {
      memoryCache.put(key, it)
      _thumbnailReadyKeys.tryEmit(key)
    }
  }

  suspend fun getCachedThumbnail(
    video: Video,
    widthPx: Int,
    heightPx: Int,
    snapshot: ThumbnailConfig = preferences.thumbnailConfig(),
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      if (isNetworkUrl(video.path)) {
        return@withContext null
      }
      
      val key = thumbnailKey(video, widthPx, heightPx, snapshot)
      synchronized(memoryCache) { memoryCache.get(key) }?.let { return@withContext it }
      loadFromDisk(video, widthPx, snapshot)?.let { thumbnail ->
        synchronized(memoryCache) { memoryCache.put(key, thumbnail) }
        return@withContext thumbnail
      }
      null
    }

  fun getThumbnailFromMemory(
    video: Video,
    widthPx: Int,
    heightPx: Int,
    snapshot: ThumbnailConfig = preferences.thumbnailConfig(),
  ): Bitmap? {
    if (isNetworkUrl(video.path)) {
      return null
    }
    
    val key = thumbnailKey(video, widthPx, heightPx, snapshot)
    return synchronized(memoryCache) { memoryCache.get(key) }
  }

  fun clearThumbnailCache() {
    folderJobs.values.forEach { it.cancel() }
    folderJobs.clear()
    folderStates.clear()
    ongoingOperations.cancelAll()

    synchronized(memoryCache) {
      memoryCache.evictAll()
    }

    synchronized(diskLock) {
      if (diskDir.exists()) {
        diskDir.listFiles()?.forEach { it.delete() }
      }
    }
  }

  fun startFolderThumbnailGeneration(
    folderId: String,
    videos: List<Video>,
    widthPx: Int,
    heightPx: Int,
  ) {
    if (videos.isEmpty()) return
    
    val snapshot = preferences.thumbnailConfig()
    folderJobs.remove(folderId)?.cancel()
    folderJobs[folderId] =
      repositoryScope.launch(Dispatchers.Default) {
        val filteredVideos = videos.filterNot { isNetworkUrl(it.path) }

        if (filteredVideos.isEmpty() || !isActive) return@launch

        folderJobs.entries.removeAll { !it.value.isActive }

        if (folderJobs.size >= maxconcurrentfolders && !folderJobs.containsKey(folderId)) {
          folderJobs.entries.firstOrNull()?.let { (oldestId, job) ->
            job.cancel()
            folderJobs.remove(oldestId)
            folderStates.remove(oldestId)
          }
        }

        val signature = folderSignature(filteredVideos, widthPx, heightPx) + snapshot.fingerprint
        val state =
          folderStates.compute(folderId) { _, existing ->
            if (existing == null || existing.signature != signature) {
              FolderState(signature = signature, nextIndex = 0)
            } else {
              existing
            }
          }!!

        var i = state.nextIndex
        while (i < filteredVideos.size && isActive && snapshot == preferences.thumbnailConfig()) {
          val video = filteredVideos[i]
          getThumbnail(video, widthPx, heightPx, snapshot)
          i++
          state.nextIndex = i
        }
      }
  }

  fun thumbnailKey(
    video: Video,
    width: Int,
    height: Int,
    snapshot: ThumbnailConfig = preferences.thumbnailConfig(),
  ): String {
    val base = videoBaseKey(video)
    return snapshot.cacheKey(base, "$width|$height")
  }

  private fun videoBaseKey(video: Video): String {
    if (isNetworkUrl(video.path)) {
      val base = video.path.ifBlank { video.uri.toString() }
      return "$base|network"
    }
    
    return "${video.path.ifBlank { video.uri.toString() }}|${video.size}|${video.dateModified}|${video.duration}"
  }

  private fun keyToFileName(key: String): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(key.toByteArray())
    val hex = digest.joinToString("") { b -> "%02x".format(b) }
    return "$hex.jpg"
  }

  private fun diskKey(video: Video, snapshot: ThumbnailConfig): String {
    val baseKey = videoBaseKey(video)
    return snapshot.cacheKey(baseKey, "disk|d$diskCacheDimension")
  }

  private fun loadFromDisk(video: Video, targetWidthPx: Int = 0, snapshot: ThumbnailConfig): Bitmap? {
    val diskFile = File(diskDir, keyToFileName(diskKey(video, snapshot)))
    if (!diskFile.exists()) return null
    return runCatching {
      val options =
        BitmapFactory.Options().apply {
          inPreferredConfig = Bitmap.Config.RGB_565
          if (targetWidthPx > 0) {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(diskFile.absolutePath, this)
            var sampleSize = 1
            var w = outWidth
            while (w / 2 >= targetWidthPx) {
              w /= 2
              sampleSize *= 2
            }
            inSampleSize = sampleSize
            inJustDecodeBounds = false
          }
        }
      BitmapFactory.decodeFile(diskFile.absolutePath, options)
    }.getOrNull()
  }

  private suspend fun writeToDisk(video: Video, bitmap: Bitmap, snapshot: ThumbnailConfig) {
    val coroutineContext = currentCoroutineContext()
    synchronized(diskLock) {
      coroutineContext.ensureActive()
      // Old requests can only write their own versioned file. Cancelled work never writes.
      val diskFile = File(diskDir, keyToFileName(diskKey(video, snapshot)))
      var temp: File? = null
      try {
        temp = File.createTempFile("thumbnail-", ".tmp", diskDir)
        FileOutputStream(temp).use { out ->
          if (!bitmap.compress(Bitmap.CompressFormat.JPEG, diskJpegQuality, out)) return
        }
        if (!temp.renameTo(diskFile)) return
        trimDiskCache()
      } catch (_: java.io.IOException) {
        // A full or unavailable cache must not prevent displaying the decoded frame.
      } finally {
        temp?.delete()
      }
    }
  }

  /** Bound old configurations and legacy files to 256 MiB, off the UI thread. */
  private fun trimDiskCache() = synchronized(diskLock) {
    val files = diskDir.listFiles()?.filter { it.extension == "jpg" } ?: return@synchronized
    var bytes = files.sumOf { it.length() }
    if (bytes <= 256L * 1024 * 1024) return@synchronized
    for (file in files.sortedBy { it.lastModified() }) {
      if (bytes <= 256L * 1024 * 1024) break
      val length = file.length()
      if (file.delete()) bytes -= length
    }
  }

  private suspend fun rotateIfNeeded(
    video: Video,
    bitmap: Bitmap
  ): Bitmap {
    val rotation = MediaInfoOps.getRotation(context, video.uri, video.displayName)
    if (rotation == 0) return bitmap
    val matrix = android.graphics.Matrix()
    matrix.postRotate(rotation.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
  }

  private suspend fun generateWithFastThumbnails(
    video: Video,
    dimension: Int,
    snapshot: ThumbnailConfig,
  ): Bitmap? {
    val coroutineContext = currentCoroutineContext()
    val bitmap = selectThumbnailFrame(
      positions = snapshot.positions(video.duration),
      avoidDarkFrames = snapshot.mode == ThumbnailMode.SMART && snapshot.avoidDarkFrames,
      checkCancellation = { coroutineContext.ensureActive() },
      decode = { position ->
        try {
          FastThumbnails.generateAsync(
            video.path.ifBlank { video.uri.toString() }, position, dimension, useHwDec = false,
          )?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          null
        }
      },
      analyze = DarkFrameDetector::analyze,
      discard = { it.recycle() },
    ) ?: return null
    return try {
      rotateIfNeeded(video, bitmap).also { if (it !== bitmap) bitmap.recycle() }
    } catch (cancelled: CancellationException) {
      bitmap.recycle()
      throw cancelled
    } catch (_: Exception) {
      // Rotation metadata is optional; a valid decoded frame still beats a system fallback.
      bitmap
    }
  }

  private suspend fun generateWithMediaStore(
    video: Video,
    dimension: Int,
  ): Bitmap? {
    // System fallback APIs choose their own frame; they cannot honor the configured seek time.
    // MediaStore only works for local files, not network URLs
    if (isNetworkUrl(video.path)) {
      android.util.Log.w("ThumbnailRepository", "Cannot use MediaStore for network URL: ${video.path}")
      return null
    }
    
    return withContext(Dispatchers.IO) {
      // Try MediaStore first
      val mediaStoreThumbnail = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
          // Use modern API for Android Q+
          // Build proper MediaStore content URI
          val contentUri = android.content.ContentUris.withAppendedId(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            video.id
          )
          android.util.Log.d("ThumbnailRepository", "Generating MediaStore thumbnail for ${video.displayName} using loadThumbnail")
          val thumbnail = context.contentResolver.loadThumbnail(
            contentUri,
            android.util.Size(dimension, dimension),
            null
          )
          android.util.Log.d("ThumbnailRepository", "MediaStore thumbnail generated successfully for ${video.displayName}")
          rotateIfNeeded(video, thumbnail)
        } else {
          // Use legacy API for older versions
          android.util.Log.d("ThumbnailRepository", "Generating MediaStore thumbnail for ${video.displayName} using getThumbnail")
          @Suppress("DEPRECATION")
          val thumbnail = android.provider.MediaStore.Video.Thumbnails.getThumbnail(
            context.contentResolver,
            video.id,
            android.provider.MediaStore.Video.Thumbnails.MINI_KIND,
            null
          )
          if (thumbnail != null) {
            // Scale to desired dimension
            val scaled = Bitmap.createScaledBitmap(
              thumbnail,
              dimension,
              (dimension * thumbnail.height) / thumbnail.width,
              true
            )
            if (scaled != thumbnail) {
              thumbnail.recycle()
            }
            android.util.Log.d("ThumbnailRepository", "MediaStore thumbnail generated successfully for ${video.displayName}")
            rotateIfNeeded(video, scaled)
          } else {
            android.util.Log.w("ThumbnailRepository", "MediaStore returned null thumbnail for ${video.displayName}")
            null
          }
        }
      }.onFailure { e ->
        if (e is CancellationException) throw e
        android.util.Log.w("ThumbnailRepository", "MediaStore thumbnail failed for ${video.displayName}, will try ThumbnailUtils: ${e.message}")
      }.getOrNull()
      
      // If MediaStore failed, try ThumbnailUtils as last resort
      if (mediaStoreThumbnail != null) {
        return@withContext mediaStoreThumbnail
      }
      
      // Fallback to ThumbnailUtils (extracts directly from file)
      runCatching {
        android.util.Log.d("ThumbnailRepository", "Generating thumbnail using ThumbnailUtils for ${video.displayName}")
        val file = java.io.File(video.path)
        if (!file.exists()) {
          android.util.Log.e("ThumbnailRepository", "File does not exist: ${video.path}")
          return@runCatching null
        }
        
        val thumbnail = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
          android.media.ThumbnailUtils.createVideoThumbnail(
            file,
            android.util.Size(dimension, dimension),
            null
          )
        } else {
          @Suppress("DEPRECATION")
          android.media.ThumbnailUtils.createVideoThumbnail(
            video.path,
            android.provider.MediaStore.Video.Thumbnails.MINI_KIND
          )?.let { thumb ->
            // Scale to desired dimension
            Bitmap.createScaledBitmap(
              thumb,
              dimension,
              (dimension * thumb.height) / thumb.width,
              true
            ).also {
              if (it != thumb) thumb.recycle()
            }
          }
        }
        
        if (thumbnail != null) {
          android.util.Log.d("ThumbnailRepository", "ThumbnailUtils thumbnail generated successfully for ${video.displayName}")
          rotateIfNeeded(video, thumbnail)
        } else {
          android.util.Log.e("ThumbnailRepository", "ThumbnailUtils returned null for ${video.displayName}")
          null
        }
      }.onFailure { e ->
        if (e is CancellationException) throw e
        android.util.Log.e("ThumbnailRepository", "ThumbnailUtils thumbnail generation failed for ${video.displayName}", e)
      }.getOrNull()
    }
  }

  private fun isNetworkUrl(path: String): Boolean {
    return path.startsWith("http://", ignoreCase = true) ||
      path.startsWith("https://", ignoreCase = true) ||
      path.startsWith("rtmp://", ignoreCase = true) ||
      path.startsWith("rtsp://", ignoreCase = true) ||
      path.startsWith("ftp://", ignoreCase = true) ||
      path.startsWith("sftp://", ignoreCase = true)
  }

  private fun folderSignature(
    videos: List<Video>,
    widthPx: Int,
    heightPx: Int,
  ): String {
    val md = MessageDigest.getInstance("MD5")
    md.update("$widthPx|$heightPx|".toByteArray())
    for (v in videos) {
      md.update(v.path.toByteArray())
      md.update("|".toByteArray())
      md.update(v.size.toString().toByteArray())
      md.update("|".toByteArray())
      md.update(v.dateModified.toString().toByteArray())
      md.update(";".toByteArray())
    }
    return md.digest().joinToString("") { b -> "%02x".format(b) }
  }
}
