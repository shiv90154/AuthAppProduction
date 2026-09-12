# Octapad — Full Project Documentation

Two things live in this repository:

1. **`app/`** — the Android drum-pad sampler app (Kotlin/Jetpack Compose UI + C++/Oboe native audio engine). Branded "ARUN SPD 30" inside the UI.
2. **`admin-panel/`** — a Next.js + MongoDB admin dashboard for managing activation codes, device locks, and signups for the app.

This document covers both: what each piece does, how everything is wired together, how to build/run each one, and what is explicitly **not** done yet.

---

## 1. Android App (`app/`)

### 1.1 What it is

An 8-pad drum sampler, similar in spirit to a Roland SPD or apps like Koala Sampler / G-Stomper. Forced landscape orientation. Every pad fires on finger-DOWN (not release) through a native Oboe audio engine tuned for the lowest latency Android's audio stack allows. First launch requires an activation code (see §1.8) tied to that one device.

### 1.2 Tech stack

| Layer | Technology |
|---|---|
| UI | Kotlin + Jetpack Compose + Material3 |
| Audio engine | C++17 + Google Oboe (via CMake `FetchContent`), Exclusive/MMAP mode |
| Audio decoding | Android `MediaCodec` / `MediaExtractor` |
| MIDI | `android.media.midi` (USB + Bluetooth MIDI, both go through the same Android MIDI API) |
| Mic recording | `MediaRecorder` |
| Licensing | Plain `HttpURLConnection` calling the admin panel's REST endpoints — no networking library dependency |
| Persistence | `SharedPreferences` + hand-rolled JSON (no Room/DB) |
| Build | Gradle Kotlin DSL, AGP 9.2.1, Kotlin 2.2.10, NDK 27, CMake 3.22.1, min SDK 24 / target SDK 36 |
| DI / Navigation / ViewModel | None — everything is state-driven Compose, single Activity |

### 1.3 Feature list (what's actually implemented right now)

**Pads**
- 8 touch pads, multi-touch, LED glow on press (top or bottom depending on row)
- Velocity sensitivity **from MIDI** (hit strength → volume). Touch pads always fire at full velocity — Android touchscreens don't reliably report pressure, so this is a hardware limitation, not a missing feature.
- Per-pad Reverse toggle
- Per-pad play mode: ONE SHOT / LOOP (immediate back-to-back retrigger, scaled by the SPEED multiplier, independent of BPM) / MULTIPLAY (layers repeated hits instead of cutting off — formerly labeled "MIX")
- **Choke**: None/1/2/3/4 levels (trimmed down from an earlier 6-level design) — a pad belongs to at most **one** level at a time (dropdown-style single choice, not a multi-select set); one "active" level at a time silences every other pad sharing it. `ChokePanel.kt` (its own top-level button next to CROP/DELAY) shows level assignment as 4 collapsible per-level folders — collapsed, a folder just lists which pads are in it; tap to expand and change.
- 2-finger drag to Swap / Mix (merges two pads' audio into one) / Add-to-End (concatenates two pads) — all three work on **factory kit sounds**, not just custom-imported audio. The drag/drop preview itself doesn't appear until the 2 fingers have been held down continuously for 2s; releasing before that cancels silently and neither the preview nor the menu ever appears (a quick 2-finger tap/brush used to open the menu instantly by accident, and an earlier gated version still showed the drag preview immediately even though the menu itself was delayed).
- Waveform crop editor (`WaveformEditorScreen.kt`) — a single pointer-count-aware gesture recognizer: one finger drags out the kept (crop) or cut (delete) region, two-plus fingers pinch-zoom/pan with inertia on release; the two never overlap or fight over the same touch. Also works on factory kit sounds, not just custom audio. **Nothing is written to disk until an explicit APPLY button is tapped** — dragging only updates the on-screen selection (this used to autosave the instant a drag gesture ended, so one accidental touch could permanently overwrite the original sample). A PREVIEW button plays the current unsaved selection straight from in-memory PCM (`AudioTrack`, no file write) so it can be heard before committing.

**Audio**
- WAV/MP3/OGG/M4A/FLAC import, or audio extracted from a video file
- Per-pad Volume (0–200%), Pitch, 3-band EQ (Low/Mid/High), **Delay** (per-pad on/off + time + level, live-adjustable — see DELAY panel below), **Length** (trims how much of the sample plays, independent of pitch), Pan (-1..1, equal-power law), Gain (0–200% trim)
- Internal mic recording straight onto a pad, with proper failure handling if the mic is busy with another app (shows a toast instead of silently doing nothing or crashing)
- **DELAY panel** (`DelayPanel.kt`, its own top-level button next to CROP/CHOKE) — a global MASTER on/off kill switch layered on top of each pad's own per-kit DELAY on/off flag: MASTER off mutes delay everywhere instantly without touching any pad's individual setting, MASTER back on restores exactly whichever pads had theirs on. Also holds DLY TIME, DLY LEVEL, and the "apply to" pad picker (moved out of the FX panel into its own dedicated panel).
- LOOP panel: BPM + SPEED multiplier (now actually scales true per-pad LOOP-mode retriggering, not just the BPM-gated global Loop toggle), global LOOP toggle, and a CHOKE quick-toggle (shortcut to the same Exclusive Mode state the dedicated CHOKE panel uses). No metronome click or beat counter — both were removed entirely.

**Banks**
- **A / B banks** — each is its own kit slot loaded in parallel (native audio slots 0–7 = A, 8–15 = B). Bank C was removed entirely (it used to push the patch-list nav row off the bottom of the non-scrolling control panel whenever active); the native engine still reserves 24 pad slots, 16–23 are simply unused now. A, B, or both (A+B) can be active at once; every active bank's sound layers together on a pad hit.

**Kit system**
- Up to 200 kits, 25 factory kits pre-loaded (200 WAV files bundled in `res/raw`)
- Save / Load / Rename (including **direct inline rename right from the Patch List** — no detour through Settings) / **Copy** (duplicates all settings + custom audio) / Delete
- Patch List screen, kit name shown on the LCD readout, both with proper text truncation so a long name can't overflow or push nav buttons off-panel
- "Load Kit" screen — build a new kit directly from 8 files picked from the file manager

**MIDI**
- USB + Bluetooth MIDI input (single code path — Android's MIDI API treats both the same once paired at the OS level)
- MIDI Learn: map any physical pad/knob to Volume, Pitch, EQ Low/Mid/High, Patch Next/Prev, Edit (opens the crop editor), Save, Delay Toggle (flips the selected pad's own delay on/off), Bank A / Bank B / Bank A+B (jump `bankMode` directly — a footswitch-style bank switch)
- **Pad mapping is dual: note-based and CC-based, both live** — despite an earlier changelog entry below claiming note-based mapping was removed, a 2026-08-14 audit confirmed `MidiLearnRepository` (note→pad), native `MidiProcessor`'s `padToNote` map, and the whole `enableMidiLearn`/`assignMidiNote`/`onPadHit` JNI chain are all present, wired, and reachable from `MidiLearnScreen.kt`'s "PAD NOTES" section. `CcMapRepository`'s `PAD_1`–`PAD_8` CC targets are a separate, independent pad-triggering path through the same MIDI Learn flow as everything else. Either a learned note or a learned CC fires a pad. There are no built-in default CC mappings for pads, so a CC mapping must be MIDI-Learned once before a controller's pads will trigger anything over CC (note-based triggering has no such requirement once a note is learned).
- **MIDI Channel Select** (1–16 or ALL) — filters incoming MIDI to one channel when your controller shares a MIDI bus
- Hardware-keyboard fallback: `Q W E R` → pads 1–4, `A S D F` → pads 5–8 (suspended automatically while a text field has focus, e.g. the rename dialog)
- Enabled per activation code via the `midiPurchased` flag (see §1.8) — shows a plain informational "MIDI not enabled for this code" screen otherwise (no pricing / purchase / external-payment steering, so it stays clear of Play's out-of-app-purchase policy)

**Backup**
- Backup/Restore screen zips every kit, every preference, the MIDI CC map, MIDI note mappings, and every reachable custom audio file into one `.zip` (via Storage Access Framework); Restore reverses it
- Activation/license state is deliberately **excluded** from both this backup and Android's own OS-level auto-backup (see §1.8)

**Startup**
- Branded splash screen (app logo, "ARUN SPD 30" wordmark) shown for about a second on every launch before handing off to either the activation screen or the pads

**Persistence**
- Everything (kit list, per-pad settings, choke groups, BPM, bank state, MIDI mappings, MIDI channel, last-open kit) survives an app restart — reopening picks up exactly where you left off

### 1.4 Architecture

No ViewModel, no Hilt, no Navigation component. `OctapadScreen.kt` (~2500+ lines) holds essentially all UI state as `remember { mutableStateOf }` / `SnapshotStateList`; the right-side control panel is split across `RightPanel.kt` (main column + panel-open/close orchestration via a shared `closeAllPanels()`), `EQPanel.kt` (FX: EQ/Reverse/Pan/Gain/Velocity/Recording), `DelayPanel.kt` (MASTER switch + per-pad delay), `ChokePanel.kt` (None/1-4 levels, per-level folders), and `TempoPanel.kt` (LOOP: BPM/SPEED/PLAY MODE). A handful of singleton objects hold state that needs to outlive recomposition:

| Singleton | Role |
|---|---|
| `AudioRepository` | List of imported/recorded audio items + which pad/kit each is assigned to |
| `KitRepository` | Persists the kit list (name, factory source, per-pad settings) |
| `PreferencesRepository` | Persists global app state (BPM, bank mode, selected pad, MIDI channel, etc.) |
| `DrumEngine` | Owns the native Oboe engine lifecycle, dedupes redundant PCM reloads |
| `PadDurationCache` | Caches the real decoded duration of factory samples (see §1.6) |
| `MidiEventBus` | Lambda-based event bus connecting native MIDI callbacks to Compose state |
| `CcMapRepository` | MIDI CC → named-target mappings (Volume, Pitch, EQ, Patch nav, Edit, Save, Delay Toggle, Bank A/B/A+B, Pad 1–8) |
| `MidiLearnRepository` | MIDI note → pad mappings |
| `MidiChannelState` | Which MIDI channel is currently being listened on |
| `NativeBridge` | JNI bridge object |
| `LicenseRepository` / `LicenseApi` / `DeviceId` | Activation/device-lock state, HTTP client, device identifier (see §1.8) |

**Pad hit → sound, step by step:**
```
finger DOWN on Pad N
  → DrumPad.pointerInput (single unified gesture handler — see §1.6) → onPress()
  → OctapadScreen.onPadHit(index)   [runs SYNCHRONOUSLY, no coroutine dispatch — see §1.6]
    → resolves which native slot(s) to trigger based on active bank(s) (A/B)
    → syncDelayForHit() pushes this pad's delay config to native synchronously (once, only for
      a genuinely new hit — never inside the loop-retrigger path, see §4's 2026-08-18 entry)
    → DrumEngine.trigger(slot, volume, pitch, stopExisting, lengthFraction, pan, gain)
      → NativeBridge.triggerPad(...)  [JNI]
        → AudioEngine::triggerPad()  [C++, on the calling thread]
          → claims a free Voice slot (or steals the oldest one if the 64-voice pool is full),
            writes padIndex/position/volume/pitch/lengthFraction/pan/gain, THEN publishes it
            "ready" (see §1.6 for why this order matters)
    → Oboe's realtime callback (AudioEngine::onAudioReady) mixes every ready voice every buffer,
      ramping each releasing voice's releaseGain out over ~5ms for a click-free retrigger cutoff
```

**MIDI path (Note-On or CC, depending on your hardware):**
```
Physical controller sends MIDI note-on
  → MidiReceiverHandler.onSend() → [channel filter] → NativeBridge.sendMidiMessage()
    → MidiProcessor::processMessage() [C++, single source of truth for note→pad mapping]
      → sendPadToKotlin() → NativeBridge.onPadHitFromNative() → MidiEventBus → same onPadHit() path as touch

Physical controller sends Control Change instead (some pad controllers do this)
  → MidiReceiverHandler.onSend() → NativeBridge.sendControlChange()
    → MidiProcessor::controlChange() → OctapadScreen's onControlChange handler
      → checks CcMapRepository's PAD_1..PAD_8 targets → onPadHit() if matched
```

### 1.5 Native audio engine (`app/src/main/cpp/`)

- `AudioEngine.h/.cpp` — Oboe stream (Exclusive/MMAP performance mode when the device supports it, device-native sample rate — not a hardcoded value, see §1.6 — stereo float), 24 pad buffer slots (16-23 unused since Bank C's removal), 64-voice polyphony pool with a ~5ms release fade-out on retrigger/stop and a ~3ms attack fade-in that only arms when a voice is claimed while another is already sounding (see the two-attempt note in §4's 2026-08-18 entry — an unconditional version of this regressed into crackle on every tap and was replaced with this narrower, conditional one), linear-interpolation pitch shifting, per-pad delay taps (a single global effect, not per-voice — see the delay-clobber note in §4's 2026-08-18 entry if you touch delay sync timing), a simple 3-band IIR EQ + master level applied on the final mix, polyphony-aware headroom scaling + `tanhf()` soft clipping.
- `MidiProcessor.h/.cpp` — GM-style default note→pad map, MIDI Learn mode, CC routing.
- `native-lib.cpp` — the only JNI entry point (an old duplicate `myapplication.cpp` that used to conflict with it has been removed).

### 1.6 Real bugs found and fixed (worth knowing about if you touch this code again)

**Native voice-publish race condition.** `Voice::padIndex` / `Voice::position` used to be written *after* the atomic `active` flag was flipped true. The realtime audio thread only synchronized on `active`, so there was a race window where it could read a voice using stale data left over from whatever note previously occupied that slot — this caused the reported "kit kit" stutter on fast multi-hits and pads occasionally playing two overlapping tones. Fixed with a second atomic `ready` flag set *after* every field is written; the audio thread gates on `ready`, not `active`, everywhere.

**Touch-to-sound latency.** The actual `DrumEngine.trigger()` call used to be wrapped in `scope.launch { }` inside `onPadHit()` — Compose's coroutine dispatcher defers that to the next Choreographer frame even when already on the UI thread, a silent ~16ms tax on every hit. Moved the trigger call (and everything it depends on) to run synchronously in the same call stack as the touch event; only the "wait for playback to finish / loop again / update the LCD" bookkeeping stays in a coroutine, since that doesn't affect what you hear.

**Audio stream configuration.** Requests `SharingMode::Exclusive` (the MMAP path, bypasses Android's mixer — Oboe silently falls back to Shared if unsupported), shrinks the buffer to exactly one hardware burst, and — as of this pass — no longer forces `setSampleRate(48000)`, since that could make AAudio insert its own resampler on any device whose native rate differs, for zero benefit (every voice already reads back whatever rate the stream actually opened at).

**DLY TIME knob didn't do anything.** The `LaunchedEffect` syncing delay timing to the native engine wasn't keyed on the delay-time value itself — dragging the knob updated the UI but the actual echo timing sent to native stayed stuck at whatever it was when you last switched pads. Fixed by keying the effect on the live value.

**Per-pad LOOP mode was tempo-gated like the global Loop toggle.** A short sample at a slow BPM played once then sat in silence for the rest of the beat before repeating. Per-pad LOOP is now a true immediate back-to-back loop; the *global* Loop toggle stays tempo-synced as documented in its own UI ("Pad loops at BPM rate").

**Factory samples used a hardcoded 500ms duration guess.** Any factory sample actually longer than that got cut off on every LOOP retrigger and had a wrong LCD progress bar. `PadDurationCache` now stores the real decoded duration the first time `DrumEngine.loadPad` loads a factory sample (reusing the decode that already has to happen — no extra I/O), and `onPadHit` uses that instead of the guess.

**Mix / Add-To-End / waveform crop editor only worked on custom-imported audio.** `PcmMixer` and `WaveformEditorScreen` both looked *only* at `AudioRepository` (custom audio) and silently did nothing for any pad still playing its factory kit sample — which is the default/common case for almost every kit. Both now fall back to decoding the kit's factory raw resource when no custom audio is assigned.

**Pad drag-to-swap used a fixed 250px threshold.** On any device whose pad size (in real pixels) didn't happen to match whatever screen this was tuned on, the drag could land in the gap between two pads and match nothing at all — the swap/mix menu just wouldn't appear. Replaced with nearest-neighbor detection: always resolves to whichever pad is closest, so it can't silently fail to match.

**Three separate, uncoordinated touch handlers on every pad.** A legacy 2-finger "record" handler (dead code — its callbacks only logged; real recording is the EQ panel's record button) plus two independent Compose gesture handlers that didn't know about each other. The tap-detector fired on *any* finger touching down, including the second finger of a 2-finger swap/drag gesture — so every drag attempt also spuriously re-triggered that pad's sound. Unified into one coordinated handler in `DrumPad.kt`.

**MediaCodec/MediaExtractor/MediaMuxer resource leaks on error paths (5 files).** `PcmDecoder`, `WaveformGenerator`, `PcmMixer`, `WaveformEditorScreen`, `AudioTrimmer` all released their codec/muxer/extractor only at the end of the happy path — any exception partway through (malformed file, truncated import, full disk) skipped cleanup. Android allows only a small number of concurrent codec instances system-wide, shared across the whole app, so a single bad file could eventually break audio loading everywhere. All five now use `try/finally`. The two decode loops that read from user-supplied files also got a runaway-loop safety cap, since a corrupt file that never signals end-of-stream used to spin forever on a background thread.

**`MidiManagerHelper` crash on devices without MIDI support.** `getSystemService(MidiManager::class.java)` can return `null`; it was dereferenced unconditionally in code that runs unconditionally on every app launch. Now null-checked.

**`PadRecorder.startRecording()` could crash the whole app.** `prepare()`/`start()` throw for a very reachable real-world reason — another app already holding the microphone — and nothing caught it. Now returns `File?` (null on failure) instead of throwing; the call site only shows the "recording…" UI when recording actually started, with an error toast otherwise.

**Wrong factory sound could load after unassigning a custom sound.** `AudioListScreen`'s pad picker guessed the factory resource name from the kit's *list position* (`"kit${currentKit+1}_padN"`) instead of the kit's real factory source — those only match by coincidence, and break the moment any kit is added, deleted, reordered, or is user-created. Fixed by passing the kit's real `sounds` list in from `OctapadScreen`.

**Text overflow / layout bugs.** Several `Text` composables showing user-editable strings (kit names) had no `maxLines`/`TextOverflow.Ellipsis`. One of these was a real layout bug, not just cosmetic: the Bank B/C kit-name selector row had no width constraint on the name, so a long name could push the `>` next-kit button off the edge of the panel entirely, making it un-tappable.

**Responsive layout.** The right-side control column and its sub-panels (EQ/Tempo/Delay/Settings) were fixed dp widths tuned for one reference screen. Now computed as ~20% of actual screen width (via `BoxWithConstraints`), clamped to a sane range, so small phones don't lose most of their pad area and tablets don't get a stranded-looking sliver.

**Pad VOL/PITCH controls only worked on Bank A.** The on-screen VOL/PITCH sliders (`RightPanel.kt`, wired in `OctapadScreen.kt`) and the matching MIDI CC VOLUME/PITCH handlers always read/wrote `kits[currentKit]` (Bank A's kit) and pushed live updates to native slot `selectedPad` (0-7, Bank A's native range), regardless of which bank the A/B/C selector actually had active. Moving the slider while Bank B or C was selected silently edited Bank A's stored value instead — it looked like "nothing happens" on B/C because the value that actually sounds for those banks (`currentKitB`/`currentKitC`, native slots 8-15/16-23) was never touched. Fixed by computing `activeBankKit` (picks `currentKit`/`currentKitB`/`currentKitC` from `bankMode`, same A>B>C precedence `onPadHit()` already uses for playback) and a shared `nativeSlotsFor()` helper, and routing both the slider wiring and the CC handlers through them.

**Auto-backup could leak the device lock across phones.** `allowBackup="true"` with empty backup rules meant Android's OS-level cloud backup (and Android 12+'s `data_extraction_rules.xml` specifically) backed up *everything*, including `license_prefs`. Restoring that backup onto a different physical device would let it start up believing it was already activated (self-correcting within ~30 minutes once online, via the periodic status re-check, but still an unnecessary gap). `license_prefs` is now explicitly excluded in both `backup_rules.xml` (Android 6–11) and `data_extraction_rules.xml` (Android 12+) — the project's `minSdk` is 24, so both mechanisms are actually reachable depending on the device.

### 1.7 Known limitations (honest, not fixed)

- **700 bundled tones** — currently ships 200 (25 kits × 8 pads). Can't fabricate 500 quality drum samples; send sample packs and they can be wired in as `res/raw` resources.
- **Hindi font for kit names** — needs an actual `.ttf` (e.g. Noto Sans Devanagari) dropped into `res/font/`; not included yet.
- **In-app MIDI purchase flow** — the paywall gate exists (`MidiPaywallScreen`, checks `midiPurchased`), but there's no in-app payment collection. Per the original spec, MIDI is sold separately and unlocked by flipping `midiPurchased` in the admin dashboard after payment is collected outside the app (UPI, etc.) — the screen says this plainly instead of pretending a purchase button exists. Real Google Play Billing integration would be a separate piece of work needing your Play Console account.
- **API 24–25 devices** (Android 7.0–7.1) fall back to the native engine's higher-latency OpenSL ES backend automatically, since AAudio didn't exist yet — this is a real hardware/OS capability difference, not something app code can change.
- **Latency is device-dependent below this app's control** — the app already does everything on its side (synchronous trigger, no coroutine dispatch delay, Exclusive/MMAP mode, 1-burst buffer, no forced sample rate). Whatever latency floor remains on a given phone is that device's own audio-stack ceiling; see the logcat line in §1.9 to tell whether a specific device even granted Exclusive/MMAP mode.
- **No release-build stripping**: `optimization { enable = false }` in `app/build.gradle.kts` — R8/ProGuard is disabled, so a release APK isn't shrunk/obfuscated and debug logging isn't stripped either. Intentional for now; revisit before a wide public release if APK size or reverse-engineering resistance start to matter.
- **Choke levels 5/6 from pre-redesign kit backups are orphaned, not migrated.** The 2026-08-18 choke redesign trimmed levels to None/1-4 in the UI; a kit backup created *before* that change, with a pad assigned to level 5 or 6, still imports fine (storage format didn't change) but that assignment becomes permanently invisible and un-editable — harmless for actual choke behavior (the ACTIVE LEVEL selector can never reach 5/6 anymore, so it can never fire), but the value just sits in the pad's `chokeGroups` list forever with no UI path to see or clear it.
- The Android side **has been compiled and built successfully** as of 2026-08-18 — both `:app:compileDebugKotlin`/`:app:assembleDebug` (repeatedly, throughout the third pass) and a fully signed `:app:assembleRelease` (against the real release keystore) — superseding the earlier "not compiled, verified by manual tracing only" caveat from the first two passes. Still worth an actual on-device run before shipping (a compiler pass doesn't catch runtime-only issues like the choke data-loss bug the 2026-08-18 audit found), and see §1.9 for what to specifically stress-test.

### 1.8 Licensing / Activation (client-side)

Full loop, connected to the admin panel described in §2.

- **`license/DeviceId.kt`** — stable per-device ID: `Settings.Secure.ANDROID_ID`, with a persisted random UUID fallback for the rare device where that's unavailable. Used to lock one activation code to exactly one phone.
- **`license/LicenseApi.kt`** — plain `HttpURLConnection` client (no networking library dependency) calling the admin panel's `/api/app/signup`, `/api/app/redeem`, `/api/app/status`.
- **`license/LicenseRepository.kt`** — local cache with a **3-day offline grace period**: the app opens instantly without internet once activated, but a remote deactivation still catches up within 3 days of it actually happening (or within ~30 minutes if the app is open and online — see the periodic check below).
- **`ui/ActivationScreen.kt`** — the real first-run blocking screen: name/phone (sent to `/api/app/signup`), activation code (sent to `/api/app/redeem`), with distinct error states for invalid code / already-used-on-another-device / deactivated / can't-reach-server. The admin-panel server URL is **not** user-editable in-app (an earlier version had a collapsible "SERVER URL" field; removed deliberately so no user can point activation at an arbitrary server) — it's a hardcoded fallback in `ActivationScreen.kt` (`serverUrl` initializer), used whenever `LicenseRepository.getServerUrl()` returns blank. Changing which admin panel the app talks to means editing that hardcoded string in code and shipping a new build, not a runtime setting.
- **`ui/MidiPaywallScreen.kt`** — shown instead of MIDI Learn when the current license's `midiPurchased` flag is false.
- **`MainActivity.kt`** — gates entry: Splash → (Activation screen if not yet activated) → the pad screen. Won't show the pads until activation succeeds once.
- **`OctapadScreen.kt`** — re-checks `/api/app/status` every 30 minutes while the app is open, so a remote deactivation or a MIDI purchase grant from the dashboard takes effect live, not just on next app restart.
- **Security note**: `license_prefs` (the SharedPreferences file holding all of this) is explicitly excluded from Android's OS-level backup — see the last item in §1.6.
- **`AndroidManifest.xml`** — `INTERNET` + `ACCESS_NETWORK_STATE` permissions added (previously missing entirely — the app couldn't talk to a server at all before this), plus `usesCleartextTraffic="true"` for local-network testing against a non-HTTPS admin panel URL during development.

**What you need to actually use this**: a real, reachable admin panel URL (see §2.6 — either your laptop's LAN IP for local testing, or a deployed URL like Vercel) and a real MongoDB Atlas connection string behind it. Without those, `/api/app/*` calls will fail and the activation screen will show "Couldn't reach the server."

### 1.9 What to specifically test once you can build this

- Rapid multi-pad hitting, and hitting the same pad repeatedly fast — the exact repro for the native race condition fix in §1.6, and for the multi-hit crackle/attack-fade fix in the 2026-08-18 pass
- The 2-finger swap/drag/mix gesture, repeatedly — hold for the full 2s and confirm the drag preview only appears then (not immediately on touch), drag to a different pad and release to confirm the menu opens, and release before 2s to confirm neither the preview nor the menu ever appear
- LOOP mode on a factory (not custom-imported) pad at a slow BPM, with the SPEED knob moved off 1.0x — should audibly speed up/slow down the retrigger rate now
- Mix / Add-To-End / waveform crop on factory kit sounds specifically
- Crop editor: drag a region, tap PREVIEW (should hear the selection without anything being saved yet), then either APPLY (commits) or navigate away without applying (original sample must be untouched)
- Delay: hit a pad with its own DELAY on while a *different* pad is actively looping with delay off — the looping pad's retriggers shouldn't clobber the first pad's delay tap (the exact repro for the 2026-08-18 audit's delay-clobber regression)
- Choke: assign a pad to Level 2 in the new folder UI, then Level 3 — confirm it leaves Level 2 (single-membership); if testing against a kit backed up before the 2026-08-18 choke redesign, check for orphaned level 5/6 assignments per the known-limitations note above
- MIDI knob sweep (VOLUME/PITCH/EQ) held for an extended period — the exact repro for the "knob gets stuck" fix (debounced persistence + gesture-cancellation fix)
- Importing a deliberately corrupted/truncated audio file — should fail gracefully, not degrade later imports
- Recording while another app holds the microphone — should show an error toast, not crash
- The full activation flow against a real deployed (or LAN) admin panel URL — note `ActivationScreen.kt` no longer has an in-app "SERVER URL" field, so this must be baked into the hardcoded fallback in code before testing
- A phone with a display notch/camera-cutout, in landscape — confirm the pad grid/control panel isn't clipped or drawn under the cutout
- Logcat for the `AudioEngine started: sampleRate=... sharingMode=0(Exclusive)/1(Shared)...` line, to see whether your test device actually grants Exclusive/MMAP mode — if it says Shared, that device's latency ceiling is a hardware/OS limit, not fixable from app code

---

## 2. Admin Panel (`admin-panel/`)

### 2.1 What it is

A small Next.js 16 app for managing the app's licensing data: generate activation codes, see which device each is bound to, remotely deactivate a device, grant/revoke the paid MIDI feature, and see the name+phone every device reports on first launch.

### 2.2 Stack

Next.js 16 (App Router, Turbopack) + TypeScript + Tailwind CSS + MongoDB (via Mongoose). No third-party UI kit — hand-built Tailwind components, no auth library — a single shared admin password, HMAC-signed session cookie.

### 2.3 Structure

```
admin-panel/src/
  app/
    login/page.tsx            — password form
    dashboard/page.tsx         — the whole admin UI (licenses + signups tabs)
    api/login, api/logout      — session cookie issue/clear
    api/licenses, api/licenses/[code]  — admin-only CRUD for license codes
    api/signups                — admin-only list of app signups
    api/app/signup             — PUBLIC: app calls this on first launch (name+phone)
    api/app/redeem              — PUBLIC: app calls this to redeem an activation code
    api/app/status               — PUBLIC: app calls this periodically to re-check active/midi status
  lib/
    mongodb.ts                 — cached connection helper
    auth.ts                    — password check + signed session token
    requireAdmin.ts            — 401-guard used by every admin API route
    generateCode.ts            — activation code generator (e.g. AB3D-9KXQ-7M2P)
  models/
    License.ts                 — { code, deviceId, active, midiPurchased, note, ... }
    Signup.ts                  — { deviceId, name, phone, installedAt }
  proxy.ts                     — Next.js 16's replacement for middleware.ts; gates /dashboard/*
```

> Note on `proxy.ts`: Next.js 16 renamed Middleware to Proxy (same feature, `export function proxy(...)` instead of `middleware(...)`, file renamed accordingly). If you're used to older Next.js docs, this is why the file isn't called `middleware.ts`.

### 2.4 Data model

**License**
```ts
{ code: string (unique), deviceId: string | null, active: boolean,
  midiPurchased: boolean, note: string, createdAt, redeemedAt, lastCheckInAt }
```
A code is unbound (`deviceId: null`) until the first device redeems it — after that it's locked to that device only. An admin can "Unbind" a code from the dashboard to free it up for a different phone.

**Signup**
```ts
{ deviceId: string (unique), name: string, phone: string, installedAt: Date }
```
Written automatically by the app on first launch (see §1.8) — this is what satisfies "name aur number mere paas aaye."

### 2.5 Auth

Single password from `ADMIN_PASSWORD` env var. Login issues an HMAC-SHA256-signed cookie (`admin_session`, httpOnly, 7-day expiry); `proxy.ts` verifies it on every `/dashboard/*` request and redirects to `/login` if missing/invalid/expired. No user table, no OTP — exactly what was asked for.

### 2.6 Setup

```bash
cd admin-panel
cp .env.local.example .env.local   # fill in MONGODB_URI + ADMIN_PASSWORD
npm install
npm run dev
```
Full step-by-step (including how to get a free MongoDB Atlas connection string) is in `admin-panel/README.md`.

**For the Android app to actually reach this**, `localhost` won't work from a phone. Either:
1. Run it locally (`npm run dev`) and use your laptop's LAN IP (e.g. `http://192.168.1.42:3000`) as the Server URL on the app's activation screen — phone and laptop need to be on the same WiFi.
2. Deploy it (Vercel is easiest: `npx vercel` from inside `admin-panel/`), with `MONGODB_URI` and `ADMIN_PASSWORD` set as environment variables there too, and use the public URL.

Either way, a real MongoDB Atlas connection string is required — without it, every `/api/app/*` call 500s.

### 2.7 Connected to the Android app

Unlike when this document was first written, the loop described here is now fully wired on the Android side too — see §1.8. The admin panel itself hasn't changed code-wise since it was first built; it was re-verified (clean production build, live server smoke test of login + all three `/api/app/*` routes) partway through this pass.

---

## 3. Build & Run — Quick Reference

**Android app:**
```bash
# Requires Android Studio, NDK 27.0.12077973, CMake 3.22.1, JDK 21, internet
# (first build fetches Oboe 1.9.0 from GitHub via CMake FetchContent)
./gradlew :app:assembleDebug
```

**Admin panel:**
```bash
cd admin-panel
npm install
npm run dev        # http://localhost:3000
npm run build       # production build check
```

---

## 4. Changelog

**BPM redefined as pure retrigger rate, WSOLA time-stretch removed entirely; Bank A/B pool separation confirmed (2026-09-12, final pass, client correction) — supersedes the `loopStretchRatioFor()` part of the entry directly below**

Client sent the textbook definition of BPM as a formal spec: "60 BPM = 1 second mein 1 beat, 120 BPM = 1 second mein 2 beats... BPM badhao to beat/loop tez hoga, BPM ghatao to dheema" — i.e. BPM should control purely how OFTEN a looping pad retriggers, nothing about the sample's own playback speed or pitch. Every WSOLA-stretch iteration up to this point (even the tightened 0.5x–2x clamp from a few hours earlier the same day) kept actually altering the sample's own audio to fit the beat interval, which is what kept causing the "quality kharab ho jaati hai, bahut fast baj raha hai" complaints regardless of how tight the clamp was — the sample was always being sped up or compressed by some amount.

- **WSOLA loop-stretch removed entirely** (not just re-clamped): `loopStretchRatioFor()` is gone, along with `fire()`'s per-slot stretch-ratio computation, the `lastLoopStretchKey` cache, and every `DrumEngine.setLoopStretch()` call — `useLoopStretch` is now always `false`. A looping pad's audio is never touched by BPM in any way; it always plays at its own normal recorded pitch/speed, identical to a one-shot.
- **New `loopBeatIntervalMs()` helper** computes `60_000f / bpm.coerceIn(40,300) / speed.coerceIn(0.9f,1.1f)`, floored at 50ms — pure retrigger-rate math, no sample-duration term at all (unlike the old ratio formula). The wait-loop uses `waitWindowMs = maxOf(beatIntervalMs, durationToShow)`: a high BPM can never cut a sample short or spin it up — retrigger happens exactly when the sample naturally finishes if that's later than the beat interval — and a low BPM simply adds a silent gap before the next repeat. This matches the client's literal spec exactly and can never regress into a "too fast" complaint again, since the sample's own audio is structurally never modified by this feature anymore.
- BPM range (40–300), default (120, from `PreferencesRepository.loadBpm()`), and live-update behavior (`onBpmChange` writes straight to the `mutableStateOf` var) were already correct and needed no change — confirmed against `TempoPanel.kt`.
- **Bank A/B separate-200-kit-pool-each + paired navigation, confirmed already correct** from the entry directly below (same day) — re-verified against the client's restated spec ("ABANK mein jaise 200 kit hai SAME BBANK mein bhi... load kare to direct ho jaye, ABANK ki jis kit mein jaye wahi SAME kit number BBANK mein bhi rahe, BBANK ke liye alag PATCH NEXT/PREVIOUS na ho") — no further changes needed here, already matches.
- Not built/tested on a physical device (no Android hardware available here) — reasoned through against the existing code paths.

**BPM loop-stretch restored with a tighter clamp; Bank A/B given back separate 200-kit pools (2026-09-12, same day, client correction) — PARTIALLY SUPERSEDED (the loop-stretch/clamp part) by the entry above; the Bank A/B pool-separation part still stands**

Client came back after the previous same-day fix with two corrections: (1) "BPM pehle chahiye tha, sahi lagbhag 50% kaam kar raha tha, speed jyada ho ja rahi thi... ab BPM wala effect koi kaam nahi kar raha, speed bhi kaam nahi kar rahi" — the "always return null" fix below over-corrected: BPM/SPEED should still affect loop playback rate, the actual complaint was the ratio occasionally overshooting to a very fast speed, not that BPM shouldn't do anything. (2) "A and B bank mein 200-200 chahiye... A bank ka patch B mein merge na ho, A alag B alag... jaise A mein 25 load kiya to B mein bhi saare duplicate ho jaate" — Bank A and Bank B need separate 200-kit pools again (not the single shared 0..199 pool from 2026-09-11), while the prev/next patch navigation still moves both banks together ("ek sath hi chahiye").

- **`loopStretchRatioFor()` restored** to the BPM/duration-derived formula (`beatIntervalMs / sampleDuration`), but the clamp is tightened from 0.25x–4x to **0.5x–2x** — a loop pad can still speed up or slow down to sync with BPM, just capped at half/double instead of quarter/quadruple, which is what produced the "bahut jyada fast" complaint at the default 120 BPM against a several-second sample. The PITCH −10/−1/+1/+10 steppers remain available as a separate manual control, unaffected by this.
- **Bank A and Bank B each have their own separate 200-slot kit pool again**, restoring the 2026-08-26 `BANK_B_KIT_START`/`BANK_B_KIT_END` design (0..199 = Bank A, 200..399 = Bank B) that the 2026-09-11 "share ONE pool" change had removed. `kits` is padded back to 400 entries on load (`"EMPTY B %03d"` naming for Bank B's blanks, same as the original 2026-08-26 fix). **Combined with pairing**: unlike the original 2026-08-26 design (which had fully independent per-bank navigation) or the 2026-09-11 shared-pool design (same Kit object on both banks), this restores the exact 2026-09-07 combination — separate underlying Kit data per bank, but `currentKitB` is driven by `LaunchedEffect(currentKit) { currentKitB = BANK_B_KIT_START + currentKit }`, so the single, shared prev/next control (no separate Bank B nav row) moves both banks' displayed number together while their actual kit data stays independent. `firstFreeBankBSlot()`, `deleteKit()`'s per-range in-place-reset, `targetSlotForLoad()`/`commitLoadedKitIndex()` (bank-aware via `bankMode == "B"`, converting between the shared displayed number and Bank B's own pool offset), and `copyKit(index, intoBankB)` are all restored to route into the correct bank's own pool. The Patch List (`KitListScreen`) is bank-aware again too — browsing/adding/copying while Bank B is active operates on `currentKitB`/`BANK_B_KIT_START..BANK_B_KIT_END`, not Bank A's range — driven by `bankMode` rather than the old separate `kitListTargetsBankB` toggle button (there's still only one Patch List button; it now behaves like every other bank-aware read in this file). `onRenameKit` (SETTINGS' rename entry point) was also switched from always targeting `currentKit` to `bankKitIdx()`, closing a pre-existing gap in the same "A and B must not alias each other's data" spirit.
- Not built/tested on a physical device (no Android hardware available here) — reasoned through against the existing code paths and the exact prior commits (`0d37814`, `f06db98`) that implemented this same combination before, not verified by ear or by an actual `./gradlew` build with the SDK installed.

**LOOP mode no longer auto-speeds a sample to match BPM; Bank B re-paired to Bank A's patch number (2026-09-12, client override) — PARTIALLY SUPERSEDED, see the entry above.** The BPM-stretch-removal and shared-single-pool parts of this entry were reversed the same day (see above); the pairing-navigation *concept* (single shared prev/next moving both banks) is the one part that still stands.

Client reported two things: (1) "loop sample ko LOOP MODE me daalte hi bahut fast baj raha hai, quality bhi alag ho jaati hai — normal tone rakho, aur slow karna ho to -1/-10 se ho jaaye"; (2) "Bbank use karne par alag se patch next/previous ka option aata hai, baar baar change karna padta hai — pehle jaisa rakho, Abank 5 pe ho to Bbank bhi 5 pe aa jaaye" (this exact pairing behavior existed before, see the 2026-09-07 entry, then was reversed 2026-09-11 — see the "independent navigation again" entry above).

- **LOOP auto-stretch-to-BPM removed.** `loopStretchRatioFor()` (`OctapadScreen.kt`) previously computed `beatIntervalMs / sampleDuration`, clamped to 0.25x–4x, and pushed it to native as a WSOLA time-stretch for every looping pad (the 2026-09-11 "BPM pitch-preserving time-stretch" feature). At the default 120 BPM (`beatIntervalMs` = 500ms), any loop sample longer than ~500ms — i.e. almost any real musical loop, as opposed to a short one-shot drum hit — got compressed toward the 4x-faster clamp, which is exactly the "bahut jyada fast, quality alag aa gaya" complaint (WSOLA compression also smears transients on top of the raw speed-up). `loopStretchRatioFor()` now always returns `null`: no stretch is computed, `setLoopStretch` is never called, and a looping pad plays its sample at normal recorded speed, same as a one-shot. The wait-loop's retrigger timing falls back to the pad's own raw `durationToShow`, matching what native actually plays. Manual slow-down/speed-up is still available through the existing PITCH −10/−1/+1/+10 steppers (`RightPanel.kt`) — pitch is sent to native unconditionally for every pad hit regardless of loop mode, so no separate mechanism was needed for that half of the request. BPM/SPEED remain visible in the LOOP panel but no longer affect playback rate — left as-is rather than removed, since the client only complained about the speed/quality problem, not about the controls' presence.
- **Bank B re-paired to Bank A's kit number.** Reverses the 2026-09-11 "independent navigation" design back to the 2026-09-07 pairing: `currentKitB` is still its own state var (kept so every native-slot/playback read site that already references it didn't need touching) but is now driven entirely by `LaunchedEffect(currentKit) { currentKitB = currentKit.coerceIn(...) }` instead of being independently stepped or persisted. `onKitBPrev()`/`onKitBNext()` and the `< B: kitname >` nav row in `RightPanel.kt` (params `kitBName`/`onKitBPrev`/`onKitBNext`/`onOpenKitListB`) are removed — there is nothing left for them to do since Bank B has no independent index anymore. The `bankBExtra` 26dp height-budget reservation for that row is gone too. `kitListTargetsBankB` (the flag that let the Patch List write into `currentKitB` instead of `currentKit`) is removed along with every branch that checked it — the Patch List, `targetSlotForLoad()`, `commitLoadedKitIndex()`, and `copyKit()` all just read/write `currentKit` unconditionally now; `currentKitB` catches up automatically via the pairing effect. **Net effect for A+B mode**: since Bank B always mirrors Bank A's number now, "A+B" layers a kit with itself rather than two different kits — this is the same trade-off the original 2026-09-07 pairing design had; the client's request explicitly asked to restore that exact prior behavior ("ye feature pehle tha").
- Bank A and Bank B still share the same single 0..199 kit pool (`BANK_A_KIT_CAPACITY` = 200, unchanged from 2026-09-11) — only the per-bank navigation independence is reversed, not the shared-pool merge.
- Not built/tested on a physical device (no Android hardware available here) — reasoned through against the existing code paths, not verified by ear or by an actual `./gradlew` build with the SDK installed.

**"Der tak bajane pe khich khich" (crackle after playing for a while) — denormal-float fix, plus WSOLA/nativeSlotsFor bug batch from a code-review pass (2026-09-11)**

Client reported crackle specifically after playing for an extended session, and asked for a full review of everything else too. Ran a multi-angle code review over the day's changes; real findings below, all fixed.

- **Root cause of the "der tak bajane pe" crackle: denormal floats in the always-running EQ IIR filter.** `onAudioReady`'s 3-band EQ (`lpState_`/`bp1State_`, single-pole shelving filters) runs on EVERY audio callback unconditionally — even full silence between hits — because the callback fires continuously regardless of whether any voice is active. Between hits, each filter's state decays toward 0.0f asymptotically and never actually reaches it, spending many callbacks passing through (and lingering in) the denormal float range (~1e-38 down to ~1e-45). Denormal arithmetic is a well-documented 10-100x slower path on many ARM/x86 FPUs without flush-to-zero enabled — on the realtime audio callback thread, that's a real risk of missing the callback deadline, heard as exactly this symptom: an intermittent glitch that gets more likely the longer/quieter a session runs, and that varies by device (depends on that specific CPU's denormal handling — also plausibly contributing to "kisi phone me hota hai kisi me nahi"). Fixed by flushing each filter state to exact `0.0f` once its magnitude drops below `1e-15f` (nowhere near audible, ~-300dB) instead of letting it keep decaying through denormal territory. This is the single most likely fix for the reported symptom.
- **WSOLA stretch cache could serve a previous sample's stretched audio.** `lastLoopStretchRatio` (Kotlin) only compared the numeric ratio, not which sample it was computed from — a kit switch (or re-crop) landing on a slot whose new sample happens to want a similar ratio (plausible; ratio is just `beatIntervalMs/durationToShow`) skipped the native call entirely, so the pad kept playing the OLD sample's stretched buffer. Fixed on both sides: Kotlin's cache (`lastLoopStretchKey`) now keys on `(sample identity, ratio)`, not ratio alone; natively, `AudioEngine::loadPadBuffer()` now drops (`loaded = false`) any cached `stretchedBuffers_[padIndex]` the instant a new raw sample is loaded, independent of whatever Kotlin's own cache decides — defense in depth.
- **WSOLA stretch could publish stale audio if a kit switch raced the (unlocked, multi-ms) correlation search.** `setPadLoopStretch`'s "don't publish if reloaded" guard only checked `buffers_[padIndex].loaded`, which stays `true` across a reload (now pointing at the new sample) — so a reload landing mid-computation could still publish a stretch computed from the OLD sample's data. Added a `bufferGeneration_[padIndex]` counter, bumped on every `loadPadBuffer()` call and captured before the unlocked WSOLA pass; publishing is now also gated on that generation being unchanged.
- **WSOLA ratio was computed once from the "primary" bank's sample and applied to every layered bank in "AB" mode.** `bankKitIdx()`'s A-precedence means `durationToShow` (used for the stretch ratio) always reflects Bank A's sample even when Bank B is also sounding — in `"AB"` mode with two different-length samples on the same pad, Bank B's audio was stretched using a ratio computed for Bank A's (different) duration, corrupting Bank B's loop entirely. Fixed: the stretch ratio is now computed per bank-slot, from that slot's OWN kit's own sample duration (raw duration × that kit's own crop span), inside the `bankSlots.forEach` loop instead of once outside it. (The wait-loop's overall retrigger *timing* is still governed by the primary bank's duration only — a separate, pre-existing limitation of sharing one retrigger clock across layered banks that predates BPM-stretch; not solved here, out of scope for this pass.)
- **WSOLA silently no-ops on very short/heavily-cropped samples, but the wait-loop still waited the (never-achieved) stretched duration.** `wsolaStretch()` returns the original unstretched audio when the input is shorter than its own ~25ms analysis frame, but still gets published as a "successful" stretch — so Kotlin's wait-loop would wait `durationToShow × ratio` (e.g. 3x longer) while native actually played the original, shorter length, producing a dead-air gap before the next retrigger — the mirror image of the bug the old `maxOf(...)` floor used to guard against. Fixed with a shared `loopStretchRatioFor(duration)` helper (also fixing a separate "the formula was duplicated between `fire()` and the wait-loop" reuse issue a review flagged) that returns `null` — meaning "don't stretch, use the raw duration" — for any sample under 30ms, matching native's own bail-out threshold.
- **Three more entry points had the same `nativeSlotsFor()` gap** the Bank-B-load fix addressed for Import Patch/Load Kit, found by the review: `WaveformEditorScreen.kt`'s crop-apply reload, `ImportScreen.kt`'s one-tap pad assign, and `AudioListScreen.kt`'s reassign/unassign paths all called `DrumEngine.invalidatePad(padIndex)`/`loadPad(..., padIndex)` with the default native slot (== padIndex, correct only for Bank A) instead of routing through `nativeSlotsFor()`. All three screens gained an `invalidateNativePads: (Int) -> List<Int>` parameter (defaulting to the old raw-index behavior so nothing else breaks), wired to `{ nativeSlotsFor(it) }` from `OctapadScreen.kt`'s call sites — editing/importing/reassigning a pad while Bank B or A+B is active now reloads the correct native slot(s) instead of silently touching Bank A's.
- **Two DOCUMENTATION.md entries from earlier the same day were left contradicting the later Bank-merge entry** (the pairing-effect and separate-Bank-B-pool description) — marked superseded in place rather than deleted, so the day's history still reads in order.
- Not verified on a physical device — the denormal fix in particular is a strong, well-matched theoretical fix for the reported symptom (a textbook realtime-audio bug class) but "confirmed to stop the crackle" needs an actual long play session on the client's own hardware.

**Bank A/B: one shared 200-kit pool, independent navigation again (2026-09-11, client override)**

Client, after the 2026-09-07 pairing fix: "mujhe ek hi list chahiye A and B, 0-199 ke beech dono ek hi mein... A kit 2 pe hai aur B kit 3 pe hai toh 2+3 bajega... maine B kit mein 5 select kiya hai toh main jaldi se switch karke B mein jaakar 5 ko baja sakunga." Confirmed back with the client before implementing: Bank A and Bank B should share ONE 200-slot kit pool (not two separate 200-slot pools), each bank keeping its OWN independent current-kit index into that shared pool (not auto-paired to the same number anymore) — so A on kit 2 + B on kit 3 plays kit 2 and kit 3 together, and landing both banks on the *same* number is expected to mean literally the same Kit object (deliberate this time, not the bug the 2026-08-26 "B bank kit isolation" fix was guarding against).

- `BANK_B_KIT_START`/`BANK_B_KIT_END` constants removed — `BANK_A_KIT_CAPACITY` (200) is now the one shared range both banks index into. The old always-blank Bank-B-only pool (previously padded onto indices 200..399) is gone; that padding loop was deleted from the kit-list initializer. An install saved from before this change may still have up to 400 entries on disk — the extra 200 are simply never read/written by either bank going forward (harmless leftover, not actively deleted, same pattern as other superseded-but-kept fields in this project).
- `currentKitB` is back to being independently navigable: initialized from its own persisted `PreferencesRepository.loadKitB()` (default index 25, "EMPTY 026" — deliberately not the same default as Bank A, so a fresh install doesn't have both banks silently pointing at the same kit) instead of being driven by a `LaunchedEffect(currentKit)` pairing effect (removed).
- **Restored Bank B's own kit navigation UI** in `RightPanel.kt`: a `< B: kitname >` row (prev/next steppers + tap-to-open-Patch-List) rendered whenever Bank B is part of the active selection (`'B' in bankMode`) — this is the same row that was removed in the 2026-09-07 pairing change, brought back with new `kitBName`/`onKitBPrev`/`onKitBNext`/`onOpenKitListB` params. `bankBExtra` (the height-budget reservation for this row) is `26.dp` while Bank B is active, `0.dp` otherwise, same mechanism the MASTER DELAY bar's own budget uses.
- `KitListScreen`'s `kitListTargetsBankB` flag (already existed, previously dead code since nothing opened the list for Bank B) is live again via the restored `onOpenKitListB`. Both A and B now use the identical `visibleRange = 0 until BANK_A_KIT_CAPACITY` (previously Bank B had its own separate `BANK_B_KIT_START..BANK_B_KIT_END` range) — same list, just a different highlighted/written index (`currentKit` vs `currentKitB`).
- `firstFreeBankBSlot()` removed — `firstFreeBankASlot()` is the one shared "find a free kit slot" search now, used by NEW KIT, Copy, Load Kit, Import Patch regardless of which bank they're for.
- `targetSlotForLoad()`/`commitLoadedKitIndex()` (added earlier today for the Bank-B-direct-load fix below) simplified: since any index can now belong to either bank, "which bank" can no longer be inferred from the index value alone — both functions now read/branch on `bankMode == "B"` directly instead of checking whether the index falls in a Bank-B-only range.
- `deleteKit()`'s in-place-reset range simplified to `index < BANK_A_KIT_CAPACITY` (was the union of both banks' separate ranges); blank-name generation no longer needs an "EMPTY" vs "EMPTY B" split since it's the same naming scheme for both banks now.
- `copyKit(intoBankB = true)` now searches the same shared free-slot pool as Bank A's copy path instead of a separate Bank-B-only pool, and got the same "all 200 slots full" toast guard Bank A's copy path already had (previously just silently `return`ed).
- Not built/tested on a physical device (no Android hardware available here) — `compileDebugKotlin` and `assembleDebug` both pass.

**Client batch: BPM pitch-preserving time-stretch, Bank B direct load, adaptive audio buffer (2026-09-11)**

Client (Hindi) asked for five things. Two were already done (verified, no change needed), one was a real gap, one was a genuinely new DSP feature, one was a device-crackle root-cause fix:

- **BPM should change a looping tone's OWN duration, pitch-preserving — not just the retrigger gap.** Client's example: same tone at BPM 100/200/300 takes progressively less real time to fully play, tone quality staying identical (explicitly contrasted with PITCH, which is expected to change tone character/"mota patla" as it changes speed — that's the existing varispeed PITCH knob, left untouched). This is a genuinely different feature from the existing BPM design (which only ever gated the *retrigger interval*, never touched a sample's own playback length) — flagged to the client as a new native DSP undertaking with a known trade-off (time-stretch algorithms audibly smear sharp transients — most drum/percussion one-shots — more than sustained/tonal material; no artifact-free fix for that without a much heavier algorithm) and no on-device listening test possible here. Client chose "implement best-effort" anyway. Implemented as WSOLA (Waveform-Similarity Overlap-Add) in `AudioEngine.cpp`:
  - `wsolaStretch()`: classic WSOLA — Hann-windowed analysis frames overlap-added at a synthesis hop that differs from the analysis hop by the target ratio (so output length ≈ input length × ratio), with a small ±(synHop/2) search per frame for the analysis offset whose overlap region best correlates with what's already written (the "waveform similarity" step that reduces, not eliminates, phase-cancellation buzz at the seams). Ratio clamped to 0.25x–4x.
  - `AudioEngine::setPadLoopStretch(padIndex, ratio)`: copies the pad's current buffer under `bufferMutex_` (cheap), runs the actual WSOLA pass **outside** the lock (real CPU work — a few ms, must never block the audio thread or a concurrent `triggerPad`), then publishes the result into a new `stretchedBuffers_[padIndex]` under the lock again. A ratio within 1% of 1.0 just drops the cached stretch instead of computing a no-op one.
  - `Voice` gained `useStretchedBuffer` (same write-before-`ready` discipline as every other Voice field). `triggerPad()` takes a new `useLoopStretch` param — when true and a stretch is actually cached and loaded, the voice claims its `position`/`totalFrames` from `stretchedBuffers_[padIndex]` instead of `buffers_[padIndex]`; otherwise it transparently falls back to the raw buffer (a pad that just started looping, or whose BPM just changed, may not have a fresh stretch yet). `onAudioReady`'s mixing loop picks the same buffer per-voice. Since the stretched buffer keeps the same `sampleRate` as the original (WSOLA changes length, not sample rate) and playback rate is still `pitch × (buf.sampleRate/outputSampleRate_)`, a voice plays the stretched (or raw) buffer at the correct pitch automatically — no separate pitch-compensation math needed.
  - `NativeBridge`/`DrumEngine` gained `setPadLoopStretch(padIndex, ratio)` and `triggerPad`/`trigger(...)` gained the `useLoopStretch` param, threaded through the JNI layer (`native-lib.cpp`).
  - `OctapadScreen.kt`'s `fire()` computes `ratio = beatIntervalMs / durationToShow` (clamped 0.25x–4x, same BPM/SPEED math as before) whenever `effectiveLoop()` is true for the pad being hit, and pushes it to native — but only when it actually changed from the last value pushed for that native slot (`lastLoopStretchRatio` map), since re-running WSOLA on every single retrigger of an already-stable loop would be pure waste. The wait-loop's old `waitWindowMs = maxOf(beatIntervalMs, durationToShow)` floor (added earlier specifically to avoid cutting a sample that couldn't otherwise fit the beat) is gone — the sample now genuinely stretches to fit, so `waitWindowMs` is exactly the stretched duration (computed with the same clamp fire() uses, so the coroutine's retrigger timing can't drift from what native actually produced).
  - One-shot (non-looping) hits are completely unaffected — `loopStretchRatio` is `null` whenever `effectiveLoop()` is false, so `useLoopStretch` is `false` and native never touches the stretched-buffer path.
  - **Caveat repeated for the record:** WSOLA is a time-domain technique with a well-documented weakness on sharp transients. Expect audibly better results on sustained/tonal loop material than on punchy one-shot drum hits stretched aggressively; this was disclosed to the client before implementing and accepted as "best effort."
- **Bank A/Bank B pairing (next/prev moves both banks together)** — client asked for this; it was already implemented (`2026-09-07` entry below, `LaunchedEffect(currentKit) { currentKitB = ... }`). Verified correct **at the time this entry was written** — later the SAME day the client asked for the opposite (independent per-bank navigation over one shared pool) and the pairing effect was removed; see the "Bank A/B: one shared 200-kit pool, independent navigation again" entry above, which supersedes this one. Left here only so the day's history reads in order.
- **Bank A direct patch load works; Bank B's didn't.** `targetSlotForLoad()` (used by "Load Kit From Folder" and "Import Patch") was hardcoded to Bank A's pool regardless of which bank was actually active — loading while Bank B was selected silently landed the kit in Bank A instead. Fixed to be bank-aware and to fix the two screens' `DrumEngine.invalidatePad(padIndex)` calls to go through `nativeSlotsFor(padIndex)`. **Superseded the same day**: the specific mechanism described next (checking `currentKitB` against a separate `BANK_B_KIT_START..BANK_B_KIT_END` range, and a `commitLoadedKitIndex()` that inferred the bank from the index's numeric range) no longer applies now that Bank A and Bank B share one pool — see the "Bank A/B: one shared 200-kit pool" entry above for the current `targetSlotForLoad()`/`commitLoadedKitIndex()` behavior (branches on `bankMode == "B"` directly). The bank-aware *intent* and the `nativeSlotsFor()` fix both still stand; only the range-based implementation detail is stale.
- **Crackle inconsistent across devices ("kisi phone me khich khich, kisi me nahi, sab me same hona chahiye").** Root cause: the Oboe stream deliberately opens at a single burst for the lowest possible latency — a good default, but some phones' schedulers can't reliably service the audio callback at that size and underrun (audible as a click/glitch), while others handle it fine — exactly the "some devices, not others" pattern reported. Rather than pick one fixed buffer size and gamble across every device in the field, `onAudioReady` now self-tunes per running stream: it checks `stream->getXRunCount()` every callback (a cheap counter read) and, the first time an actual underrun is detected, grows the buffer by one more burst (capped at 4x, still low-latency) via `setBufferSizeInFrames`. A phone that can sustain 1-burst timing keeps the lowest latency; one that can't gets a few more ms of buffer automatically, only once it actually needs it.
- Not built/tested on a physical device (no Android hardware available here) — `compileDebugKotlin`, `externalNativeBuildDebug`, and `assembleDebug` all pass, so this is confirmed to compile and link, not confirmed to sound correct. The WSOLA quality/transient-smearing trade-off above specifically needs real ears on a real device before being called final.

**Post-review bug batch: LOOP migration, load-slot overwrite, slider drag, loop poll, patch-list filter (2026-09-09)**

Full-project review pass after the `MASTER_DELAY` fix. Five issues found, all fixed:

- **Per-kit LOOP re-enabled itself on kit 0 every launch** for users who upgraded from the old global LOOP flag. `PreferencesRepository.loadLoopEnabledKits()` returned `emptySet()` for *both* "never saved" (key absent) and "saved, all kits off" (empty string) — so once such a user turned kit 0's LOOP back off, the list serialized to `""`, was read back as `emptySet()`, the one-time migration re-ran, `loadLoopEnabled()` was still `true`, and `add(0)` fired again. Fixed: `loadLoopEnabledKits()` now returns `Set<Int>?` (null = never saved), the migration branch persists its result immediately *and* clears the old `KEY_LOOP_ENABLED` flag so it can only run once.
- **`targetSlotForLoad()` overwrote a populated user-built kit.** It returned `currentKit` whenever `factoryKitNumber == -1`, but that's true for every user kit, not just blank ones — so Load Kit / Import Patch while viewing a kit you'd built silently replaced it. Now also requires `name.startsWith("EMPTY ")` (same test `firstFreeBankASlot()` uses); a non-blank current slot falls back to the first free blank, matching the long-standing pre-2026-09-03 behavior.
- **VOL/PITCH `LinearSlider` drag could be stolen by the parent `verticalScroll`.** After `RightPanel`'s main column got a scroll fallback, the sliders sit inside a scrollable and a near-vertical drag lost arbitration mid-adjust. The gesture now consumes `awaitFirstDown()` before the move loop, claiming the pointer so the ancestor scroll can't yank it.
- **Loop retrigger poll `delay(15)` ran flat-out for the whole wait**, ~3.3× the old `delay(50)` idle wakeups — noticeable for a held PLAY MODE = LOOP pad whose wait re-runs back-to-back indefinitely. Now adaptive: `delay(40)` while there's real time left, `delay(12)` only in the final 60ms, keeping the tight retrigger latency without the constant wakeups.
- **`KitListScreen`'s `filteredKits` recomputed an O(400) `withIndex().filter()` on every recomposition** (every search keystroke, highlight change, animation frame) after the inline-`run{}` fix for the frozen-list bug. Wrapped in `derivedStateOf` — still subscribes to the `SnapshotStateList`'s structural changes and to `searchQuery`, but only recomputes when one of those actually changes.

Not built/tested here (no SDK/NDK) — unverified until an on-device build.

**`MASTER_DELAY` MIDI note now toggles the master kill switch (2026-09-09)**

Client (Hindi): "MIDI me master delay pehle on/off ho raha tha, ab nahi — paid ki button se sirf delay folder (per-pad) on ho raha hai, master wala nahi." Verified — the report is accurate:

- `DELAY_TOGGLE` (both the MIDI note and the older CC target) only ever flipped the **selected pad's** `padDelayEnabled` flag via `toggleCurPadDelayEnabled()`. That is the "single / folder" toggle the client saw still working.
- `MASTER_DELAY` (added 2026-09-03) only ever **opened the DELAY panel** (`openDelayPanelRequest++`). Nothing in the MIDI path touched the global `delayMasterEnabled` kill switch — it could only be flipped by tapping the on-screen "MASTER DELAY: ON/OFF" bar. So from a hardware button there was no way to mute/unmute delay globally.
- **Fix:** `MASTER_DELAY`'s note handler now does `delayMasterEnabled = !delayMasterEnabled` directly. Safe from the long-lived `LaunchedEffect(Unit)` note handler because `delayMasterEnabled` is a remembered `mutableStateOf` delegate (stable reference), not a per-recomposition `val` — this is *not* the stale-capture shape that caused the old `DELAY_TOGGLE` only-turns-on bug. The `LaunchedEffect(delayMasterEnabled)` that persists it via `PreferencesRepository.saveDelayEnabled` still fires, and the effective state pushed to native stays `padDelayEnabled && delayMasterEnabled`.
- `openDelayPanelRequest` + its `RightPanel` `LaunchedEffect` are left in place (now unincremented) in case a "open the DELAY panel from a button" target is wanted later.
- Not built/tested here (no SDK/NDK) — unverified until an on-device build.

**Bank B is now permanently paired to Bank A's kit number (2026-09-07)**

Client feedback (Hindi): "B bank se ye [manual selector] htwa do — B bank me patch banane ke baad baar baar set karna padta hai. Niche ke next/previous button se hi B bank change ho. A bank ka 10 number kit dabau to B bank me bhi automatic 10 number kit aaye." The old workflow forced the user to re-pick the Bank B kit every time they changed the Bank A patch.

- **Removed the manual Bank B kit selector** — the "`< B: kitname >`" row in `RightPanel.kt` (step buttons + tap-name-to-open-patch-list) is gone, along with its params (`kitBName`/`onKitBPrev`/`onKitBNext`/`onOpenKitListB`) and the `bankBExtra` height budget it needed.
- **`currentKitB` is driven from `currentKit`**: `LaunchedEffect(currentKit) { currentKitB = BANK_B_KIT_START + currentKit.coerceIn(0, BANK_A_KIT_CAPACITY - 1) }`. Bank A kit index N always pairs with Bank B pool slot N (displayed "EMPTY B 0NN" — numbers line up). Bank A's `<`/`>`/PATCH LIST/MIDI PC nav now moves Bank B in lock-step; there is no independent Bank B navigation.
- Bank B is still a real, separately-persisted 200-slot pool (`BANK_B_KIT_START..BANK_B_KIT_END`) — editing pads while Bank B or A+B is active still writes into the paired B-pool slot via `bankKitIdx()` and persists, so each Bank A patch keeps its own dedicated Bank B layer that comes back automatically.
- `currentKitB` stays a `var` (not `derivedStateOf`) and the now-unreachable `kitListTargetsBankB == true` branches in the KitListScreen overlay are left in place (dead, compile-only). `firstFreeBankBSlot()` / `copyKit(intoBankB = true)` are likewise now dead.
- Not built/tested here (no SDK/NDK) — unverified until an on-device build.

**User bug-report batch: BPM restored as loop base, SPEED = fine-tune, PITCH in semitones (2026-09-08)**

Client feedback (Hindi): "Speed aawaj same rahega sirf speed badega" (SPEED = tempo only, keep), "Pitch me aawaj badal jaayega" + "A# B# C# aisa … saath hi -1/-10/+1/+10 bhi dalna" (PITCH = musical, notes + steppers), "BPM wale remove nahi karna tha, thoda thik karna tha".

- **BPM restored to the LOOP panel** — display + `-10/-1/+1/+10` steppers back in `TempoPanel.kt`; `bpm`/`onBpmChange` re-threaded `OctapadScreen → RightPanel → TempoPanel`.
- **SPEED demoted to a fine-tune multiplier around BPM.** Slider range `0.9x–1.1x` (was `0.5–2`). Loop math: `beatIntervalMs = 60000 / bpm / speed.coerceIn(0.9,1.1)`, then `waitWindowMs = maxOf(beatIntervalMs, durationToShow)` for every looping case — the "never cut the sample" floor is back. `speed` is clamped into `0.9..1.1` on load so old persisted values snap in-range.
- **SPEED still does not change pitch** — the 2026-09-07 varispeed removal stays.
- **PITCH shown as musical semitones.** `RightPanel.kt` gains `pitchToSemitones` / `semitonesToPitch` / `semitoneLabel` (unity `1.00x` = "C 0", ±12 st = 0.5x/2x). The PITCH slider's readout is the note label; a new `-10/-1/+1/+10` semitone stepper row (`PitchStepButton`) under the VOL/PITCH sliders steps whole semitones (clamped ±12), live-updating via the existing `onPitchChange`.
- `delay(15)` loop poll kept.
- Not built/tested here (no SDK/NDK) — unverified until an on-device build.

**User bug-report batch: SPEED is the only loop control, BPM + varispeed removed (2026-09-07)**

Client feedback (Hindi), reversing most of batch #2 below: "sabhi patch me loop ka speed work krta hai, loop k liye bas speed rakhna hai" / "loop mode me tempo kaam nahi krta" / "speed me sayad pitch change hota hai, isko hta k sirf tone fast ho" / "loop pick turant nahi pakadta, late se loop start hota hai". Offered a real pitch-preserving time-stretch (native DSP); client chose the retrigger-rate approach.

- **SPEED no longer changes pitch (varispeed removed).** `fire()` triggered voices at `kits[...].pitches[index] * speed` — now just `kits[...].pitches[index]`. The two sites that mirrored the old varispeed for live agreement (the `PITCH` MIDI-CC handler and the on-screen `onPitchChange` slider) push raw pitch too.
- **SPEED is the sole loop-rate control; BPM dropped from the loop math.** The retrigger window is now `loopIntervalMs = durationToShow / speed.coerceIn(0.25f, 4f)` (floored 50ms) for every looping case — no `beatIntervalMs`, no `maxOf` floor. SPEED > 1 repeats faster (cuts a long sample short — that is the requested "tone fast ho"); SPEED = 1 is seamless back-to-back; SPEED < 1 leaves a gap. Removing the `maxOf(beatInterval, …)` floor is also what fixes "loop turant nahi pakadta" — that floor made repeats wait out a full beat at slow BPM.
- **BPM removed from the LOOP panel UI.** `TempoPanel.kt` lost its BPM display + `-10/-1/+1/+10` steppers; `bpm`/`onBpmChange` params dropped from `TempoPanel` and `RightPanel`. The `bpm` state var, `PreferencesRepository.loadBpm/saveBpm`, and the backup `KEY_BPM` field are kept (unread by any UI) so existing backups still round-trip.
- **Loop poll tightened** from `delay(50)` to `delay(15)` in the wait loop so a repeat fires within ~15ms of the sample ending.
- Not built/tested here (no SDK/NDK) — unverified until an on-device build.

**User bug-report batch #2: per-kit LOOP, loop cut-off + BPM, BPM +10 button, patch-load slot, patch-list actions (2026-09-03)**

Follow-up after the client re-tested the batch below.

- **LOOP is now per-kit, not one global switch.** Was `loopEnabled` (a single `PreferencesRepository` boolean). Now `loopEnabledKits` — a `SnapshotStateList<Int>` of kit indices with LOOP on, persisted as a comma-separated list (`KEY_LOOP_ENABLED_KITS`). `loopOnForCurrentKit()` / `setLoopForCurrentKit()` (declared right after `bankKitIdx()`) read/write it live for the active bank+kit, so turning LOOP on in one kit only loops that kit, changing patch drops it, and returning restores it. `effectiveLoop()`'s ONESHOT branch and the `RightPanel` toggle both route through the helpers. Old global flag kept for backup round-trip only.
- **Loop retrigger window unified + never cuts the sample.** Both loop cases (`currentMode == "LOOP"` and the global toggle on an ONESHOT pad) now use `maxOf(beatIntervalMs, audibleLenMs)` where `audibleLenMs = durationToShow / speed` (SPEED is a pitch/varispeed multiplier, so a 2× sample really finishes in half the time). This fixes: (a) "BPM loop mode me kaam nahi karta" — BPM now gates LOOP mode too; (b) "speed badhane par loop cut jata hai, poora tone play nahi hota" — `maxOf` guarantees the window is never shorter than the sample's real audible length. Trade-off the client explicitly asked for: a short sample at a slow BPM now leaves a rhythmic gap between repeats instead of a gapless stutter.
- **BPM "+10" button was clipped off-screen.** `TempoPanel.kt`'s 4 BPM step buttons were fixed 40dp wide with 10dp gaps (~190dp) inside a ~165dp content box, so "+10" was cut off the right edge and untappable. Now `Modifier.weight(1f)` each in a `fillMaxWidth` row — always fits. (`BpmStepButton` gained an optional `modifier` param.)
- **"Load Kit From Folder" / "Import Patch" now land in the current patch slot.** Was `firstFreeBankASlot()` (lowest blank slot — "30 pe load kiya, 43 pe save ho gaya"). New `targetSlotForLoad()`: uses `currentKit` when it's a non-factory Bank A slot, else the first blank. The client navigates to the patch they want and loads there.
- **Patch List New/Copy/Delete "still not working".** `KitListScreen.kt`'s `filteredKits` was `remember(kits, …)` — `kits` is a `SnapshotStateList` whose reference never changes, so in-place element mutations (add/copy/delete all write `kits[i] = …`) never invalidated the `remember` and the on-screen list stayed frozen. Computed inline now so it tracks structural changes. New Kit / Copy also close the Patch List so the result is visible.

**User bug-report batch: kit-load slot, patch-list hide, loop cut-off, MIDI EDIT toggle, FX/DELAY MIDI targets (2026-09-03)**

Client reported six issues in Hindi. Verified each against the code; five were real and are fixed.

- **COPY in the Patch List landed a kit off-list / "COPY, NEW, DELETE kaam nahi kar raha".** `copyKit(intoBankB = false)` did `kits.add(newKit)` — but `kits` is permanently padded to 400+ entries (Bank A 0–199, Bank B 200–399), so the copy landed at index 400+, outside Bank A's Patch List range and every `<`/`>`/PC/Note nav cap, and `currentKit` jumped to an unreachable slot. That corrupted `currentKit` for the rest of the session, which is why NEW/DELETE also *looked* dead. Now writes into `firstFreeBankASlot()` like NEW KIT / Load Kit / Import Patch already do — so loaded/copied kits fill sequentially from KIT 26 instead of "random" slots (53/78/43). NEW KIT and DELETE were correct in isolation.
- **Right control strip clipped its bottom "◄ PATCH LIST ►" row on short screens** (worse after tapping BANK / A+B, which adds the Bank B kit row) — "kisi ke phone me aadha dikhta hai, patch list hide ho jaata hai". The column deliberately had no scroll and a `heightScale` floored at 0.72, so on a short-aspect device the fixed dp budget overflowed and the last row was dropped. Added a bounded `verticalScroll` fallback to `RightPanel.kt`'s main column — tall screens still don't scroll, short ones keep every control reachable. (Overrides the older "trim, don't scroll" note there — that approach was re-tuned repeatedly and users kept hitting it.)
- **Global LOOP toggle on an ONESHOT pad cut the sample off mid-playback** — "aawaz beech se kat kar continue bajti hai, poora nahi bajta". `waitWindowMs` for the beat-synced case was pure `60_000/BPM`, so any sample longer than one beat was chopped on every repeat (a deliberate earlier tradeoff for the SPEED knob). Per explicit request it's back to `maxOf(beatIntervalMs, durationToShow)` — sample always plays in full, BPM only tightens the loop down to the sample's own length. (The "+10 BPM" button the client asked for already exists in `TempoPanel.kt` — they were testing an old build.)
- **MIDI-note "EDIT" target only opened the crop editor, never closed it.** `nm.getNote("EDIT") -> { topPanel = "EDIT" }` — now `topPanel = if (topPanel == "EDIT") "" else "EDIT"`. Same stale-set class as the old DELAY_TOGGLE bug.
- **FX panel and MASTER DELAY panel had no MIDI-note mapping.** Added `"FX"` and `"MASTER_DELAY"` targets to `NoteMapRepository.TARGETS` (MidiLearnScreen picks them up automatically). Their panel open-state lives inside `RightPanel`, so `OctapadScreen` bumps `openFxPanelRequest` / `openDelayPanelRequest` counters that `RightPanel` watches via `LaunchedEffect` and toggles the matching panel (routed through `closeAllPanels()`). CROP was already covered by the existing `EDIT` target.
- Not built/tested here (no SDK/NDK) — treat as unverified until an on-device build.

**On-screen VOL / PITCH sliders didn't affect a sounding pad (2026-09-02)**

`RightPanel`'s `onVolumeChange` / `onPitchChange` only wrote the kit value + `persistKitsDebounced()` — unlike the Pan/Gain/EQ sliders and the MIDI-CC path, they never called `DrumEngine.setVolume` / `setPitch`. So dragging VOL or PITCH did nothing to a currently sounding or looping pad; it only took effect on the next hit. Now both push to native live via `nativeSlotsFor(selectedPad)`, and PITCH is multiplied by `speed` to match `fire()`'s varispeed so a live drag and the next retrigger agree (the MIDI-CC PITCH handler got the same `* speed` for consistency).

**EDIT MODE "Clear Sound" didn't work on factory pads (2026-09-02)**

`clearPadSound()` sets `kits[..].sounds[pad] = -1`, but the Bank A / Bank B pad-reload `LaunchedEffect`s keyed only on `currentKit`, the `AudioRepository` audio list, and `padReverse` — **not** on the `sounds` list. So clearing a custom sound worked (it mutates `AudioRepository`), but clearing a **factory-only** pad (the common case) changed nothing the effect watched: the native slot kept the old sample and the pad played on until the next kit switch. Fixed by adding `kits[..].sounds.toList()` to both effects' keys, and by making `clearPadSound` invalidate the real `nativeSlotsFor(pad)` slot(s) instead of always slot 0–7.

**Play Store release prep (2026-09-02)**

Repo-side changes toward a publishable build (see `RELEASE_CHECKLIST.md` for the full list, including what still needs a device / Google account):

- `applicationId` → `com.arunspd30.octapad` (Play rejects `com.example.*`). `namespace` stays `com.example.myapplication` so the R class and every `Java_com_example_myapplication_*` JNI symbol are untouched — the two are independent.
- R8/minify keep rules written (`app/src/main/keepRules/rules.keep`, keeps the whole app package + JNI bridge + enums) but minify left OFF — AGP 9.x needs the experimental "gradual R8" flag and a device to validate the shrunk build; not a Play blocker. Enable procedure is in `app/build.gradle.kts`'s `buildTypes.release` comment.
- 16 KB page-size link flag in `CMakeLists.txt` (`-Wl,-z,max-page-size=16384`) — Play requirement for native apps.
- `LicenseRepository.OFFLINE_GRACE_MS` 3 → 14 days.
- `.gitignore` broadened; `.idea/` untracked. `PRIVACY_POLICY.md` + `RELEASE_CHECKLIST.md` added.
- Not built/tested (no SDK/NDK here). Cleartext HTTP left enabled per owner's decision (may self-host plain-http server).

**User bug-report batch: branding, logo size, MIDI delay toggle, BPM/SPEED split, kit-change crackle, per-phone display (2026-09-02)**

Client reported eight issues in Hindi; #3 (re-applying delay after editing already-delayed tones in Crop) was explicitly deferred by the client. The rest:

- **App name → "ARUN SPD-30 MOBILE OCTAPAD".** `strings.xml` `app_name` plus the on-screen wordmarks in `SplashScreen.kt`, `ActivationScreen.kt`, `RightPanel.kt`.
- **App logo smaller** so the full artwork + name are visible: splash logo 140→104dp (border removed), activation logo `ContentScale.Crop`→`Fit` and 72→64dp.
- **MIDI note DELAY_TOGGLE only ever turned delay ON.** The raw Note-On handler installed in `LaunchedEffect(Unit)` captured `curPadDelayEnabled` (a per-recomposition `val`) once at first composition — always `false` — so `!curPadDelayEnabled` was always `true`. Now calls a new `toggleCurPadDelayEnabled()` that re-reads the pad's live flag from `kits[bankKitIdx()].padDelayEnabled` at call time.
- **BPM and SPEED are now separate concerns.** SPEED (LOOP panel) became a pitch/varispeed multiplier on the sample (`fire()` multiplies voice pitch by `speed`, coerced 0.25–4×), removed from all loop-interval math. BPM is now the sole loop-tempo input (`60_000f / bpm`). Per-pad LOOP mode stays gapless (retriggers on `durationToShow / speed`). Fixes "speed se tone cut-cut" and "BPM loop me kaam nahi karta".
- **Master delay pulled out of the DELAY panel** onto the main control strip as a `MASTER DELAY: ON/OFF` bar (per-pad DLY toggle stays next to VOL).
- **Crackle when changing kit while pads still sound.** `AudioEngine::loadPadBuffer` now flags any voice currently playing the reloaded pad as `releasing` (under `bufferMutex_`) before swapping the sample in, so it fades out over ~5ms instead of jumping mid-waveform into the new buffer.
- **"Display har phone me alag."** `MainActivity` remaps `LocalDensity` so the usable screen is always `820dp` wide (`scaledDensity = realDensity * screenWidthDp / 820`), cancelling per-OEM density and the "Display size"/zoom setting; layout is now uniform across phones, height still tracks aspect ratio.

Not device-tested (no Android SDK/NDK in this environment) — the audio-engine and density changes especially need an on-device pass.

**User bug-report batch: kit-load slots, non-destructive crop, loop SPEED, delay drop (2026-09-01)**

Five issues reported by the client in Hindi:

- **New kit / Patch List "+ NEW KIT" / Load Kit From Folder / Import Patch all broken** — one root cause. `kits` is permanently padded to ≥400 entries (Bank A pool 0..199, Bank B pool 200..399), but every "create a Bank A kit" path did `kits.add(...)`, landing the new kit at index 400+ — outside Bank A's Patch List range (0..199) *and* outside `onKitNext`'s `BANK_A_KIT_CAPACITY-1` cap. So the NEW KIT button did nothing (`if (kits.size < 200)` is never true), and a loaded/imported kit was invisible in the list ("patch save nahi hota") with `>` dead (only `<` worked, walking into Bank B's pool). Added `firstFreeBankASlot()` and routed all four paths into the first blank slot inside Bank A's own 0..199 pool. Startup clamp pulls a stuck `currentKit >= 200` back into range for already-affected installs.
- **Crop is now non-destructive.** `WaveformEditorScreen`'s CROP mode no longer rewrites the sample file — it stores a play-window as `Kit.padCropStartPct` (new) + `padLengthPct` (existing end-trim) and the native engine plays only `[startFraction, lengthFraction]` of the still-full-length buffer. New native `Voice::startFraction` + `triggerPad(..., startFraction)` param threaded through JNI/`NativeBridge`/`DrumEngine`/`onPadHit`/persistence/swap/copy/import-patch. DELETE-region cuts still rewrite the file (can't be a single window) and reset the pad's crop window to full on commit. The editor seeds its handles from the pad's saved window; RESET commits the cleared window. `onPadHit` scales the LCD timer + LOOP retrigger interval by the crop span so a cropped looping pad doesn't retrigger with a silent tail.
- **LOOP-mode SPEED had no effect past a point.** The global Loop toggle's retrigger window was `maxOf(beatIntervalMs, durationToShow)` — once SPEED made a beat shorter than the sample, raising it further did nothing. Now uses `beatIntervalMs` (already BPM×SPEED scaled, floored at 50ms) directly, so SPEED always tightens/loosens the loop, truncating the sample when the beat is shorter.
- **Delay dropped when any other pad was hit.** `AudioEngine::setDelayEnabled(false)` cleared the pending-echo queue — and Kotlin calls it synchronously before *every* pad hit via `syncDelayForHit()`, so hitting (or even just selecting) any delay-off pad wiped a delay-on pad's still-pending echo. Removed the queue-clear; `delayEnabled_` still gates whether *new* taps schedule, already-queued taps drain naturally.

Built clean (`:app:assembleDebug`, all 4 ABIs). Not device-tested.

**Full-project audit: kit-load race + JNI thread-attach leak (2026-08-31)**

Client asked for a general "check the whole project for bugs" pass (audio crackle in particular). A read-only audit found the documented crackle/race fixes (voice `active`/`ready` ordering, headroom/soft-clip, attack/release fades, `bufferMutex_`/`delayMutex_` ordering, choke `clear()` data loss) all still genuinely hold — no regression there. Two previously-unreported bugs did turn up and were fixed:

- **`DrumEngine.kt`'s `loadPad()` — stale-sample race on fast kit switching.** Each call launched its own independent `CoroutineScope(Dispatchers.Default).launch {}` to decode + `NativeBridge.loadPadAudio()`, with no ordering against a still-in-flight call for the *same* `nativeSlot`. Decode time depends on file size/format, not call order — stepping through kits (Patch List prev/next, MIDI PC) faster than a decode could finish meant whichever coroutine's decode happened to finish *last* won the native buffer, not necessarily the most recently selected kit. A pad could end up silently playing a stale/previous kit's sample until the next reload. Fixed with `pendingKey`/`loadJobs` arrays (per native slot): a new `loadPad()` call cancels the previous in-flight `Job` for that slot outright, and a decode that does finish only applies its result if `pendingKey[nativeSlot]` still matches the key that triggered it (belt-and-suspenders against a cancellation racing a decode that's already past its last suspension point).
- **`native-lib.cpp`'s MIDI→Kotlin JNI bridge — `AttachCurrentThread()` never paired with `DetachCurrentThread()`.** `sendControlChangeToKotlin`/`sendPadHitToKotlin`/`sendLearnAssignedToKotlin`/`sendProgramChangeToKotlin`/`sendRawNoteOnToKotlin` all attach the calling (MIDI-receive/binder pool) thread on every call and never detach — a real JNI leak: if that native thread is ever recycled by the framework's thread pool while still attached, ART logs (and on some configurations can abort on) "native thread exited without calling DetachCurrentThread". Added `GetEnvForCurrentThread()`, which only attaches if the calling thread isn't already attached (checked via `GetEnv()`) and reports that back so each `sendXToKotlin()` only detaches a thread it itself attached, never one the JVM/framework already owned the lifecycle of.

Not verified on a physical device or an actual `./gradlew` build in this environment (no Android SDK/NDK available here — see the root `CLAUDE.md`'s standing note on that).

**Bank B kit isolation — real fix, supersedes the 2026-08-21 entry below (2026-08-26)**

The 2026-08-21 "Bank B: two fixes" entry below only nudged Bank B off Bank A's *currently selected* kit when the two happened to collide — Bank A and Bank B still indexed into the exact same shared 200-slot `kits` list underneath, so manually navigating Bank B to any kit number Bank A wasn't actively sitting on (the common case — e.g. Bank A on kit 1, Bank B on kit 2) still opened the literal same `Kit` object Bank A's kit 2 uses; editing/importing/cropping a pad from Bank B still silently mutated the shared pre-built A-bank kit. Caught in a post-implementation audit, not by a new client report.

Real fix: `kits` (`OctapadScreen.kt`) is now always kept at a minimum of 400 slots — 0..199 stays Bank A's original pool (25 factory kits + blanks, unchanged), 200..399 (`BANK_B_KIT_START`/`BANK_B_KIT_END`) is a second, permanently-blank pool reserved exclusively for Bank B. The two ranges never overlap, so the two banks can no longer land on the same `Kit` object under any navigation path:
- `currentKitB` is clamped into `BANK_B_KIT_START..BANK_B_KIT_END` on load (migrates a pre-fix install's saved value, including the old default of 25, off Bank A's range); `PreferencesRepository.loadKitB()`'s fresh-install default moved from `25` to `200`.
- Bank A's own `<`/`>` stepping and Patch List, the MIDI Program Change handler, and the MIDI Note `PATCH_NEXT`/`PATCH_PREV` actions are all capped at `BANK_A_KIT_CAPACITY - 1` (199) so normal Bank A navigation can't wander into Bank B's reserved range either. A kit an older install had organically grown past 200 via Import Patch/Load Kit (those two have never had a slot cap) is still reachable through the Patch List's search, just not via `+`/`-` stepping.
- `deleteKit()` used to `removeAt(index)`, shifting every later kit down one slot — inside either reserved range that would have moved the *other* bank's kits to different absolute indices over time, eventually letting the two ranges drift back into each other. It now resets the slot to a blank placeholder **in place** for any index `<= BANK_B_KIT_END`, and only physically removes-and-shifts kits beyond that (organic overflow, which has no fixed-position guarantee to protect).
- `copyKit()` gained an `intoBankB` flag: a Bank B copy writes into the first still-blank slot inside Bank B's own reserved pool (`firstFreeBankBSlot()`) instead of appending past the end, which would have landed outside the range `currentKitB` is confined to. Bank A copy is unchanged (appends at the end) — safe from ever colliding with Bank B's range since `kits` never shrinks below 400 (see the `deleteKit()` fix above).
- `KitListScreen` (shared between both banks' patch-list overlay) gained a `visibleRange` param — Bank B's list now only shows and can only act on its own 200..399 range, never Bank A's; its "+ NEW KIT" action is likewise bank-aware (jumps to the next free Bank B slot instead of appending).

Known residual gap, considered out of scope for this fix: custom (imported/recorded/cropped) pad *audio* is tracked in `AudioRepository` purely by raw kit index + pad index, with no separate cleanup when `deleteKit()` blanks a slot in place — a slot's stale `AudioRepository` entries stay associated with that index until something overwrites them (e.g. a later copy/import into that same slot), which pre-dates this fix and isn't specific to Bank B.

Not verified on a physical device or an actual `./gradlew` build in this environment (no Android SDK/NDK available here — see the root `CLAUDE.md`'s standing note on that).

**User bug-report batch: multi-hit volume, Crop rework, Delay drop, Choke simplify, Bank B isolation, MIDI Note-only actions (2026-08-21)**

Eight issues reported directly by the client in Hindi, worked in the order given. Two required a scope decision from the client mid-session (asked via clarifying questions, not assumed) before implementing.

- **Multi-hit volume ducking**: `AudioEngine.cpp`'s mix-bus headroom scale used to be derived purely from `1/sqrt(activeVoiceCount)` — it ducked the *entire* mix the instant a second pad was hit, regardless of whether the two voices were anywhere near clipping (this was itself a fix from an earlier pass, for a genuine old clipping bug — but the fix over-corrected). Replaced with an actual peak-based limiter: measures the real peak of each mixed buffer and only reduces gain when that peak would exceed unity, so two moderately-loud simultaneous pads no longer duck each other at all; heavy stacking still gets the same asymmetric fast-attack/slow-release protection as before.
- **Crop editor**: three changes. (1) Re-encoding the cropped clip to AAC via `MediaCodec` (async dequeue/queue polling loop) on every APPLY was the source of "save karne ke baad latency aa jata hai" — replaced with a direct WAV writer (`writePcmToWav`, a 44-byte header + raw PCM copy, no codec involved; `minSdk 24` already reads WAV fine via `MediaExtractor`). (2) The APPLY/SAVE button is gone — after a client trade-off decision (auto-apply-with-safety vs. no-safety-at-all), edits now auto-commit ~1s after the on-screen selection stops changing (debounced `LaunchedEffect`), with RESET still available to bail out before that fires. (3) Added precise edge-grabbing: a single-finger drag starting near the existing start/end line now moves *only* that edge instead of always redefining the whole region from scratch, and a genuine two-finger touch landing on both edges at once moves each edge independently (forward or back) in one gesture — both layered into the same unified gesture recognizer so they still can't fight the existing pinch-zoom/pan handling.
- **Delay dropped on simultaneous multi-pad hits**: root cause was `onAudioReady` only calling `fireDelayTaps()` when the single global `delayEnabled_` flag was true — but that flag gets re-pushed synchronously before *every* pad's trigger (`syncDelayForHit()`), including a pad with no delay of its own. Hitting a delay-off pad shortly after a delay-on pad flipped `delayEnabled_` false and silently starved any already-scheduled echoes from ever firing. `fireDelayTaps()` is now called unconditionally every buffer (it's a cheap no-op when its queue is empty) — `delayEnabled_` still gates whether *new* taps get scheduled, just not whether already-queued ones get to fire. (The MASTER delay on/off switch requested separately already existed in `DelayPanel.kt`/`delayMasterEnabled` from an earlier pass — no change needed there.)
- **Choke simplified**: removed the EXCLUSIVE MODE toggle and ACTIVE LEVEL selector entirely — those were two extra gates on top of a pad's own choke-group membership, and choke did nothing until both happened to be set correctly (read as "choke kaam nahi kar raha"). `ChokePanel.kt` now shows just the None/1/2/3/4 per-level folders; choke groups are simply always live — any two pads sharing a non-NONE level always choke each other reciprocally, nothing else to turn on first. `OctapadScreen.kt`'s choke check in `onPadHit()` dropped the `exclusiveMode`/`activeChokeLevelState` gating accordingly.
- **Bank B**: two fixes plus one new entry point. Bank A and Bank B share one 200-slot kit pool — if both banks ever land on the same kit index, they're the literal same `Kit` object, so editing one edited the other ("A bank bigad jata hai"). A new `LaunchedEffect(currentKit, currentKitB)` now auto-nudges Bank B off Bank A's slot onto a neighbor whenever they collide (Bank A's own selection is never moved). Separately, Bank B previously only had `<`/`>` step buttons with no way to jump to a specific kit number — tapping the "B: kitname" label now opens the same `KitListScreen` used for Bank A, targeting Bank B's selection (`kitListTargetsBankB` state); this also exposed and fixed a latent bug where renaming a kit from that list wrote into `currentKit` (Bank A's selection) as a side effect — decoupled into its own `renameKitIndex`. Also fixed the Bank B kit-selector row not being accounted for in `RightPanel.kt`'s non-scrolling height budget, which could push the PATCH LIST nav row off the bottom of short screens whenever Bank B was active.
- **MIDI: button/action targets moved from CC to Note**. `CcMapRepository` used to also cover `PATCH_NEXT`/`PATCH_PREV`/`EDIT`/`SAVE`/`DELAY_TOGGLE`/`BANK_A`/`BANK_B`/`BANK_AB`/`PAD_1..PAD_8` — CC-learned, but the client's hardware sends a Note for these, not a CC, so LEARN never matched what was actually pressed. Only `VOLUME`/`PITCH`/`EQ_LOW`/`EQ_MID`/`EQ_HIGH` remain CC-based (continuous knobs genuinely need a CC's value stream). Everything else moved to a new `NoteMapRepository`/`NoteLearnState`, mirroring the CC-learn pattern but keyed by MIDI Note number — required a small native addition, `MidiProcessor::onRawNoteOn`, firing for every Note-On not consumed by pad-note MIDI Learn (regardless of whether that note is pad-mapped), bridged through `native-lib.cpp`/`NativeBridge.onRawNoteOnFromNative`/`MidiEventBus.onRawNoteOn`. `PAD_1..PAD_8` CC pad-triggering was removed outright (redundant — pad triggering was already, and remains, Note-based via the existing `MidiLearnRepository`/`MidiProcessor` path). `MidiLearnScreen.kt`'s list is now two sections: "KNOBS" (CC) and "ACTIONS" (Note, shows "Waiting for Note…" while listening). Both `CcMapRepository` and `NoteMapRepository` are included in Backup/Restore.

Not independently verified on a physical device or an actual `./gradlew` build in this environment (no Android SDK/NDK available here — see the root `CLAUDE.md`'s standing note on that); treat as reasoned-through and logically reviewed, not confirmed working, until built and tested on-device.

**Mobile responsiveness pass (2026-08-18)**

The app is locked to `sensorLandscape` (`AndroidManifest.xml`), so "responsive across mobile devices" here means adapting to varying landscape phone widths/heights, not a portrait layout — there is no portrait UI to fix. Full-codebase audit found `controlPanelWidth` (`OctapadScreen.kt`'s root `BoxWithConstraints`, ~20% of screen width) was the *only* screen-size-aware value in the whole app; every other fixed-dp size was tuned for one reference device. Fixed:

- **`DrumPad.kt`'s LED indicator bar could clip entirely on small screens** — its side padding was a fixed `50.dp` on each side, but a pad's actual width shrinks with `weight(1f)` in the 4-across grid; on a narrow enough screen that combined 100dp inset could exceed the pad's own width. Changed to `fillMaxWidth(0.5f)` (a fraction of the pad's own width, centered) so the LED bar scales down with the pad instead of being clipped by it.
- **Two dp-fixed modals weren't clamped to the available screen width**: the EDIT PAD popup's four option boxes (`OctapadScreen.kt`, was `width(200.dp)`) and `PadActionMenu.kt`'s Swap/Mix/Add-to-End menu (was `width(260.dp)`). Both now derive their width from `maxWidth` (via `BoxWithConstraints`), clamped to a sane range, so they can't overflow the smallest supported screen.
- **`RightPanel.kt`'s main column (deliberately non-scrolling — see its own comment on why) had no defense against short-height screens** beyond spacing constants tuned for one device. Wrapped it in `BoxWithConstraints` and scaled its Spacer heights, padding, and the two VOL/PITCH `LinearSlider` track heights by `(maxHeight / 380.dp).coerceIn(0.72f, 1f)` — the non-scrolling design is unchanged, but the same spacing "budget" now compresses proportionally on shorter screens instead of only being correct for whichever device it was last tuned against.
- **Touch targets**: Bank B's `<`/`>` kit-nav buttons were `24.dp` (smaller than the main PATCH LIST nav's `32.dp` two rows below) — bumped to match at `32.dp`. The EQ/Choke/Delay/Tempo panel close (✕) buttons were `22.dp` — bumped to `28.dp`. Left the 8-across CHOKE level pad-grid buttons (`24.dp`) and the DLY/EDIT MODE round toggles (`30.dp`) alone — both are already width-constrained by 8-across or side-by-side layouts on an already-narrow control column, and enlarging them further isn't possible without either shrinking something else or widening the whole panel at the pad grid's expense.
- **Three simple confirmation dialogs had no scroll fallback** (`BackupScreen.kt`, `PatchExportScreen.kt`, `ImportPatchScreen.kt`) — content is normally short enough not to need it, but added `verticalScroll` to each as a safety net against the shortest supported screens, matching the pattern every other overlay screen in the app already uses.

Verified with a real `./gradlew :app:assembleDebug` (SDK/NDK were available in this environment for this pass, unlike earlier passes) — compiles and packages cleanly. Not verified on a physical device or emulator at multiple actual screen sizes; if any of the above still clips or looks off on a specific device, get its exact screen dimensions before assuming the scaling math above is wrong.

**Third pass — Bank C removal, Delay/Choke redesign, real-time audio + MIDI fixes, post-implementation audit (2026-08-18)**

A large user-driven pass (bug reports + feature requests) followed immediately by an independent full-codebase audit (6 parallel finder agents) that caught real regressions in the same session's own changes before they'd been separately verified. Grouped by area:

*Bank / Choke / Delay redesign:*
- **Bank C removed entirely** — UI, `bankKitIdx()`, `nativeSlotsFor()`, choke/fire logic. It was pushing `RightPanel.kt`'s patch-list nav row off the bottom of the (non-scrolling) panel whenever active. Native `AudioEngine` still reserves 24 pad slots; 16-23 are just unused now (not worth a native slot-layout rewrite for a UI-level removal). `PreferencesRepository`'s `loadKitC()/saveKitC()` and the backup `kitC` field are kept, unread, so old exported backups still parse.
- **Choke redesigned**: 6 levels → None/1/2/3/4; a pad now belongs to at most one level (dropdown-style) instead of a multi-select set; the level-assignment UI is now 4 collapsible per-level folders instead of one long list. **Audit caught a real data-loss bug in the first implementation**: the "deselect" path called `groups.clear()` instead of `groups.remove(level)` — for a kit saved *before* this redesign (which could have a pad in multiple legacy levels, including 5/6 which have no UI anymore), toggling off the one level shown in the new UI silently wiped every other legacy level too. Fixed to `remove()` before shipping.
- **Delay moved out of FX into its own `DelayPanel.kt`**, with a new global MASTER kill switch (`delayMasterEnabled`) layered on top of the existing per-pad-per-kit `padDelayEnabled` flag — MASTER off mutes delay everywhere without touching any pad's own setting. Also fixed the actual reported bug ("first hit has no delay, works after the 2nd hit on the same pad") — delay's native params were only synced by a reactive `LaunchedEffect` that lagged a frame behind the synchronous trigger. **Audit caught a regression the first fix introduced**: moving that sync inside `fire()` (called both for the initial hit and every loop auto-retrigger) meant an already-looping pad's retrigger would re-clobber the single global native delay state with its own settings, stomping whatever a different pad's hit had just set. Fixed by syncing once before the initial `fire()` call only, never inside `fire()` itself.

*Real-time audio engine (`AudioEngine.cpp`/`.h`):*
- **Multi-hit crackle ("kich khich") on fast multi-pad playing — two attempts, same day.** Every freshly-claimed voice started at full amplitude on frame 0 (rarely a zero-crossing sample), a real click/pop source when it happened while other voices were already sounding. **v1**: added a ~3ms attack fade-in (`attackGain`, mirrors the existing `releaseGain` fade-out) unconditionally at every voice-claim site (`triggerPad`'s normal + pool-exhausted-steal paths, `fireDelayTaps`'s echo claim). A user report on the resulting build found audible crackle on *every single pad tap* instead — including a lone hit starting from silence, which had nothing to fade against in the first place — a regression, not a fix. Reverted entirely (no physical device was available in this environment to diagnose further; verification up to that point was compile success + math/logic tracing, not an actual listen). **v2**: reintroduced narrower — `triggerPad`/`fireDelayTaps` now scan `voices_` for any other currently-`ready` voice at claim time and only arm the fade (`attackGain = 0.0f`) when one exists; a voice claimed into silence is set straight to `1.0f`, a no-op in the mixing loop, restoring exact pre-fade behavior for that case. Also hoisted the attack/release ramp step-size computation out of the per-voice loop as a small real-time-thread efficiency fix (unaffected by the revert/reintroduction). v2 is, again, reasoned through rather than verified on a physical device — if either symptom (multi-hit crackle still present, or a new single-tap regression) is reported after this build, don't assume the conditional logic is correct just because it reads correctly.

*MIDI / knob responsiveness:*
- **"Volume/pitch knob gets stuck after testing for a while, via MIDI and on-screen both"** — two independent root causes, both fixed. (1) `LinearSlider`'s (VOL/PITCH/EQ/DLY TIME/DLY LEVEL/PAN/GAIN — every continuous knob) touch-gesture handler was a bare `awaitPointerEventScope { while(true) {...} }` never wrapped in `awaitEachGesture`; since every slider lives inside a `verticalScroll` panel, losing gesture arbitration to that ancestor scrollable (ordinary on a diagonal drag) threw an uncaught cancellation out of the handler's coroutine, permanently killing that slider until the screen reopened. (2) `persistKits()` (synchronous JSON-serializes up to 200 kits) was called on *every single* CC tick / drag-frame from every continuous knob — a fast MIDI sweep can send dozens of CC messages/sec, and stacking that serialize+disk-write on the MIDI receive thread on every one of them made the thread fall behind the longer a sweep continued. Added `persistKitsDebounced()` (300ms debounce) at every continuous-drag call site; one-shot actions (rename, swap, delete, SAVE, toggles) still persist immediately.
- Removed `Log.d` calls that ran on every single MIDI message (Note On/Off/CC in `MidiReceiver.kt`) and every pad hit (`onPadHit`'s `"PADHIT"` log) — real per-message syscall cost, no shipped-build benefit (ProGuard/R8 disabled, nothing strips debug logging).
- Added MIDI-Learn CC targets: `DELAY_TOGGLE`, `BANK_A`, `BANK_B`, `BANK_AB`.

*Crop editor (`WaveformEditorScreen.kt`):*
- **Accidental-touch data loss**: the editor used to autosave (re-encode + overwrite the original sample) the instant any drag gesture on the waveform canvas ended, with zero confirmation — a single stray touch permanently destroyed the original. Dragging now only updates the in-memory selection; nothing is written until an explicit APPLY button is tapped. Added a PREVIEW button that plays the current unsaved selection straight from in-memory PCM via `AudioTrack` (no file write) so it can be heard before committing.

*Loop / tempo:*
- **PLAY MODE = LOOP ignored the SPEED knob entirely** (always retriggered at the sample's raw length) — the most common case someone would test SPEED against. Added a separate `loopModeIntervalMs = durationToShow / speed` used only for true LOOP mode; the global Loop toggle's BPM-gated `beatIntervalMs` is deliberately still not applied to LOOP mode (would reintroduce an earlier "silence gap" bug). Also fixed a BPM precision-loss bug: `beatIntervalMs` divided `60_000L / bpm` as `Long` before the SPEED division ever ran, truncating early and compounding rounding error.

*UI polish:*
- Kit-rename dialog's SAVE button was reachable only below the on-screen keyboard (which could cover it) — added a second SAVE action in the dialog's title row, always visible regardless of keyboard height.
- Swap/Mix/Add-to-Last (2-finger pad gesture) used to open instantly on any 2-finger drag/tap that crossed onto a different pad. Gated on a hold, in two attempts: v1 used a 3.5s timer that also correctly delayed the *menu*, but still showed the floating drag/drop preview immediately on 2-finger touch (`dragVisible = true` was set at press-time, not after the delay) — reported as "hold isn't doing anything" since the visible drag interaction started instantly regardless. v2 (same day): shortened to 2s and moved `dragVisible = true` inside the delayed coroutine alongside `holdArmed`, so neither the preview nor the menu appear at all until the full 2s has elapsed; releasing early cancels silently.
- Display-cutout (notch) handling was entirely absent for this fullscreen-immersive landscape app, left to inconsistent per-OEM defaults ("full screen on some phones, half on others"). Added `windowLayoutInDisplayCutoutMode="shortEdges"` (API 27+ overlay) plus `windowInsetsPadding(WindowInsets.displayCutout)` on the root layout so `controlPanelWidth`/pad-grid sizing is computed from the real safe area on every device.
- **`RightPanel.kt`'s side-panel-closing logic deduplicated**: the same 5-line "close every other panel" block was repeated across 6+ call sites; every new panel (DELAY was the latest) meant hunting down and editing all of them. Extracted `closeAllPanels()`. **Audit caught a live instance of exactly this failure mode**: the DELAY icon's long-press handler opened the panel correctly but never set `activeBtn = "DELAY"` (every other panel-opening path did) — the CROP/CHOKE/DELAY button row kept highlighting whichever button was tapped last instead of DELAY.

**Flagged, not changed** (audit found but needs a product decision, not a bug fix): `ActivationScreen.kt`'s advanced "SERVER URL" field was removed at some point outside this session, leaving no in-app way to point at a different admin-panel server if the hardcoded fallback URL is ever wrong for a given deployment — worth confirming this was intentional.

**Second full system-wide audit (previous pass, 2026-08-14)**

A follow-up, independent audit (four parallel passes: native audio engine/JNI, Kotlin UI state/pad-hit hot path, persistence/backup/MIDI repos, admin panel API/license flow) specifically re-checking whether the previous pass's fixes were actually complete. Real bugs found and fixed:

- **MIDI-triggered hits were pinned to whichever bank was active at app launch — the bank-routing fix never actually reached MIDI.** The previous pass's `activeBankKit` fix was a plain `val` (a one-time snapshot), which is fine for on-screen knob/slider lambdas (recreated every recomposition) but not for `MidiEventBus.onPadHit`/`onControlChange`, which are installed inside a `LaunchedEffect(Unit)` that runs exactly once for the whole composable's lifetime. That one-time closure captured whichever bank was selected at first composition (typically Bank A) forever — switching banks afterward had zero effect on anything triggered via MIDI note/CC (volume, pitch, EQ, play-mode/loop checks), only on touch. Fixed by turning `activeBankKit` into a function, `bankKitIdx()`: `bankMode`/`currentKit`/`currentKitB`/`currentKitC` are `by remember { mutableStateOf(...) }` delegates, so referencing them inside a function body always reads the current value at call time, even from a closure that was itself created once and never rebuilt.
- **Pad drag-Swap, Mix, Add-To-End, one-tap Import, the waveform crop Editor, Clear Sound, and mic Recording were still hard-wired to `kits[currentKit]` (Bank A) and native slots 0-7**, unlike the FX knobs/choke grid the previous pass fixed. Doing any of these while Bank B or C was selected silently mutated Bank A's inaudible kit (and, for swap, only reloaded Bank A's native buffers) instead of the bank actually being played — reads as "swap/import/crop/record does nothing." Routed all of them through `bankKitIdx()` for kit reads/writes and `nativeSlotsFor()` for native reload, same pattern as the earlier fix.
- **LCD kit-name label always showed Bank A's name**, even while the VOL/PITCH sliders directly below it were correctly editing Bank B/C's kit — a real (if minor) display/state mismatch. `RightPanel` now takes a `bankKitIdx` param from `OctapadScreen` instead of deriving the label from the raw `currentKit`.
- **MIDI CC-learn collision fix only covered CCs that had been explicitly learned, not the two hard-coded defaults (VOLUME=11, PITCH=12).** Learning PITCH onto CC 11 (VOLUME's default, never explicitly re-learned) left `getCc("VOLUME")` still returning 11 — both targets matched the same CC, and the dispatcher's `when` (checks VOLUME first) kept firing VOLUME, so PITCH showed as "learned" but silently never fired. Fixed by comparing against `getCc()` (which already applies the default) and persisting an explicit `-1` (not `remove()`, which was a no-op for a key that was never present) so a default-backed collision is actually recorded as unmapped.
- **`CcMapRepository`/`MidiLearnRepository`'s `importBackup()` skipped the validation `KitRepository`'s got in the previous pass** — both wrote the backup's raw JSON straight into SharedPreferences unchecked; a truncated/corrupted zip entry got written verbatim, and the next read's `JSONException` fallback silently wiped every CC/note mapping. Both now validate (parse-check) before writing, throwing instead.
- **Backup restore wasn't atomic across repositories.** `KitRepository.importBackup()` validates before writing (fixed last pass) and commits to disk immediately — if a *later* repository's payload (preferences, CC map, MIDI map) then failed to parse, the restore aborted with kits already replaced by the backup while the others silently stayed on their old values: a hybrid, half-restored state, not the "leaves existing data untouched on failure" guarantee the docs claim. Split every repository's `importBackup()` into a validate-only pass (`validateBackupPayload()`, throws without writing) and a commit pass; `BackupScreen.restoreBackup()` now validates all four payloads up front before committing any of them. `PreferencesRepository.importBackup()` itself had the same non-atomic problem internally (`save*()` calls fired as each field was read, so one malformed field left earlier fields persisted and later ones not) — fixed the same way, parse everything into a holder first, write only after all of it parses clean.
- **Native: a corrupt import reporting `sampleRate == 0` (or a pitch of exactly 0, reachable via the live pitch knob) permanently leaked a voice.** `onAudioReady` computes playback rate as `pitch * (buf.sampleRate / outputSampleRate_)`; either being 0 makes the voice's position never advance, so its end-of-sample check never trips — it plays frame 0 forever and never releases its slot from the shared 64-voice pool. `loadPadBuffer` now rejects `sampleRate <= 0` outright (keeping whatever the pad had before); `triggerPad` and `setPadPitch` both clamp pitch to a safe nonzero minimum magnitude.
- **Native: the 64-voice pool had no stealing — once exhausted, new hits were silently dropped** (reachable with MIX/MULTIPLAY layering across 3 banks, more so if voices were already leaking per the bug above). `triggerPad` now falls back to stealing the oldest currently-playing voice (tracked via a monotonic `claimSeq` per voice) instead of losing the hit.
- **Native: `triggerPad`'s buffer-loaded check and stop-then-claim sequence, and `fireDelayTaps`'s buffer-loaded check, read `buffers_[padIndex]` without holding `bufferMutex_`**, the same mutex `loadPadBuffer` writes under and `onAudioReady` reads under every callback — a genuine data race (undefined behavior), and separately meant two concurrent `triggerPad` calls for the same pad (e.g. a duplicate note+CC mapping firing together) could both independently pass the stop-check and claim distinct voices, producing overlapping voices for a pad whose `stopExisting=true` (ONE SHOT) semantics promise a single-voice retrigger. Both now take `bufferMutex_` around the buffer check and voice claim/steal; scoped carefully (bufferMutex_ released before delayMutex_ is taken) to avoid a lock-order inversion against `fireDelayTaps`, which is called from the audio thread with the opposite nesting.
- **Admin panel: a failed MongoDB connection attempt permanently wedged every `/api/*` route.** `connectToDatabase()` cached the connection *promise* before awaiting it; if `mongoose.connect()` ever rejected (an Atlas hiccup, IP-allowlist blip, DNS timeout), the rejected promise stayed cached forever — every subsequent call (including the public `/api/app/*` routes the Android app depends on) re-awaited the same dead promise and failed immediately, with no retry, until the Node process restarted (which, on Vercel, means until a redeploy, since the module scope persists across invocations). Now resets the cached promise on failure so the next call gets a fresh connection attempt.
- **Admin panel: `/api/app/status` updated `lastCheckInAt` via non-atomic `findOne` + mutate + `save()`**, unlike `/api/app/redeem`'s already-correct atomic `findOneAndUpdate`. Two concurrent status pings for the same device could lose one's timestamp update. Switched to `updateOne` with `$set`.

**Verified correct / no regression found** (re-checked, not just assumed): the previous pass's `redeem` atomicity, `requireAdmin` coverage on every admin route, absence of NoSQL injection or further env-var typos, `LicenseApi.kt`'s connection cleanup and timeouts, request/response shape parity between `LicenseApi.kt` and `/api/app/*`; `DrumPad.kt`'s single unified gesture handler; `onPadHit()`'s loop-staleness live re-reads; the on-screen FX knob wiring and multi-bank choke check.

**Full system-wide audit (previous pass, 2026-08-14)**

A parallel, per-subsystem audit of the entire Android app (native audio engine, pad-hit hot path, kit/bank persistence, audio import/edit pipeline, MIDI, licensing/activation, UI/gesture handling) plus the admin panel's app-facing API. Real, confirmed bugs found and fixed:

- **Bank B/C controls beyond Volume/Pitch silently edited Bank A instead.** A prior pass fixed this for the VOL/PITCH sliders only. Every other live per-pad control — PLAY MODE, loop duration/URI lookups, EQ, Delay, Length, Reverse, Pan, Gain, the choke grid, and their MIDI CC handlers — still read/wrote `kits[currentKit]` (Bank A) and native slots 0-7 unconditionally. Fixed by routing all of them through the existing `activeBankKit`/`nativeSlotsFor()` helpers, same pattern as the earlier VOL/PITCH fix.
- **Choke groups silently did nothing unless Bank A was part of the active bank combination.** The exclusive-mode/choke check in `onPadHit()` only consulted Bank A's choke level and group membership — a pure Bank B, Bank C, or B+C session never choked. Rewrote to check every currently-active bank's own choke level and group config.
- **A malformed/truncated Backup zip could silently wipe all 200 kit slots.** `KitRepository.importBackup()` wrote the backup's raw JSON straight into SharedPreferences with no validation; a bad restore corrupted the saved kit list, and the *next* app load then hit its parse-failure fallback (`emptyList()`), erasing all kits. Now validates every entry before writing anything, throws instead, and leaves existing data untouched on failure. Both `BackupScreen.kt` and `ImportPatchScreen.kt` also now clean up already-extracted audio files if validation fails afterward, instead of leaving them orphaned on disk.
- **`MediaExtractor` leak in `WaveformGenerator`** on any decode failure (malformed import/factory resource) — the inner codec was already wrapped in try/finally, the extractor wasn't. Fixed.
- **Orphaned temp `.mp4` file** if video-audio extraction (`AudioTrimmer`) failed partway through. Fixed — the partial file is now deleted on the failure path.
- **Native division-by-zero risk** in `native-lib.cpp`'s `loadPadAudio` JNI entry point if a corrupt imported file causes the decoder to report `channels == 0`. Guarded.
- **Admin panel's entire `/api/app/*` activation loop was broken by an env-var typo**: `admin-panel/src/lib/mongodb.ts` read `MONGODB_URI_MONGODB_URI` instead of the documented `MONGODB_URI` — following the project's own setup instructions would always fail to connect, breaking signup/redeem/status on any fresh deploy. Fixed.
- **`HttpURLConnection` leak on error paths** in `LicenseApi.kt` — `disconnect()` only ran after a successful response read; now wrapped in try/finally so a network failure mid-request still releases the connection.
- **MIDI device-open NPE risk** in `MidiManagerHelper.kt` — the `OnDeviceOpenedListener` callback's `device` param is nullable on open failure and was dereferenced unconditionally. Null-checked.
- **Silent MIDI CC-mapping collisions**: learning a new CC for one target didn't clear that CC from whatever target previously held it, so a colliding mapping showed as "learned" in the UI but never actually fired (the dispatcher only checks the first matching target). Fixed to clear the CC from any other target on save.
- **Stuck MIDI "learn" mode**: `CcLearnState.listeningForTarget` was only cleared by explicit Cancel/X taps, not on every way the Learn screen could close (e.g. tapping DONE mid-listen) — since the CC handler stays globally registered, this could silently hijack the next CC message sent anywhere in the app. Now cleared unconditionally via `DisposableEffect.onDispose`.
- **Removed an unconditional per-pad-hit `Log.d`** (with string-template allocation) that ran on the documented-critical synchronous hot path in `OctapadScreen.onPadHit()`, on every single hit, not just MIDI ones.

**Documentation corrections** (no code change, doc was simply wrong): `app/CLAUDE.md` and this file both claimed MIDI note-based pad mapping "was removed entirely" in favor of CC-only mapping. It was not — `MidiLearnRepository`, `MidiProcessor.cpp`'s note→pad map, and the full `enableMidiLearn`/`assignMidiNote`/`onPadHit` JNI chain are live and reachable (`MidiLearnScreen.kt`'s "PAD NOTES" section). Both mechanisms are independent and both work; docs corrected to say so — see §1.3 above.

**Verified correct / no regression found** (i.e. explicitly checked, not just assumed): native voice active/ready ordering and memory ordering, delay-tap sample-rate sourcing, `stopPad()`'s per-pad fade/multi-voice stop, JNI string/array hygiene, PCM buffer lifecycle on pad reload, `PcmDecoder`/`PcmMixer`/`WaveformEditorScreen`/`PadRecorder` try/finally coverage and factory-audio fallback, waveform-editor gesture state machine, `applyEdits()`'s non-boxed PCM stitching, kit-name text overflow across all kit-list/bank-selector composables, drag-swap field completeness, pad LED release timing across all 8 pad files, responsive panel sizing, `DeviceId`/`LicenseRepository` grace-period timekeeping, backup/restore exclusion of `license_prefs`, server-side atomic `findOneAndUpdate` in `/api/app/redeem`, MIDI channel filtering, MIDI Learn keyboard-fallback focus suspension, and MIDI paywall re-gating on remote revoke.

**LOOP panel reorg + Choke/Loop correctness pass**
- **LOOP panel** (`TempoPanel.kt`): removed the TOTAL beat counter and the METRONOME toggle entirely — UI, state (`totalBeats`, `metronomeOn`), the metronome click-generation coroutine (`ToneGenerator`), and `PreferencesRepository` persistence (including backup/restore) are all gone, not just hidden. Added a CHOKE quick-toggle in their place, wired to the same `exclusiveMode` state the dedicated CHOKE panel (`ChokePanel.kt`, next to CROP) uses — turning it on/off from either panel stays in sync; detailed per-level pad-group assignment still only lives in the CHOKE panel.
- **CHOKE button auto-enables Exclusive Mode**: tapping CHOKE (next to CROP) now also turns Exclusive Mode on, so the level/group picker (which was gated behind it) is usable immediately instead of requiring a separate manual toggle tap first.
- **Choke cutoff verified instant**: native `AudioEngine::stopPad()` already uses a ~5ms linear fade-to-silence (not a hard cut) specifically to avoid an audible click on every choke — confirmed this is imperceptibly fast in practice and correctly kills *every* voice tagged with a pad's index, including all layered MULTIPLAY voices, not just one.
- **Loop logic: two real staleness bugs fixed.** `onPadHit()`'s loop/retrigger decision used to read the global LOOP toggle and the pad's PLAY MODE from values captured once at hit-time into `val`s, even though a loop can run for many iterations/seconds inside its coroutine. (1) Turning the global LOOP toggle off while a pad was mid-loop had no effect on that already-running loop — it kept going until the pad was re-hit. (2) Switching a pad's PLAY MODE away from LOOP in the LOOP panel while it was actively looping was equally ignored. Both are now re-read live on every check.
- **Loop reaction latency fixed.** The wait-between-retriggers loop used to always sleep out the full tempo-synced `waitWindowMs` before ever re-checking whether it should still be looping — at a slow BPM this could mean several seconds of continuing to loop/wait after the toggle was flipped off before it actually noticed. It now breaks early the instant the pad's loop token is superseded (re-hit or choked by another pad), and finishes out just the sample's own natural duration (instead of the extra beat-sync padding) the instant effective-looping turns false mid-wait.
- **Per-pad PLAY MODE = LOOP now has an actual stop gesture.** LOOP mode is a true infinite hold with no natural end, and previously the *only* way to silence one was switching its PLAY MODE away from LOOP in the LOOP panel — hitting the pad again just restarted the same infinite loop from zero, which read as "loop mode never turns off." A second tap on an already-looping LOOP-mode pad now stops it instead (tracked via a new `loopModeActive` map in `OctapadScreen.kt`).

**Crop editor: gesture-conflict rebuild (latest pass)**

The previous pass's gesture unification (below) made Crop and Delete use the same *drag-select* code path, but left a second, deeper bug in place: `WaveformEditorCanvas` had **two independent `pointerInput` gesture detectors stacked on the same Canvas** — `detectTransformGestures` for pinch/pan, and a separate detector for the region drag — both receiving and reacting to the *same raw touch stream* at the same time.

- **Root cause.** `detectTransformGestures`'s callback fires for *any* pointer count, not just two — a plain one-finger drag reports `zoom = 1f` but a nonzero `pan`, so a single-finger crop/delete drag was simultaneously being read as a pan/scroll by the other detector. The reverse was just as broken: during a genuine two-finger pinch, the region detector's "first pointer" tracking kept moving the crop/delete boundary too. Two recognizers independently interpreting one touch stream is what produced every symptom reported — an "unnatural" feeling drag, broken two-finger zoom/pan, visible latency (competing state writes and recompositions each frame), and a crop that "didn't apply cleanly" because the boundary could get silently corrupted by pan math mid-pinch, or vice versa.
- **Fix: one gesture recognizer, not two.** Rewrote the Canvas's touch handling from scratch using `awaitEachGesture`/`awaitFirstDown` (replacing `detectTransformGestures`) with an explicit state machine (`CanvasGesture.NONE/REGION/TRANSFORM`) that decides per-gesture, from the live pointer count, whether this is a region drag (1 finger) or pinch/pan (2+ fingers) — and once it commits to one, never evaluates the other against the same events. A 2nd finger joining mid single-finger drag cleanly hands off to pinch (the partial region edit just isn't committed); a finger lifting mid-pinch ends the gesture rather than snapping the remaining finger into a fresh region drag.
- **Commit-on-release, not a timer.** The previous pass's 250ms debounce is gone. `WaveformEditorCanvas` now calls `onRegionCommit()` exactly once, exactly when a region-drag gesture ends (all fingers lift) — `WaveformEditorScreen`'s autosave `LaunchedEffect` is keyed on a `commitTick` counter bumped by that callback, not on `cropStartMs`/`cropEndMs` value changes. Live-dragging a region only ever updates cheap local state; the encode+persist runs once, immediately, with zero artificial delay.
- **Coordinate math de-duplicated.** The pinch handler used to have its own inline copy of the px↔time-fraction math; both it and the region-drag handler now go through shared `pxToFrac`/`fracToPx` (and `msToCanvasPx`/`canvasPxToMs` built on top of them), so gesture math and what's actually drawn can't drift apart.
- **Removed a redundant per-frame state write**: `canvasWidthPx` was being set both from `onGloballyPositioned` (correct — only fires on real layout changes) *and* unconditionally on every single Canvas draw call. The draw-time write served no purpose and was deleted.
- **Added bounded, cancellable inertia** on pan release (a fast 2-finger pan now glides and decelerates instead of stopping dead), implemented as a simple frame-synced exponential decay (`withFrameNanos`), clamped to valid scroll bounds, and killed instantly by the next touch — no dependency on `VelocityTracker`, just a per-event scroll-delta/time estimate that zeroes out on a still frame so a deliberate pause-then-release can't produce a phantom fling.
- Also removed two now-unnecessary `remember`ed state vars (`dragStartPx`/`dragEndPx` used to live at the Canvas composable level; they're now plain locals scoped to a single gesture, since that's their entire actual lifetime) and a pre-existing unused import (`kotlin.math.roundToInt`).
- `applyEdits()`'s exact-range sample copy (see perf entry below) was re-verified during this pass and is unaffected — the "leftover audio" complaints traced entirely to the gesture-conflict bug above, not to the encode math.

**Crop editor: autosave + perf (latest pass)**
- Removed the explicit "SAVE EDITS" button in `WaveformEditorScreen.kt` — crop/delete-region edits now autosave via a `LaunchedEffect` debounced ~250ms after the last change to `cropStartMs`/`cropEndMs`/`deleteStartMs`/`deleteEndMs`. Dragging a handle only updates local UI state; the actual encode+persist (MediaCodec AAC encode, `AudioRepository.assignRecordedAudio`, `DrumEngine.invalidatePad`/`loadPad`) fires once after the user pauses/releases, not on every drag frame. RESET now also autosaves (persists the reset back to the pad) since there's no other way to commit it once the Save button is gone.
- Fixed a real perf bug in `applyEdits()`: the crop/delete stitching step built a `MutableList<Short>`, boxing every PCM sample (hundreds of thousands+ on a multi-second clip) — this was the actual bottleneck behind any crop-save latency, and became more load-bearing once saves fire automatically on every edit instead of one deliberate button tap. Replaced with a preallocated `ShortArray` + `System.arraycopy`.
- **Crop gesture unified with Delete region's.** Crop used to work by snapping the first touch to whichever existing edge (start/end handle) was pixel-closer, then only that one edge tracked the drag — so a single gesture could never redefine the whole range, and the untouched edge silently stayed wherever it was from a previous edit or the full-length default. That's a real "incomplete crop" bug: the result could keep unintended audio outside the region the user thought they'd just selected. Crop now uses the exact same drag-to-select interaction Delete region already used — touch down anywhere starts a brand-new region at that point, drag defines the other edge live, both edges are always fully replaced by one continuous drag. `WaveformEditorCanvas`'s two separate `onCropStartChange`/`onCropEndChange` callbacks were collapsed into one `onCropRegion(start, end)` to match.
- Two-finger pinch-to-zoom (`detectTransformGestures`) on the waveform is unaffected by this pass.

**UI reorg, new FX, CC-only MIDI, audio-quality fix (latest pass)**
- Right-panel controls reorganized into three groups: Main display (`RightPanel.kt` — EDIT/SAVE/LOAD, Volume/Pitch, bank A/B/C, Patch List, kit name), FX (`EQPanel.kt`, renamed header — EQ, Delay incl. the old standalone DelayPanel's controls now merged in, Choke, Reverse, Pan, Gain, Exclusive/Velocity, Recording, one-tap pad import), LOOP (`TempoPanel.kt`, renamed header — BPM, new SPEED multiplier, Loop/Metronome, per-pad PLAY MODE moved here with MIX relabeled MULTIPLAY)
- New per-pad FX: **Pan** (-1..1, equal-power law) and **Gain** (0-200% trim) — new native `Voice` fields, full JNI/Kotlin/persistence plumbing, knobs in the FX panel
- **Audio quality on multi-hit fixed**: voice mixing previously had no headroom compensation and a hard (not actually soft) clip — stacking simultaneous voices clipped and distorted. Now scales the mix by `1/sqrt(activeVoiceCount)` before a real `tanhf()` soft-knee clip.
- **MIDI pad mapping is now CC-only** — note-based mapping (GM defaults, `MidiLearnRepository`, native `MidiProcessor` note→pad map) removed entirely; a controller's pads are mapped only via `CcMapRepository`'s `PAD_1..PAD_8` CC targets, same MIDI Learn flow as every other target. No default CC-to-pad mappings exist, so each pad needs a one-time MIDI Learn.
- **Pad LED now turns off the instant a finger lifts**, not when the sample finishes playing (`DrumPad.kt` `onRelease` callback) — the audio trigger was already synchronous; this was a pure visual lag.
- Pad grid spacing tightened (8dp → 3dp) for a denser layout
- All 200 kit slots now exist from first launch (25 factory + 175 pre-populated blank), not just the ones explicitly created via Copy
- One-tap "IMPORT TO THIS PAD" in the FX panel — assigns a picked file straight to the selected pad, skipping the Import screen → Audios screen → pad-picker detour (that detour still exists for bulk import from SETTINGS)
- One-time "follow us" (YouTube/Instagram/WhatsApp) screen on first launch, right after the splash — placeholder URLs in `SplashScreen.kt`, need real links before shipping
- Bug fixes: admin panel `/api/app/redeem` race condition (atomic `findOneAndUpdate` instead of read-then-write) that could let two devices redeem the same code; pad drag-swap now moves ALL per-pad settings (choke groups, EQ, delay, length, reverse, play mode), not just volume/pitch/sound; delay-tap timing no longer assumes a hardcoded 48000Hz sample rate (`NativeBridge.getSampleRateNative()`); delay echoes no longer inherit a stale LENGTH-trim value from whatever voice slot they reuse

**Licensing / activation (client-side, new this pass)**
- Built the full activation loop on the Android side: `DeviceId`, `LicenseApi`, `LicenseRepository` (with offline grace period), `ActivationScreen`, `MidiPaywallScreen`
- Wired into `MainActivity`/`OctapadScreen`: blocks entry until activated, re-checks status every 30 minutes while open
- Added `INTERNET`/`ACCESS_NETWORK_STATE` permissions (previously missing — app couldn't reach a network at all)
- Excluded the license state from Android's OS-level auto-backup so it can't leak across physical devices

**Latency**
- Removed a silent ~16ms-per-hit delay caused by wrapping the actual trigger call in a coroutine launch
- Oboe stream: Exclusive/MMAP mode, 1-burst buffer size, no longer forces a sample rate (avoids a potential internal resampler)

**Real bugs found and fixed** (see §1.6 for detail on each)
- Native voice-publish race condition (root cause of multi-hit stutter / double-tone playback)
- DLY TIME knob not actually updating the running delay effect
- Per-pad LOOP mode incorrectly tempo-gated
- Factory samples using a wrong hardcoded duration guess (loop/tone-cut symptoms)
- Mix / Add-To-End / waveform crop editor not working on factory (non-custom) kit sounds
- Unreliable pad drag-to-swap detection
- Conflicting/uncoordinated touch gesture handlers on every pad
- MediaCodec/MediaExtractor/MediaMuxer resource leaks across 5 files
- Crash risks in MIDI device setup and mic recording
- Wrong factory sound loading after unassigning a custom sound in the Sound Library
- Text overflow and a real off-panel-button layout bug

**Features added**
- MIDI Channel Select (1–16 / ALL)
- MIDI CC-based pad triggering (for controllers that send CC instead of Note-On)
- Direct/inline kit rename from the Patch List
- Branded splash screen
- Responsive control-panel sizing across screen sizes
- Volume range extended to 0–200%
- Kit Copy, Bank C, per-pad Length trim, full Backup/Restore
- Patch List force-closes side panels before opening, so it can never be hidden behind one

**Admin panel**
- Built from scratch: Next.js + MongoDB, single-password auth, license CRUD dashboard, signups tab, public `/api/app/*` endpoints for the app to call
