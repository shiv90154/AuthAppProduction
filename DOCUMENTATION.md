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
- Per-pad play mode: ONE SHOT / LOOP (immediate back-to-back retrigger, independent of BPM) / MIX (layers repeated hits instead of cutting off)
- Choke groups: 6 independent levels, any pad can belong to any subset of levels; one "active" level at a time silences every other pad sharing it
- 2-finger drag to Swap / Mix (merges two pads' audio into one) / Add-to-End (concatenates two pads) — all three work on **factory kit sounds**, not just custom-imported audio
- Waveform crop editor (`WaveformEditorScreen.kt`) — a single pointer-count-aware gesture recognizer: one finger drags out the kept (crop) or cut (delete) region, two-plus fingers pinch-zoom/pan with inertia on release; the two never overlap or fight over the same touch. Also works on factory kit sounds now, not just custom audio. Edits autosave the instant the gesture ends (finger lift) — no separate "Save" button, no artificial delay

**Audio**
- WAV/MP3/OGG/M4A/FLAC import, or audio extracted from a video file
- Per-pad Volume (0–200%), Pitch, 3-band EQ (Low/Mid/High), Delay (per-pad time, live-adjustable — the DLY TIME knob actually updates the running engine now, not just on the next pad switch), **Length** (trims how much of the sample plays, independent of pitch)
- Internal mic recording straight onto a pad, with proper failure handling if the mic is busy with another app (shows a toast instead of silently doing nothing or crashing)
- LOOP panel: BPM + SPEED multiplier, global LOOP toggle, and a CHOKE quick-toggle (shortcut to the same Exclusive Mode state the dedicated CHOKE panel uses). No metronome click or beat counter — both were removed entirely.

**Banks**
- **A / B / C banks** — each is its own kit slot loaded in parallel (24 native audio slots total: 0–7 = A, 8–15 = B, 16–23 = C). Any combination of A/B/C can be toggled on at once; every active bank's sound layers together on a pad hit.

**Kit system**
- Up to 200 kits, 25 factory kits pre-loaded (200 WAV files bundled in `res/raw`)
- Save / Load / Rename (including **direct inline rename right from the Patch List** — no detour through Settings) / **Copy** (duplicates all settings + custom audio) / Delete
- Patch List screen, kit name shown on the LCD readout, both with proper text truncation so a long name can't overflow or push nav buttons off-panel
- "Load Kit" screen — build a new kit directly from 8 files picked from the file manager

**MIDI**
- USB + Bluetooth MIDI input (single code path — Android's MIDI API treats both the same once paired at the OS level)
- MIDI Learn: map any physical pad/knob to Volume, Pitch, EQ Low/Mid/High, Patch Next/Prev, Edit, Save
- **Pad mapping is dual: note-based and CC-based, both live** — despite an earlier changelog entry below claiming note-based mapping was removed, a 2026-08-14 audit confirmed `MidiLearnRepository` (note→pad), native `MidiProcessor`'s `padToNote` map, and the whole `enableMidiLearn`/`assignMidiNote`/`onPadHit` JNI chain are all present, wired, and reachable from `MidiLearnScreen.kt`'s "PAD NOTES" section. `CcMapRepository`'s `PAD_1`–`PAD_8` CC targets are a separate, independent pad-triggering path through the same MIDI Learn flow as everything else. Either a learned note or a learned CC fires a pad. There are no built-in default CC mappings for pads, so a CC mapping must be MIDI-Learned once before a controller's pads will trigger anything over CC (note-based triggering has no such requirement once a note is learned).
- **MIDI Channel Select** (1–16 or ALL) — filters incoming MIDI to one channel when your controller shares a MIDI bus
- Hardware-keyboard fallback: `Q W E R` → pads 1–4, `A S D F` → pads 5–8 (suspended automatically while a text field has focus, e.g. the rename dialog)
- Gated behind the MIDI add-on purchase flag (see §1.8) — shows a plain "MIDI is a paid add-on" screen instead if not granted

**Backup**
- Backup/Restore screen zips every kit, every preference, the MIDI CC map, MIDI note mappings, and every reachable custom audio file into one `.zip` (via Storage Access Framework); Restore reverses it
- Activation/license state is deliberately **excluded** from both this backup and Android's own OS-level auto-backup (see §1.8)

**Startup**
- Branded splash screen (app logo, "ARUN SPD 30" wordmark) shown for about a second on every launch before handing off to either the activation screen or the pads

**Persistence**
- Everything (kit list, per-pad settings, choke groups, BPM, bank state, MIDI mappings, MIDI channel, last-open kit) survives an app restart — reopening picks up exactly where you left off

### 1.4 Architecture

No ViewModel, no Hilt, no Navigation component. `OctapadScreen.kt` (~1900 lines) holds essentially all UI state as `remember { mutableStateOf }` / `SnapshotStateList`, and a handful of singleton objects hold state that needs to outlive recomposition:

| Singleton | Role |
|---|---|
| `AudioRepository` | List of imported/recorded audio items + which pad/kit each is assigned to |
| `KitRepository` | Persists the kit list (name, factory source, per-pad settings) |
| `PreferencesRepository` | Persists global app state (BPM, bank mode, selected pad, MIDI channel, etc.) |
| `DrumEngine` | Owns the native Oboe engine lifecycle, dedupes redundant PCM reloads |
| `PadDurationCache` | Caches the real decoded duration of factory samples (see §1.6) |
| `MidiEventBus` | Lambda-based event bus connecting native MIDI callbacks to Compose state |
| `CcMapRepository` | MIDI CC → named-target mappings (Volume, Pitch, EQ, Patch nav, Edit, Save, Pad 1–8) |
| `MidiLearnRepository` | MIDI note → pad mappings |
| `MidiChannelState` | Which MIDI channel is currently being listened on |
| `NativeBridge` | JNI bridge object |
| `LicenseRepository` / `LicenseApi` / `DeviceId` | Activation/device-lock state, HTTP client, device identifier (see §1.8) |

**Pad hit → sound, step by step:**
```
finger DOWN on Pad N
  → DrumPad.pointerInput (single unified gesture handler — see §1.6) → onPress()
  → OctapadScreen.onPadHit(index)   [runs SYNCHRONOUSLY, no coroutine dispatch — see §1.6]
    → resolves which native slot(s) to trigger based on active bank(s) (A/B/C)
    → DrumEngine.trigger(slot, volume, pitch, stopExisting, lengthFraction)
      → NativeBridge.triggerPad(...)  [JNI]
        → AudioEngine::triggerPad()  [C++, on the calling thread]
          → claims a free Voice slot, writes padIndex/position/volume/pitch/lengthFraction,
            THEN publishes it "ready" (see §1.6 for why this order matters)
    → Oboe's realtime callback (AudioEngine::onAudioReady) mixes every ready voice every buffer
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

- `AudioEngine.h/.cpp` — Oboe stream (Exclusive/MMAP performance mode when the device supports it, device-native sample rate — not a hardcoded value, see §1.6 — stereo float), 24 pad buffer slots, 64-voice polyphony pool, linear-interpolation pitch shifting, per-pad delay taps, a simple 3-band IIR EQ + master level applied on the final mix, soft clipping.
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
- The Android side has **not been compiled** in the environment this was built in (no Android SDK/NDK available) — every fix listed above is verified by careful manual tracing and full brace/paren-balance checks across the codebase, not an actual compiler run. Build it in Android Studio (or anywhere with the SDK/NDK set up) before shipping, and see §1.9 for what to specifically stress-test.

### 1.8 Licensing / Activation (client-side)

Full loop, connected to the admin panel described in §2.

- **`license/DeviceId.kt`** — stable per-device ID: `Settings.Secure.ANDROID_ID`, with a persisted random UUID fallback for the rare device where that's unavailable. Used to lock one activation code to exactly one phone.
- **`license/LicenseApi.kt`** — plain `HttpURLConnection` client (no networking library dependency) calling the admin panel's `/api/app/signup`, `/api/app/redeem`, `/api/app/status`.
- **`license/LicenseRepository.kt`** — local cache with a **3-day offline grace period**: the app opens instantly without internet once activated, but a remote deactivation still catches up within 3 days of it actually happening (or within ~30 minutes if the app is open and online — see the periodic check below).
- **`ui/ActivationScreen.kt`** — the real first-run blocking screen: admin-panel server URL (collapsible, set once), name/phone (sent to `/api/app/signup`), activation code (sent to `/api/app/redeem`), with distinct error states for invalid code / already-used-on-another-device / deactivated / can't-reach-server.
- **`ui/MidiPaywallScreen.kt`** — shown instead of MIDI Learn when the current license's `midiPurchased` flag is false.
- **`MainActivity.kt`** — gates entry: Splash → (Activation screen if not yet activated) → the pad screen. Won't show the pads until activation succeeds once.
- **`OctapadScreen.kt`** — re-checks `/api/app/status` every 30 minutes while the app is open, so a remote deactivation or a MIDI purchase grant from the dashboard takes effect live, not just on next app restart.
- **Security note**: `license_prefs` (the SharedPreferences file holding all of this) is explicitly excluded from Android's OS-level backup — see the last item in §1.6.
- **`AndroidManifest.xml`** — `INTERNET` + `ACCESS_NETWORK_STATE` permissions added (previously missing entirely — the app couldn't talk to a server at all before this), plus `usesCleartextTraffic="true"` for local-network testing against a non-HTTPS admin panel URL during development.

**What you need to actually use this**: a real, reachable admin panel URL (see §2.6 — either your laptop's LAN IP for local testing, or a deployed URL like Vercel) and a real MongoDB Atlas connection string behind it. Without those, `/api/app/*` calls will fail and the activation screen will show "Couldn't reach the server."

### 1.9 What to specifically test once you can build this

- Rapid multi-pad hitting, and hitting the same pad repeatedly fast — the exact repro for the native race condition fix in §1.6
- The 2-finger swap/drag/mix gesture, repeatedly — the exact repro for the gesture-conflict fix
- LOOP mode on a factory (not custom-imported) pad at a slow BPM
- Mix / Add-To-End / waveform crop on factory kit sounds specifically
- Importing a deliberately corrupted/truncated audio file — should fail gracefully, not degrade later imports
- Recording while another app holds the microphone — should show an error toast, not crash
- The full activation flow against a real deployed (or LAN) admin panel URL
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

**Full system-wide audit (latest pass, 2026-08-14)**

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
