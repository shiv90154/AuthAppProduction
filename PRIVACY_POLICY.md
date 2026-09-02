# Privacy Policy — ARUN SPD-30 MOBILE OCTAPAD

_Last updated: 2 September 2026_

> **ACTION REQUIRED before publishing:** fill in the `[[ ... ]]` placeholders
> below (developer name, contact email, effective date) and host this file
> at a public HTTPS URL (GitHub Pages, your website, or a free host). Paste
> that URL into Google Play Console → App content → Privacy policy.

This policy explains what information the **ARUN SPD-30 MOBILE OCTAPAD**
Android app ("the app", "we") collects, why, and how it is handled.

## Who we are

The app is published by **[[developer / company name]]**.
Contact: **[[support email]]**.

## Summary

- The app is an offline drum-pad sampler. Your **sounds, recordings,
  kits and edits never leave your device** — they are stored only in the
  app's private storage.
- The app requires a one-time **activation code** to unlock. To validate
  that code and enforce one-device-per-code licensing, the app sends a
  **device identifier** and the activation code to our licensing server.
- We do **not** show ads, we do **not** use analytics or tracking SDKs,
  and we do **not** sell or share personal data.

## What we collect and why

| Data | When | Why | Sent off device? |
|------|------|-----|------------------|
| Activation code | When you activate | Unlock the app; bind the code to one device | Yes — to our licensing server |
| Device identifier (Android `ANDROID_ID`, or a random ID if unavailable) | On activation and on periodic re-checks | Enforce "one activation code = one device"; detect if a code was moved to a new device | Yes — to our licensing server |
| Email address / name | Only if you enter it on the sign-up screen | Let the code issuer contact you / provide support | Yes — to our licensing server |
| Licensing status (active / deactivated, MIDI unlock) | Periodic background check while the app is open (about every 30 minutes) | Reflect remote deactivation or a purchased feature unlock | Request goes out; status comes back |
| Microphone audio | Only while you actively record a pad sound | Let you sample sounds directly into a pad | **No** — recorded audio is saved only in the app's private storage on your device |
| Imported audio files | Only files you pick | Assign your own sounds to pads | **No** — stays on device |

The app also stores, **only on your device**: your kits, pad settings,
recordings, edits, MIDI mappings, and cached activation state.

## Permissions the app requests

- **Microphone (`RECORD_AUDIO`)** — used solely to record a sound into a
  pad when you tap record. Recordings are stored locally. Audio is not
  streamed, uploaded, or used for any other purpose.
- **Read audio / media files** — so you can import your own sound files
  and assign them to pads. Files are read locally only.
- **Internet / network state** — used only to reach the licensing server
  for activation and periodic license checks.

## Where data goes

License and sign-up data is transmitted over HTTPS to our activation
service, which runs on:

- **Vercel** (application hosting) — https://vercel.com/legal/privacy-policy
- **MongoDB Atlas** (database) — https://www.mongodb.com/legal/privacy-policy

These providers process the data on our behalf to operate the licensing
system. No other third parties receive your data.

## Data retention

Activation records (activation code, device identifier, and any email/name
you provided) are retained for as long as the licensing system operates,
so that a code stays bound to its device and support requests can be
handled. You can request deletion of your activation record (see below);
note that deleting it may deactivate the app on your device.

## Children

The app is a music tool and is not directed at children under 13. We do
not knowingly collect personal information from children.

## Your choices and rights

- You can uninstall the app at any time; this removes all locally stored
  data (kits, recordings, cached license state).
- You can request access to, correction of, or deletion of the activation
  data associated with your device or email by contacting
  **[[support email]]**.

## Security

Data in transit to the licensing server uses HTTPS. Local data is kept in
the app's private, sandboxed storage. Cached activation state is excluded
from Android's cloud backup so it cannot be restored onto a different
device.

## Changes to this policy

If this policy changes materially, we will update the "Last updated" date
above and, where appropriate, note it in the app's store listing.

## Contact

**[[developer / company name]]** — **[[support email]]**
