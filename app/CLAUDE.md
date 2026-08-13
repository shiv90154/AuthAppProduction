# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build debug APK (first build fetches Oboe 1.9.0 from GitHub via CMake FetchContent — needs internet)
./gradlew :app:assembleDebug

# Unit tests (currently only placeholder tests exist — ExampleUnitTest.kt)
./gradlew :app:test

# Instrumented tests (needs a connected device/emulator — currently only a placeholder)
./gradlew :app:connectedAndroidTest

# Lint
./gradlew :app:lint
```

Requires: Android Studio or standalone SDK, **NDK 27.0.12077973 exactly** (pinned in `build.gradle.kts` — a different NDK version can produce subtly different native behavior for the audio engine), CMake 3.22.1, JDK 21. `local.properties` with `sdk.dir=...` must exist (not checked in).

There is no separate lint config beyond the Android Gradle plugin default — `app/src/main/keepRules/rules.keep` (R8/ProGuard) is currently empty and release optimization is disabled in `build.gradle.kts`.

## Architecture

Single Activity (`MainActivity`), no ViewModel/Hilt/Navigation component. Everything is state-driven Jetpack Compose. `ui/OctapadScreen.kt` (~2100 lines) owns nearly all UI state directly as `remember { mutableStateOf }` / `SnapshotStateList`; a handful of singleton objects hold state that needs to outlive recomposition (`AudioRepository`, `KitRepository`, `PreferencesRepository`, `DrumEngine`, `PadDurationCache`, `MidiEventBus`, `CcMapRepository`, `MidiChannelState`, `NativeBridge`, and the `license/` package's `LicenseRepository`/`LicenseApi`/`DeviceId`). `MidiLearnRepository` (note-based pad mapping) was removed — see the MIDI section below.

**App entry gate** (`MainActivity.kt`): Splash screen → one-time "follow us" social-links screen (`SocialLinksScreen`, gated on `PreferencesRepository.hasSeenSocialLinks()`, shown once per install — placeholder YouTube/Instagram/WhatsApp URLs at the top of `SplashScreen.kt`, replace before shipping) → activation gate (blocks until `LicenseRepository.isUsable()`, which has a 3-day offline grace period) → `OctapadScreen`. `OctapadScreen` re-checks license status with the admin panel every 30 minutes while open.

**Right-panel layout** (`ui/RightPanel.kt` + `ui/EQPanel.kt` + `ui/TempoPanel.kt`): three groups. Main display (`RightPanel.kt`'s own column — EDIT/SAVE/LOAD quick buttons, Volume/Pitch, bank A/B/C, Patch List nav, kit name) stays in the main column; **FX** (`EQPanel.kt`, header text says "FX") holds EQ, Delay (on/off + time + level + "apply to" pad picker — merged in from the old standalone `DelayPanel`, which no longer exists), Choke, Reverse, Pan, Gain, Exclusive/Velocity, Recording, and a one-tap "IMPORT TO THIS PAD" button; **LOOP** (`TempoPanel.kt`, header text says "LOOP") holds BPM, the new SPEED multiplier, the global Loop/Metronome toggles, and per-pad PLAY MODE (ONE SHOT/LOOP/MULTIPLAY — MULTIPLAY is the old "MIX" mode, just relabeled). Pad grid spacing was tightened from 8dp to 3dp to match a denser reference layout.

**Pad hit path** (the hot path — see `OctapadScreen.onPadHit()`): fires **synchronously**, not inside `scope.launch {}`, all the way through the native JNI trigger call. This is deliberate — wrapping the trigger in a coroutine used to add a silent ~16ms delay per hit (Compose's dispatcher defers `launch` to the next frame even from the UI thread). Only the "wait for playback to finish / loop again / update the LCD" bookkeeping after the trigger runs inside a coroutine, since that part doesn't affect what's actually heard. **Do not move the trigger call back inside a coroutine launch.**

**Native audio engine** (`cpp/AudioEngine.h/.cpp`): Oboe stream opened Exclusive/MMAP when the device supports it (falls back to Shared automatically), device-native sample rate (deliberately not hardcoded — forcing a rate can make AAudio insert its own resampler), 1-burst buffer size for minimum latency, 24 pad slots (0–7/8–15/16–23 = Bank A/B/C), 64-voice polyphony pool.

The `Voice` struct has two atomics, `active` (slot-claim lock) and `ready` (safe-to-mix gate) — **this ordering is load-bearing, not a style choice**. `padIndex`/`position`/`volume`/`pitch`/`pan`/`gain`/`lengthFraction` must always be written *before* `ready` is set true, and the audio thread must gate on `ready`, not `active`. Getting this backwards reintroduces a real data race that previously caused audible stutter/double-triggering on fast multi-hits (the realtime audio thread could read a voice mid-write, using stale data from whatever note previously occupied that slot). `pan`/`gain` are new per-voice fields (equal-power pan law, multiplicative gain trim) applied in `onAudioReady`'s per-voice mix loop, following this exact same write-then-`ready` discipline.

**Multi-hit mixing / headroom**: `onAudioReady` sums all active voices with a headroom scale (`1/max(1, sqrt(activeVoiceCount))`) before clipping, and clips with `tanhf()` (a real soft-knee curve) instead of a hard `[-1,1]` clamp. Previously there was no headroom compensation and the "soft clip" was actually a hard clamp — stacking a handful of simultaneous voices pushed the sum past unity and produced audible harsh distortion on fast multi-hits. If you touch the mixing loop again, keep both parts: the headroom scale (so rapid multi-hits don't distort) and the `tanhf` soft-knee (so any residual overshoot rolls off smoothly).

**MIDI is CC-only**: note-based pad mapping (GM-style default note→pad map, MIDI Learn note flow, `MidiLearnRepository`, `MidiProcessor`'s old `padMapping`/`enableMidiLearn`/`assignMidiNote`) has been removed entirely. A physical controller's pad hit is mapped **only** through `CcMapRepository`'s `PAD_1`..`PAD_8` targets, the same MIDI Learn flow used for every other CC target (Volume, Pitch, EQ, Patch nav, etc.) — see `MidiLearnScreen.kt`. **There are no default CC-to-pad mappings**, unlike the old GM note defaults — a freshly connected controller's pads won't fire anything until each is explicitly MIDI-Learned once; this is a known, accepted tradeoff, not a bug. `MidiProcessor.cpp` still receives Note-On/Off (for logging) and Control Change (still fully functional) — only the note→pad triggering path was removed; `sendPadToKotlin`/`onPadHitFromNative`/the JNI plumbing for it are gone too. `MidiChannelState.selectedChannel` filtering (in `MidiReceiver.kt`) still applies before either message type is dispatched.

**Factory vs. custom audio**: every pad's sound is either a factory `res/raw/kitN_padM.wav` resource (looked up via `kits[currentKit].sounds[padIndex]`, an `Int` resource ID, `-1` = none) or a custom `AudioItem` from `AudioRepository` (imported/recorded/edited). **Any feature touching pad audio must handle both** — `PcmMixer` (Mix/Add-To-End) and `WaveformEditorScreen` (crop editor) previously only checked `AudioRepository` and silently no-op'd on factory-only pads, which is the common case for most kits. The fix pattern (see `PcmMixer.resolvePcm()`) is: try `AudioRepository.audioForPad()` first, fall back to decoding the factory resource ID. Never resolve a factory resource ID by guessing from a kit's list position (`"kit${index+1}_padN"`) — kits get reordered/deleted/duplicated, so list position and factory kit number diverge. Always read the real ID from `kits[currentKit].sounds`.

**MediaCodec/MediaExtractor/MediaMuxer usage** (`PcmDecoder`, `WaveformGenerator`, `PcmMixer`, `WaveformEditorScreen`, `AudioTrimmer`): all codec/extractor/muxer lifecycles must be wrapped in `try/finally`. Android allows only a small number of concurrent codec instances system-wide, shared across the whole app — a leaked instance from one bad file (which real users will eventually import) can break audio decoding everywhere else, not just the operation that leaked it. Decode loops reading from user-supplied files also need an iteration cap; a corrupt file that never signals end-of-stream will otherwise spin the loop forever on a background thread.

**Touch gesture handling** (`ui/pads/DrumPad.kt`): a single unified `pointerInput` block handles both single-finger tap (fires on down, not on lift) and 2-finger drag (swap/mix). Do not add a second independent gesture handler on the pad `Box` — there used to be three (a legacy `pointerInteropFilter` for 2-finger record, plus two uncoordinated Compose handlers), and the tap-detector fired on *any* finger touching down, including the second finger of a drag gesture, corrupting the drag. Recording is triggered from the EQ panel's record button, not a pad gesture.

**Licensing** (`license/` package): `DeviceId` (ANDROID_ID + UUID fallback) locks one activation code to one device server-side. `license_prefs` (the SharedPreferences file holding activation state) is deliberately excluded from Android's OS-level auto-backup in both `res/xml/backup_rules.xml` (API 23–30) and `res/xml/data_extraction_rules.xml` (API 31+) — restoring a backup onto a different physical device must not let it inherit another device's activation state. Keep both files' exclusion in sync if you rename that SharedPreferences file.

**Kit slots**: first launch now pre-populates all 200 kit slots, not just the 25 factory kits — slots 26–200 are generated as genuinely empty kits (`sounds` all `-1`, `factoryKitNumber = -1`) so the Patch List shows the full 200 from the start instead of kits only existing once a user explicitly creates one via Copy.

**Pad LED timing**: `pressedPads[index]` is now cleared the instant a finger lifts (`DrumPad.kt`'s `onRelease` callback, wired per-pad in `OctapadScreen.kt`), not when the sample finishes playing. The audio trigger itself was already synchronous (see above) — the old lingering LED was a pure visual issue, not audio latency. The post-playback coroutine still clears `pressedPads` too (needed for CC/MIDI-triggered hits, which have no touch-release event), so don't remove that fallback.

**One-tap per-pad import**: `ImportScreen.kt` takes optional `currentKit`/`targetPad`/`targetPadDefaultResId` params — when opened from the FX panel's "IMPORT TO THIS PAD" button (`OctapadScreen`'s `importTargetPad` state), a successful import is assigned straight to that pad via `AudioRepository.assignPadToKit()` + `DrumEngine.invalidatePad/loadPad`, skipping the old Import screen → Audios screen → pad-picker detour. Opening Import from SETTINGS still leaves the file unassigned in the library, same as before.
