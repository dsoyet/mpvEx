package app.marlboroadvance.mpvex.domain.thumbnail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Shared visible-only work. The last departing card cancels queued and unfinished generation. */
internal class VisibleThumbnailRequests<T>(private val scope: CoroutineScope) {
  private class Entry<T>(val task: Deferred<T>, var readers: Int = 0)
  private val entries = mutableMapOf<String, Entry<T>>()

  suspend fun await(key: String, generate: suspend () -> T): T {
    val entry = synchronized(entries) {
      entries.getOrPut(key) {
        Entry(scope.async(start = CoroutineStart.LAZY) { generate() })
      }.also { it.readers++ }
    }
    try {
      entry.task.start()
      return entry.task.await()
    } finally {
      synchronized(entries) {
        entry.readers--
        if (entry.readers == 0) {
          if (entries[key] === entry) entries.remove(key)
          if (!entry.task.isCompleted) entry.task.cancel()
        }
      }
    }
  }

  fun cancelAll() = synchronized(entries) {
    val tasks = entries.values.map { it.task }
    entries.clear()
    tasks.forEach { it.cancel() }
  }

  fun cancelUnless(keep: (String) -> Boolean) = synchronized(entries) {
    entries.keys.filterNot(keep).forEach { key -> entries.remove(key)?.task?.cancel() }
  }
}
