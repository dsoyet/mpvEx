package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.domain.thumbnail.AnimatedThumbnailConfig
import app.marlboroadvance.mpvex.domain.thumbnail.ThumbnailConfig
import app.marlboroadvance.mpvex.domain.thumbnail.ThumbnailMode
import app.marlboroadvance.mpvex.preferences.preference.getEnum
import kotlinx.coroutines.flow.combine
import app.marlboroadvance.mpvex.BuildConfig
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore

class AdvancedPreferences(
  preferenceStore: PreferenceStore,
) {
  val animatedThumbnailsEnabled = preferenceStore.getBoolean("animated_thumbnails_enabled", true)
  val animatedThumbnailFrameCount = preferenceStore.getInt("animated_thumbnail_frame_count", 5)
  val animatedThumbnailInterval = preferenceStore.getInt("animated_thumbnail_interval_ms", 1500)
  val animatedThumbnailStart = preferenceStore.getInt("animated_thumbnail_start_percent", 10)
  val animatedThumbnailEnd = preferenceStore.getInt("animated_thumbnail_end_percent", 90)
  val animatedThumbnailSkipDark = preferenceStore.getBoolean("animated_thumbnail_skip_dark", true)

  fun animatedThumbnailConfig() = AnimatedThumbnailConfig(
    animatedThumbnailsEnabled.get(), animatedThumbnailFrameCount.get(), animatedThumbnailInterval.get(),
    animatedThumbnailStart.get(), animatedThumbnailEnd.get(), animatedThumbnailSkipDark.get(),
  ).normalized()

  private val animatedTimingChanges = combine(
    animatedThumbnailsEnabled.changes(), animatedThumbnailFrameCount.changes(), animatedThumbnailInterval.changes(),
  ) { enabled, count, interval -> Triple(enabled, count, interval) }
  private val animatedRangeChanges = combine(
    animatedThumbnailStart.changes(), animatedThumbnailEnd.changes(), animatedThumbnailSkipDark.changes(),
  ) { start, end, skip -> Triple(start, end, skip) }
  val animatedThumbnailConfigChanges = combine(animatedTimingChanges, animatedRangeChanges) { timing, range ->
    AnimatedThumbnailConfig(timing.first, timing.second, timing.third, range.first, range.second, range.third).normalized()
  }

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
