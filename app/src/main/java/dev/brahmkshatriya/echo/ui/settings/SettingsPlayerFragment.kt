package dev.brahmkshatriya.echo.ui.settings


import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toResourceImageHolder
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.CACHE_SIZE
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.CLOSE_PLAYER
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.CACHE_IN_RAM_ONLY
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.AUDIO_OFFLOAD_ENABLED
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.PRELOAD_FUTURE_TRACKS_S
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.PRELOAD_TRACK_CACHE
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.PAUSE_FADE_DURATION
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.RESUME_FADE_DURATION
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.SKIP_FADE_DURATION
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.SKIP_SILENCE
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.STREAM_QUALITY
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.UNMETERED_STREAM_QUALITY
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.streamQualities
import dev.brahmkshatriya.echo.playback.listener.PlayerRadio.Companion.AUTO_START_RADIO
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel.Companion.KEEP_QUEUE
import dev.brahmkshatriya.echo.ui.settings.AudioEffectsFragment.Companion.AUDIO_FX
import dev.brahmkshatriya.echo.utils.ContextUtils.SETTINGS_NAME
import dev.brahmkshatriya.echo.utils.ui.prefs.MaterialListPreference
import dev.brahmkshatriya.echo.utils.ui.prefs.MaterialSliderPreference
import dev.brahmkshatriya.echo.utils.ui.prefs.TransitionPreference

class SettingsPlayerFragment : BaseSettingsFragment() {
    override val title get() = getString(R.string.player)
    override val icon get() = R.drawable.ic_play_circle.toResourceImageHolder()
    override val creator = { AudioPreference() }

    class AudioPreference : PreferenceFragmentCompat() {

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            configure()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = preferenceManager.context
            preferenceManager.sharedPreferencesName = SETTINGS_NAME
            preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
            val screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen

            PreferenceCategory(context).apply {
                title = getString(R.string.playback)
                key = "playback"
                isIconSpaceReserved = false
                layoutResource = R.layout.preference_category
                screen.addPreference(this)

                TransitionPreference(context).apply {
                    key = AUDIO_FX
                    title = getString(R.string.audio_fx)
                    summary = getString(R.string.audio_fx_summary)
                    layoutResource = R.layout.preference
                    isIconSpaceReserved = false
                    addPreference(this)
                }

                MaterialListPreference(context).apply {
                    key = STREAM_QUALITY
                    title = getString(R.string.stream_quality)
                    summary = getString(R.string.stream_quality_summary)
                    entries = context.resources.getStringArray(R.array.stream_qualities)
                    entryValues = streamQualities
                    layoutResource = R.layout.preference
                    isIconSpaceReserved = false
                    setDefaultValue(streamQualities[2])
                    addPreference(this)
                }

                MaterialListPreference(context).apply {
                    key = UNMETERED_STREAM_QUALITY
                    title = getString(R.string.unmetered_stream_quality)
                    summary = getString(R.string.unmetered_stream_quality_summary)
                    entries =
                        context.resources.getStringArray(R.array.stream_qualities) + getString(R.string.unmetered_stream_quality_auto)
                    entryValues = streamQualities + "off"
                    layoutResource = R.layout.preference
                    isIconSpaceReserved = false
                    setDefaultValue(streamQualities[1])
                    addPreference(this)
                }
            }

            PreferenceCategory(context).apply {
                title = getString(R.string.behavior)
                key = "behavior"
                isIconSpaceReserved = false
                layoutResource = R.layout.preference_category
                screen.addPreference(this)

                SwitchPreferenceCompat(context).apply {
                    key = KEEP_QUEUE
                    title = getString(R.string.keep_queue)
                    summary = getString(R.string.keep_queue_summary)
                    layoutResource = R.layout.preference_switch
                    isIconSpaceReserved = false
                    setDefaultValue(true)
                    addPreference(this)
                }

                SwitchPreferenceCompat(context).apply {
                    key = CLOSE_PLAYER
                    title = getString(R.string.stop_player)
                    summary = getString(R.string.stop_player_summary)
                    layoutResource = R.layout.preference_switch
                    isIconSpaceReserved = false
                    setDefaultValue(false)
                    addPreference(this)
                }

                val preloadFuture = MaterialSliderPreference(context, 0, 1200, steps = 10, allowOverride = true).apply {
                    key = PRELOAD_FUTURE_TRACKS_S
                    title = getString(R.string.preload_future_tracks)
                    summary = getString(R.string.preload_future_tracks_summary)
                    isIconSpaceReserved = false
                    setDefaultValue(10)
                }

                val cacheInRam = SwitchPreferenceCompat(context).apply {
                    key = CACHE_IN_RAM_ONLY
                    title = getString(R.string.cache_in_ram_only)
                    summary = getString(R.string.cache_in_ram_only_summary)
                    layoutResource = R.layout.preference_switch
                    isIconSpaceReserved = false
                    setDefaultValue(false)
                }

                SwitchPreferenceCompat(context).apply {
                    key = PRELOAD_TRACK_CACHE
                    title = getString(R.string.preload_track)
                    summary = getString(R.string.preload_track_summary)
                    layoutResource = R.layout.preference_switch
                    isIconSpaceReserved = false
                    setDefaultValue(true)
                    setOnPreferenceChangeListener { _, newValue ->
                        // Safely cast to Boolean to prevent ClassCastException and silent failures, 
                        // ensuring dependent UI elements correctly toggle their visibility.
                        val isEnabled = newValue as? Boolean == true
                        preloadFuture.isVisible = isEnabled
                        cacheInRam.isVisible = isEnabled
                        true
                    }
                    addPreference(this)
                }

                val isPreloadEnabled = preferenceManager.sharedPreferences?.getBoolean(PRELOAD_TRACK_CACHE, true) == true
                preloadFuture.isVisible = isPreloadEnabled
                addPreference(preloadFuture)

                cacheInRam.isVisible = isPreloadEnabled
                addPreference(cacheInRam)

                SwitchPreferenceCompat(context).apply {
                    key = SKIP_SILENCE
                    title = getString(R.string.skip_silence)
                    summary = getString(R.string.skip_silence_summary)
                    layoutResource = R.layout.preference_switch
                    isIconSpaceReserved = false
                    setDefaultValue(true)
                    addPreference(this)
                }


                val skipFadeDuration = MaterialSliderPreference(context, 0, 30).apply {
                    key = SKIP_FADE_DURATION
                    title = getString(R.string.skip_fade_duration)
                    summary = getString(R.string.skip_fade_duration_summary)
                    isIconSpaceReserved = false
                    setDefaultValue(10)
                }

                val pauseFadeDuration = MaterialSliderPreference(context, 0, 20).apply {
                    key = PAUSE_FADE_DURATION
                    title = getString(R.string.pause_fade)
                    summary = getString(R.string.pause_fade_summary)
                    isIconSpaceReserved = false
                    setDefaultValue(5)
                }

                val resumeFadeDuration = MaterialSliderPreference(context, 0, 20).apply {
                    key = RESUME_FADE_DURATION
                    title = getString(R.string.resume_fade)
                    summary = getString(R.string.resume_fade_summary)
                    isIconSpaceReserved = false
                    setDefaultValue(5)
                }

                addPreference(skipFadeDuration)
                addPreference(pauseFadeDuration)
                addPreference(resumeFadeDuration)

                SwitchPreferenceCompat(context).apply {
                    key = AUDIO_OFFLOAD_ENABLED
                    title = getString(R.string.hardware_audio_offload)
                    summary = getString(R.string.hardware_audio_offload_summary)
                    layoutResource = R.layout.preference_switch
                    isIconSpaceReserved = false
                    setDefaultValue(false)
                    addPreference(this)
                }

                SwitchPreferenceCompat(context).apply {
                    key = AUTO_START_RADIO
                    title = getString(R.string.auto_start_radio)
                    summary = getString(R.string.auto_start_radio_summary)
                    layoutResource = R.layout.preference_switch
                    isIconSpaceReserved = false
                    setDefaultValue(true)
                    addPreference(this)
                }

                MaterialSliderPreference(context, 200, 1000, allowOverride = true).apply {
                    key = CACHE_SIZE
                    title = getString(R.string.cache_size)
                    summary = getString(R.string.cache_size_summary)
                    isIconSpaceReserved = false
                    setDefaultValue(250)
                    addPreference(this)
                }
            }
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            val view = listView.findViewById<View>(preference.key.hashCode())
            return when (preference.key) {
                AUDIO_FX -> {
                    requireActivity().openFragment<AudioEffectsFragment>(view)
                    true
                }

                else -> false
            }
        }
    }
}
