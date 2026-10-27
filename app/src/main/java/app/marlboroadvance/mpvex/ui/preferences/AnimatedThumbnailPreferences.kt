package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.SliderPreference
import kotlin.math.roundToInt
import me.zhanghai.compose.preference.SwitchPreference

@Composable
internal fun AnimatedThumbnailPreferences(preferences: AdvancedPreferences) {
  val enabled by preferences.animatedThumbnailsEnabled.collectAsState()
  val count by preferences.animatedThumbnailFrameCount.collectAsState()
  val interval by preferences.animatedThumbnailInterval.collectAsState()
  val start by preferences.animatedThumbnailStart.collectAsState()
  val end by preferences.animatedThumbnailEnd.collectAsState()
  val skipDark by preferences.animatedThumbnailSkipDark.collectAsState()
  Column {
    PreferenceSectionHeader(title = stringResource(R.string.pref_animated_thumbnails))
    PreferenceCard {
      SwitchPreference(
        value = enabled, onValueChange = preferences.animatedThumbnailsEnabled::set,
        title = { Text(stringResource(R.string.pref_animated_enabled)) },
      )
      if (enabled) {
        PreferenceDivider()
        ListPreference(
          value = count.coerceIn(2, 10), values = (2..10).toList(),
          onValueChange = preferences.animatedThumbnailFrameCount::set,
          valueToText = { AnnotatedString(it.toString()) },
          title = { Text(stringResource(R.string.pref_animated_count)) },
          summary = { Text(count.coerceIn(2, 10).toString()) },
        )
        PreferenceDivider()
        SliderPreference(
          value = interval.coerceIn(500, 3000).toFloat(),
          onValueChange = { preferences.animatedThumbnailInterval.set(it.roundToInt()) },
          sliderValue = interval.coerceIn(500, 3000).toFloat(),
          onSliderValueChange = { preferences.animatedThumbnailInterval.set(it.roundToInt()) },
          valueRange = 500f..3000f,
          title = { Text(stringResource(R.string.pref_animated_interval)) },
          summary = { Text(stringResource(R.string.pref_animated_interval_value, interval.coerceIn(500, 3000))) },
        )
        PreferenceDivider()
        ListPreference(
          value = start.coerceIn(0, 40), values = (0..40).toList(),
          onValueChange = preferences.animatedThumbnailStart::set,
          valueToText = { AnnotatedString("$it%") },
          title = { Text(stringResource(R.string.pref_animated_start)) },
          summary = { Text("${start.coerceIn(0, 40)}%") },
        )
        PreferenceDivider()
        ListPreference(
          value = end.coerceIn(60, 100), values = (60..100).toList(),
          onValueChange = preferences.animatedThumbnailEnd::set,
          valueToText = { AnnotatedString("$it%") },
          title = { Text(stringResource(R.string.pref_animated_end)) },
          summary = { Text("${end.coerceIn(60, 100)}%") },
        )
        PreferenceDivider()
        SwitchPreference(
          value = skipDark, onValueChange = preferences.animatedThumbnailSkipDark::set,
          title = { Text(stringResource(R.string.pref_animated_skip_dark)) },
        )
      }
    }
  }
}
