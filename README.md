# Echo: Music Player

An open-source, extension-based music player for Android built with performance, extensibility, and seamless playback in mind.

> [!IMPORTANT]  
> **Testing Needed & Bug Reporting:**  
> This project has undergone extensive refactoring, rebase, and audio engine rewrites. There may still be undiscovered bugs or edge cases across different Android devices and OS versions.  
> **Your help with testing is greatly appreciated!** If you encounter any issues, crashes, or unexpected behavior, please [**open a GitHub Issue**](https://github.com/Mat1RX/echo/issues) with steps to reproduce or logcat details.

---

## 📢 Repository Notice & Rebase Information

This repository represents a clean rebase and evolution from [rschwertley/gladix](https://github.com/rschwertley/gladix) onto the original [brahmkshatriya/echo](https://github.com/brahmkshatriya/echo) codebase.

### Why the Embedded Module was Separated:
In previous iterations, an embedded streaming extension module (Deezer) was bundled directly within the application source tree. To strictly comply with content provider Terms of Service (TOS) guidelines and eliminate repository risk, the embedded module was completely removed and separated back into an external extension (`.eapk`).

Decoupling external content providers ensures that the core repository remains 100% legal, lightweight, and focused purely on perfecting the core player architecture, audio processing engine, hardware volume fades, Android Auto integration, and system stability.

---

## ✨ What's New Compared to Upstream Echo

### 🎵 Audio Engine & Hardware Volume Fades
- **Hardware Volume Fade:** Smooth sine-curve volume transitions when pausing, resuming, or skipping tracks in `ShufflePlayer` to eliminate audio pops and clicks.
- **Configurable Fade Durations:** Independent sliders in Settings for Skip fade, Pause fade, and Resume fade durations.
- **Android 12+ Audio Focus Compliance:** Immediate playback suspension (`pauseImmediately()`) on `AUDIOFOCUS_LOSS` and `AUDIOFOCUS_LOSS_TRANSIENT` to prevent Android 12+ OS audio enforcement from permanently muting the stream.
- **Instant Audio Processing Passthrough:** Zero-CPU native buffer passthrough (`memcpy`) in `AudioEffectsProcessor` when processing is disabled, allowing instant toggle changes without playback gaps.
- **Loudness Normalization (Experimental):** Real-time dual-stage LUT loudness normalization with cubic soft-limiting.
- **Hardware Audio Offload (Experimental):** Toggle in settings to delegate audio decoding to the device's DSP chip for battery savings, with dynamic `TrackSelectionParameters` updates.
- **Decoupled System Equalizer:** Independent access to system-level equalizer panels (Dolby Atmos, Wavelet, etc.) via system Intent (`ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL`), detached from internal DSP toggles.
- **Buffering Watchdog:** 8-second automatic watchdog for `STATE_BUFFERING` stalls to prevent infinite buffering hangs.

### 🚗 Android Auto
- **Full Media Tree Navigation:** Home, Search, and Library tabs load correctly per extension.
- **Google Assistant Voice Search:** Full support for voice commands ("Hey Google, play X on Echo").
- **Now Playing Controls & Queue:** Interactive queue view in Now Playing screen with explicit Shuffle and Repeat buttons.
- **Car Connection Stability:** Auto-pause on car disconnect, circuit breaker for region-locked/unavailable tracks, and battery optimization exemption prompt on first launch.

### 📺 Android TV & Pairing Subsystem
- **Dedicated Android TV Interface:** Specialized DPAD-aware TV layout (`PlayerTvFragment`), `TvAwareRecyclerView`, TV navigation menu, and TV banner support.
- **TV Pairing System:** Built-in TV pairing screen (`TvPairingFragment`) and companion `pair.html` web interface for easy code-based pairing from mobile devices or desktop browsers.

### 📜 Listening History & Caching
- **Local Listening History Subsystem:** Complete Room-based local listening history database (`HistoryDatabase`, `HistoryRepository`) with a dedicated history view on the home screen.
- **RAM-Only Caching Option:** Ability to store cached data in temporary RAM memory (`Cache in RAM only`) to protect flash drive longevity.
- **Background Preloading:** Configurable preloading duration (`PRELOAD_FUTURE_TRACKS_S`) for background track caching and gapless playback.

### 🎨 UI, UX & Visual Polish
- **Ken Burns Animation:** Fullscreen album art view with smooth pan/zoom motion (`KenBurnsImageView`).
- **Voice Search Microphones:** Voice search button integrated across search bars (`search_mic_menu`).
- **Dynamic Theming:** AMOLED black mode, custom theme color picker, and dynamic player colors based on track artwork.
- **Compact Context Menus:** Refined context menus with icon-first layouts, Shuffle Play option, and tappable "Playing from" subtitles navigating to album/playlist/artist.

---

## 🛠️ Building from Source

### Prerequisites:
- Android Studio Ladybug or newer
- JDK 17
- Android SDK 34+

### Steps:
1. Clone the repository:
   ```bash
   git clone https://github.com/brahmkshatriya/echo.git
   ```
2. Open the project in Android Studio.
3. Build and run:
   ```bash
   ./gradlew assembleDebug
   ```

---

## 🔌 Extension Installation

Echo uses modular extensions (`.eapk`) to connect to media sources.

1. Download or build an `.eapk` extension file.
2. Open the `.eapk` file on your Android device — Echo will handle the installation prompt automatically.
3. Manage installed extensions under **Settings → Extensions**.

---

## 📄 Credits & Disclaimer

- **Core Architecture:** Built upon [Echo](https://github.com/brahmkshatriya/echo) by [brahmkshatriya](https://github.com/brahmkshatriya).
- **Disclaimer:** Echo is a media player shell and hosts **zero** content. All media is provided by user-installed extensions and external services. The developers are not responsible for third-party extensions or compliance with external terms of service.
