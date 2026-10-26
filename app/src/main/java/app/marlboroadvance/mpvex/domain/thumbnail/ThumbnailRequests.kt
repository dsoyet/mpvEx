package app.marlboroadvance.mpvex.domain.thumbnail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/** Share work across callers, including callers requesting different display dimensions. */
internal class ThumbnailRequests<T>(private val scope: CoroutineScope) {
  private val operations = ConcurrentHashMap<String, Deferred<T>>()

  suspend fun await(key: String, generate: suspend () -> T): T {
    val operation = synchronized(operations) {
      operations[key] ?: scope.async(start = CoroutineStart.LAZY) { generate() }.also { deferred ->
        operations[key] = deferred
        deferred.invokeOnCompletion { operations.remove(key, deferred) }
      }
    }
    operation.start()
    return operation.await()
  }

  fun cancelAll() = synchronized(operations) {
    operations.values.forEach { it.cancel() }
    operations.clear()
  }

  fun cancelExcept(config: ThumbnailConfig) = synchronized(operations) {
    val suffix = "|${config.fingerprint}"
    operations.entries.filterNot { it.key.endsWith(suffix) }.forEach { (key, operation) ->
      operation.cancel()
      operations.remove(key, operation)
    }
  }
}
