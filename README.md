# Octapad — "ARUN SPD 30"

An 8-pad Android drum sampler built with Jetpack Compose and a native C++/Oboe audio engine tuned for the lowest latency Android's audio stack allows. Inspired by hardware samplers like the Roland SPD series.

This repo also includes a small **admin panel** (Next.js + MongoDB) for managing activation codes and device licensing for the app — fully connected to the app's own activation flow.

> Looking for deep technical detail — architecture, data flow, native engine internals, every bug fixed and why, API reference? See **[DOCUMENTATION.md](DOCUMENTATION.md)**.
>
> Setting up activation for the first time (deploy the admin panel, connect the app to it)? See **[ACTIVATION_SETUP.md](ACTIVATION_SETUP.md)**.

---

## What's inside

```
.
├── app/            Android app (Kotlin + Jetpack Compose + C++/Oboe)
├── admin-panel/    Next.js + MongoDB admin dashboard for licensing
└── DOCUMENTATION.md  Full technical writeup of both
```

## Features

- **8 velocity-sensitive pads** (MIDI velocity; touch always full velocity — a hardware limitation, not a software one), multi-touch, per-pad LED
- **A / B / C banks** — any combination layers together on a pad hit
- **Kit system** — up to 200 kits, 25 factory kits bundled, Save / Load / Rename (including direct inline rename from the Patch List) / Copy / Delete
- **Per-pad audio controls** — Volume (0–200%), Pitch, 3-band EQ, Delay, Length trim, Reverse, One Shot / Loop / Mix play modes, 6-level choke groups — all of it works on factory kit sounds, not just imported audio
- **Waveform crop editor** — trim start/end with zoom, delete a region from the middle
- **MIDI** — USB + Bluetooth input, MIDI Learn, per-pad note *or* CC mapping (for controllers that send Control Change instead of Note-On), channel select (1–16/ALL), hardware-keyboard fallback (`Q W E R` / `A S D F`) — gated behind a paid add-on flag
- **Mic recording** straight onto a pad, with proper error handling if the mic is busy
- **Backup / Restore** — one `.zip` for every kit, setting, MIDI mapping, and custom sound
- **Activation on first launch** — one code locks to one device, remote deactivation and MIDI unlock both take effect live via the admin panel
- Branded splash screen, responsive layout, everything persists across restarts

See [DOCUMENTATION.md](DOCUMENTATION.md) for the full feature breakdown, a detailed list of real bugs found and fixed, and what's honestly still outstanding (a 700-tone sample pack, Hindi font rendering, in-app MIDI payment collection).

## Building the Android app

Prerequisites: Android Studio, NDK `27.0.12077973`, CMake `3.22.1`, JDK 21, and an internet connection for the first build (Oboe 1.9.0 is fetched via CMake `FetchContent`).

```bash
./gradlew :app:assembleDebug
```

Min SDK 24, target SDK 36. The app is landscape-only, and requires activating against a running admin panel on first launch (see below).

## Running the admin panel

```bash
cd admin-panel
cp .env.local.example .env.local   # add your MongoDB URI + admin password
npm install
npm run dev
```

For the phone to actually reach it, `localhost` won't work — use your laptop's LAN IP for local testing, or deploy (Vercel is easiest). Full setup, including how to get a free MongoDB Atlas connection string, is in [admin-panel/README.md](admin-panel/README.md) — or follow [ACTIVATION_SETUP.md](ACTIVATION_SETUP.md) for the complete deploy-and-connect walkthrough.

## Project status

Actively developed — see [DOCUMENTATION.md](DOCUMENTATION.md#4-changelog) for a running changelog of what's been fixed/added, and the honest "known limitations" sections in both `DOCUMENTATION.md` and `admin-panel/README.md` for what's still outstanding. The Android side has not yet been compiled in the environment this was developed in — build and test on a real device before shipping.
