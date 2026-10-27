package app.marlboroadvance.mpvex.domain.thumbnail

/** Eviction drops ownership without recycling images that a visible card may still render. */
internal class ThumbnailByteCache<T>(
  private val capacity: Long,
  private val maxEntries: Int = 512,
  private val sizeOf: (T) -> Long,
) {
  private val values = LinkedHashMap<String, Pair<T, Long>>(16, 0.75f, true)
  var sizeBytes: Long = 0
    private set

  @Synchronized fun get(key: String): T? = values[key]?.first

  @Synchronized fun put(key: String, value: T) {
    values.remove(key)?.let { sizeBytes -= it.second }
    val bytes = sizeOf(value).coerceAtLeast(1L)
    if (bytes > capacity) return
    values[key] = value to bytes
    sizeBytes += bytes
    val iterator = values.entries.iterator()
    while ((sizeBytes > capacity || values.size > maxEntries) && iterator.hasNext()) {
      sizeBytes -= iterator.next().value.second
      iterator.remove()
    }
  }

  @Synchronized fun clear() {
    values.clear()
    sizeBytes = 0
  }
}
