# Play Store Listing — ARUN SPD-30 MOBILE OCTAPAD

Draft copy + questionnaire answers to paste into Play Console. Adjust wording
to taste; the **Data safety** and **content rating** answers must stay
truthful to what the app actually does.

---

## Basics

| Field | Value |
|-------|-------|
| App name | ARUN SPD-30 MOBILE OCTAPAD |
| Package | `com.arunspd30.octapad` |
| Default language | English (or Hindi — pick one, add the other as a translation) |
| App or game | App |
| Category | Music & Audio |
| Tags | drum machine, sampler, music production, percussion |
| Contact email | inphora1@gmail.com |
| Website | https://octapad-adminpanel-final.vercel.app (or a real marketing site) |
| Privacy policy | https://octapad-adminpanel-final.vercel.app/privacy |

## Short description (≤ 80 characters)

> 8-pad octapad sampler with your own sounds, loops, MIDI and low-latency audio.

(78 chars. Alt: "Turn your phone into an octapad — record pads, loop, and play live with MIDI.")

## Full description (≤ 4000 characters)

> **ARUN SPD-30 MOBILE OCTAPAD** turns your phone into a pocket octapad / drum
> sampler, built for live players.
>
> **Play**
> • 8 velocity-aware pads with a tuned native C++/Oboe audio engine for the
>   lowest latency your device allows
> • Two independent kit banks (A and B) you can switch or layer instantly
> • 200 kit slots per bank
>
> **Make it yours**
> • Record straight into a pad with the mic, or import your own WAV/audio files
> • Non-destructive crop editor — trim start and end without touching the
>   original sample
> • Per-pad volume, pitch, pan, gain, 3-band EQ, reverse and velocity
>
> **Perform**
> • Loop mode with adjustable BPM, plus a separate SPEED (varispeed / pitch)
>   control
> • Per-pad play modes: One Shot, Loop, Multiplay
> • Choke groups (4 levels) for realistic hi-hat / cymbal cut-offs
> • Delay with per-pad on/off and a master delay switch on the main strip
>
> **MIDI**
> • Connect a USB/BLE MIDI controller
> • Learn any note or CC to a pad, or to Volume / Pitch / EQ / patch navigation
>   / bank switch / delay toggle
> • Channel filtering
>
> **Built for the stage**
> • Landscape, fully immersive, consistent layout across phones
> • Hardware-keyboard mapping (Q W E R / A S D F) for quick testing
>
> Requires a one-time activation code. Works offline after activation.

## "What's new" (first release)

> First public release.
> • 8-pad sampler with native low-latency audio
> • Record / import / crop your own sounds
> • Loop, BPM, SPEED, choke groups, delay
> • Full MIDI note & CC learn
> • Two independent kit banks

---

## Data safety form answers

**Does your app collect or share any of the required user data types?** → Yes

**Is all user data encrypted in transit?** → Yes

**Do you provide a way for users to request that their data be deleted?** → Yes
(via the contact email; deleting the activation record may deactivate the app)

### Data types

| Category | Data type | Collected | Shared | Processed ephemerally | Required/Optional | Purpose |
|----------|-----------|-----------|--------|-----------------------|-------------------|---------|
| Personal info | Email address | Yes | No | No | Optional | Account management, support |
| Personal info | Name | Yes | No | No | Optional | Account management, support |
| Device or other IDs | Device or other IDs | Yes | No | No | Required | App functionality (device-locked licensing), fraud prevention |
| App activity | Other actions (licensing status checks) | Yes | No | No | Required | App functionality |

- **Audio** (microphone recordings, imported files): **not collected** — never
  leaves the device. Do **not** declare it as collected/shared.
- No location, contacts, financial info, health, messages, photos, or browsing
  history collected.
- No data shared with third parties (Vercel/MongoDB are sub-processors /
  service providers hosting our own backend, which Play does not count as
  "sharing").
- No advertising or analytics.

## Content rating questionnaire

- Violence: None
- Sexuality: None
- Language: None
- Controlled substances: None
- User-generated content / social features: None (no in-app community, no
  sharing between users)
- Does the app share the user's location: No
- Is the app primarily directed at children: No
- Expected rating: **Everyone / PEGI 3 / 3+**

## App access (for review)

The reviewer needs a working activation code. In Play Console → App content →
App access, add:

> All functionality requires a one-time activation code entered on first
> launch. Test code: **[[generate a code in the admin dashboard and paste it
> here]]**. Server URL is pre-filled. No other login needed.

## Ads

Contains ads: **No**

## Target audience

13+ (music production tool; collects a device identifier).
