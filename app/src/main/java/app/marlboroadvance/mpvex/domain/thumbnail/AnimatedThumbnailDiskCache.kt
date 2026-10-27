package app.marlboroadvance.mpvex.domain.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Private JPEG sequences, published as complete directories using a same-filesystem rename. */
internal class AnimatedThumbnailDiskCache(cacheDir: File) {
  private val root = File(cacheDir, "animated-thumbnails-v1")
  private val lock = Any()
  private val maxBytes = 256L * 1024 * 1024

  private fun directory(key: String): File {
    val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
      .joinToString("") { "%02x".format(it) }
    return File(root, hash)
  }

  fun read(key: String, checkCancellation: () -> Unit): List<Bitmap>? = synchronized(lock) {
    val dir = directory(key)
    val frames = mutableListOf<Bitmap>()
    var complete = false
    var cancelled = false
    try {
      if (!dir.isDirectory) return@synchronized null
      val count = File(dir, "count").readText().toIntOrNull() ?: return@synchronized null
      if (count !in 1..10) return@synchronized null
      repeat(count) { index ->
        checkCancellation()
        val file = File(dir, "$index.jpg")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth !in 1..256 || bounds.outHeight !in 1..256) return@synchronized null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        frames += BitmapFactory.decodeFile(file.path, options) ?: return@synchronized null
      }
      checkCancellation()
      dir.setLastModified(System.currentTimeMillis())
      complete = true
      frames.toList()
    } catch (error: CancellationException) {
      cancelled = true
      throw error
    } catch (_: IOException) {
      null
    } finally {
      if (!complete) {
        frames.forEach { it.recycle() }
        // Incomplete/corrupt data must be regenerated, rather than repeatedly decoded.
        if (!cancelled) dir.deleteRecursively()
      }
    }
  }

  fun write(key: String, frames: List<Bitmap>, checkCancellation: () -> Unit) = synchronized(lock) {
    if (frames.isEmpty()) return@synchronized
    root.mkdirs()
    val target = directory(key)
    val staging = File(root, "${UUID.randomUUID()}.tmp")
    try {
      checkCancellation()
      if (target.isDirectory) return@synchronized
      if (!staging.mkdirs()) return@synchronized
      frames.forEachIndexed { index, bitmap ->
        checkCancellation()
        File(staging, "$index.jpg").outputStream().use {
          if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)) return@synchronized
        }
      }
      File(staging, "count").writeText(frames.size.toString())
      checkCancellation()
      if (staging.renameTo(target)) trimLocked()
    } catch (_: IOException) {
      // The in-memory animation remains usable when the cache is unavailable or full.
    } finally {
      staging.deleteRecursively()
    }
  }

  fun trim() = synchronized(lock) { trimLocked() }

  private fun trimLocked() {
    val dirs = root.listFiles() ?: return
    dirs.filter { it.name.endsWith(".tmp") }.forEach { it.deleteRecursively() }
    val entries = dirs.filter { it.isDirectory && !it.name.endsWith(".tmp") }
      .map { it to it.walkTopDown().filter { file -> file.isFile }.sumOf { file -> file.length() } }
    var bytes = entries.sumOf { it.second }
    if (bytes <= maxBytes) return
    for ((dir, size) in entries.sortedBy { it.first.lastModified() }) {
      if (bytes <= maxBytes) break
      if (dir.deleteRecursively()) bytes -= size
    }
  }

  fun clear() = synchronized(lock) {
    root.listFiles()?.forEach { it.deleteRecursively() }
    Unit
  }
}
