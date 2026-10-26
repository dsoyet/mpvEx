package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.domain.thumbnail.ThumbnailConfig
import app.marlboroadvance.mpvex.domain.thumbnail.ThumbnailMode
import app.marlboroadvance.mpvex.preferences.preference.getEnum
import kotlinx.coroutines.flow.combine
import app.marlboroadvance.mpvex.BuildConfig
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore

class AdvancedPreferences(
  preferenceStore: PreferenceStore,
) {
  val thumbnailMode = preferenceStore.getEnum("thumbnail_mode", ThumbnailMode.PERCENTAGE)
  val thumbnailPositionSeconds = preferenceStore.getInt("thumbnail_position_seconds", 3)
  val thumbnailPositionPercent = preferenceStore.getInt("thumbnail_position_percent", 30)
  val thumbnailAvoidDarkFrames = preferenceStore.getBoolean("thumbnail_avoid_dark_frames", true)
  val thumbnailMaxAttempts = preferenceStore.getInt("thumbnail_max_attempts", 5)

  fun thumbnailConfig() = ThumbnailConfig(
    thumbnailMode.get(), thumbnailPositionSeconds.get(), thumbnailPositionPercent.get(),
    thumbnailAvoidDarkFrames.get(), thumbnailMaxAttempts.get(),
  ).normalized()

  val thumbnailConfigChanges = combine(
    thumbnailMode.changes(), thumbnailPositionSeconds.changes(), thumbnailPositionPercent.changes(),
    thumbnailAvoidDarkFrames.changes(), thumbnailMaxAttempts.changes(),
  ) { mode, seconds, percent, avoidDark, attempts ->
    ThumbnailConfig(mode, seconds, percent, avoidDark, attempts).normalized()
  }

  val mpvConfStorageUri = preferenceStore.getString("mpv_conf_storage_location_uri")
  val mpvConf = preferenceStore.getString("mpv.conf")
  val inputConf = preferenceStore.getString("input.conf")

  val verboseLogging = preferenceStore.getBoolean("verbose_logging", BuildConfig.BUILD_TYPE != "release")

  val enabledStatisticsPage = preferenceStore.getInt("enabled_stats_page", 0)

  val enableRecentlyPlayed = preferenceStore.getBoolean("enable_recently_played", true)

}
