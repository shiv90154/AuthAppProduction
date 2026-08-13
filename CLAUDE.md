# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

This repo holds two independent projects that are connected at runtime but never built together:

- **`app/`** — the Android drum-pad sampler ("Octapad" / "ARUN SPD 30"): Kotlin + Jetpack Compose UI, C++/Oboe native audio engine. See `app/CLAUDE.md` for build commands and architecture.
- **`admin-panel/`** — Next.js + MongoDB admin dashboard that issues activation codes and manages device licensing for the app. See `admin-panel/CLAUDE.md` for build commands and architecture.

The connection between them: the Android app calls the admin panel's `/api/app/*` REST endpoints over plain HTTP to activate, and to periodically re-check license status. There is no shared code, shared types, or shared build step — treat them as separate codebases that happen to live in one repo.

`DOCUMENTATION.md` at the repo root is the full technical writeup of both projects — architecture, every real bug found and fixed with rationale, and a changelog. Read it before making non-trivial changes; it has context this file deliberately doesn't repeat.

## Working across both projects

- Changes to the admin panel's `/api/app/*` request/response shapes must be mirrored in `app/src/main/java/com/example/myapplication/license/LicenseApi.kt` (and vice versa) — nothing enforces this at compile time since they're different languages/repos-in-one-repo.
- Neither project has automated tests worth relying on (Android ships only placeholder instrumented/unit tests; the admin panel has none). Verification for the Android side has to be manual/logical review or an actual on-device build — there was no Android SDK/NDK available in the environment this was developed in, so treat "compiles cleanly" as unverified until you've actually run `./gradlew` somewhere with the SDK installed.
- Keep `DOCUMENTATION.md` and the two `README.md` files updated when you make non-trivial changes — they were allowed to drift stale earlier in this project's history and it caused real confusion.
- `README.md` at the repo root has been found corrupted into garbled UTF-16 (null-byte-interleaved) more than once, from causes outside any edits made here — if you ever read it and see garbled text instead of markdown, rewrite it clean in UTF-8 rather than trying to patch it.
