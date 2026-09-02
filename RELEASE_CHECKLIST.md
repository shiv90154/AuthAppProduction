# Play Store Release Checklist — ARUN SPD-30 MOBILE OCTAPAD

Status as of 2 September 2026. Items are grouped by who has to do them.

---

## ✅ Done in the repo (this pass)

- [x] `applicationId` changed `com.example.myapplication` → `com.arunspd30.octapad`
      (Play rejects `com.example.*`). `namespace` left unchanged so JNI
      symbols and the R class are untouched. **This ID is now permanent.**
- [~] R8 / minify keep rules written (`app/src/main/keepRules/rules.keep`)
      but minify itself is left **OFF** — enabling it in AGP 9.x needs the
      experimental "gradual R8" flag and a device to validate the shrunk
      build. Not a Play blocker. See the comment in `app/build.gradle.kts`
      `buildTypes.release` for the 3-step enable procedure to do later.
- [x] 16 KB page-size link flag added to `app/src/main/cpp/CMakeLists.txt`
      (`-Wl,-z,max-page-size=16384`) — required for native apps on Play.
- [x] Offline licensing grace period raised 3 → 14 days
      (`LicenseRepository.OFFLINE_GRACE_MS`) so a short server outage or a
      week off-grid doesn't lock out paying users.
- [x] App name → "ARUN SPD-30 MOBILE OCTAPAD"; splash/activation logo resized.
- [x] `.gitignore` broadened (`/.idea/`, `keystore.properties`);
      `.idea/` untracked.
- [x] Privacy policy written **and hosted** by the admin panel at
      `/privacy` (`admin-panel/src/app/privacy/page.tsx`, public, no auth) —
      URL: `https://octapad-adminpanel-final.vercel.app/privacy`. Filled with
      Inphora Pvt Ltd / Shiv Kumar (Senior Developer) / inphora1@gmail.com.
      Redeploys on admin-panel push (confirm Vercel picked it up).

---

## 🔴 Must be done by you on a machine with the Android SDK/NDK

- [ ] **Build & smoke-test on a real device.** Nothing in this project has
      ever been compiled or run on hardware.
      `./gradlew :app:assembleDebug` then `:app:assembleRelease`.
      If `assembleRelease` fails, it's almost always a missing `-keep` in
      `rules.keep` for something hit by reflection/JNI — add it, don't
      disable R8.
- [ ] **Verify the recent fixes on device** (they were reasoned, not run):
      multi-hit / patch-change crackle, BPM vs SPEED behaviour, MIDI
      DELAY_TOGGLE off, MASTER DELAY on the strip, uniform display size,
      logo/name.
- [ ] **Test on 3–4 different phones** (different OEMs: Samsung / Xiaomi /
      realme / Motorola…) for the "display alag" and audio-latency reports.
- [ ] **Create the release keystore** (`keytool -genkeypair ... -keystore
      keystore/release.jks`), put its path + passwords in `local.properties`
      as `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` /
      `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` (already wired in
      `app/build.gradle.kts`). **Back this file up — losing it means you can
      never update the app.** Or use Play App Signing (recommended).
- [ ] Build the **AAB**: `./gradlew :app:bundleRelease`.

---

## 🔴 Policy blockers — decide/implement before submitting

- [~] **MIDI unlock — mitigated, not fully de-risked.** MIDI is enabled
      per activation code (`midiPurchased` flag, toggled in the admin
      dashboard). There is no in-app payment flow. `MidiPaywallScreen`'s
      copy was reworded to remove all "paid add-on / contact developer /
      purchase" language — it now only states "MIDI not enabled for this
      code" and that an updated code unlocks it automatically. This keeps
      it clear of Play's out-of-app-purchase steering rule.
      Residual risk: a reviewer could still treat any code-gated feature as
      a paid tier. If it's ever flagged, the clean fix is to make every
      valid code include MIDI (drop the flag + screen entirely). Do NOT add
      an in-app "buy" button / price / external link — that would be the
      actual violation.
- [x] ~~Host the privacy policy~~ — done, served at
      `/privacy` by the admin panel. Just paste
      `https://octapad-adminpanel-final.vercel.app/privacy` into Play
      Console → App content → Privacy policy (and verify the page is live
      after Vercel redeploys).
- [ ] **Data safety form** (Play Console): declare device identifier +
      email collection, "data encrypted in transit", "data not sold",
      purpose = app functionality / account management.
- [ ] **Prominent disclosure** for the microphone permission if not
      obvious from context (recording UI makes it fairly obvious).

---

## 🟡 Should do (not hard blockers)

- [ ] Replace the custom Compose splash with the Android 12+ `SplashScreen`
      API (`androidx.core:core-splashscreen`) to avoid a double-flash on
      some phones.
- [ ] Add crash reporting (Play Console vitals is enough to start; Firebase
      Crashlytics for more detail) — the native audio engine's on-device
      behaviour is still unverified.
- [ ] Confirm the default activation server URL in
      `ActivationScreen.kt` (`https://octapad-adminpanel-final.vercel.app`)
      is the real production URL and has uptime monitoring.
- [ ] Remove the placeholder `com.example.myapplication` namespace someday
      (large refactor: Kotlin package + all `Java_com_example_myapplication_*`
      JNI symbols) — not required, `applicationId` is what Play checks.
- [ ] `versionCode` / `versionName` bump process for future updates.

---

## 🟢 Store listing (Play Console, no code)

- [ ] App icon 512×512 (adaptive icon already in `res/mipmap-anydpi-v26/`).
- [ ] Feature graphic 1024×500.
- [ ] Phone + 7"/10" tablet screenshots (landscape).
- [ ] Short description (≤80 chars) + full description.
- [ ] Content rating questionnaire.
- [ ] Category: Music & Audio.
- [ ] Closed testing track: Google requires **12 testers opted in for 14
      continuous days** before a new personal developer account can go to
      production.
