# HelioLAN

HelioLAN is a local-first Android app that reads health data from Health Connect and serves a LAN dashboard for browser access on your Wi-Fi network.

## Current status

- Phase 0 to Phase 12 implementation is complete.
- Security and performance implementation checklists are complete.
- Release automation, signing support, and manual test/release checklists are in place.

## Recent updates (Phase 12)

- Dashboard visual refresh with improved contrast and unified green theme.
- Cardio layout update: stat cards and charts are arranged for better large-screen readability.
- Settings UX updates: equal-height cards, clearer controls, and improved permissions visibility.
- "Not available - Why?" explainer modal for unavailable metrics.
- Duplicate "Sync Now" control removed from the Today section.
- Android app theme aligned with dashboard colors.
- Setup guide redesigned with card-based sections and improved readability.
- Total calories pipeline updated to page through Health Connect records and compute range sums consistently.
- Cardio tab now tolerates per-endpoint failures, logs failing endpoints, and still renders available metrics/charts.
- Today total calories now uses Health Connect `ENERGY_TOTAL` aggregation for local-day totals, with raw overlap sums retained for drill-down/debug comparison.
- Sleep trend refresh now reprocesses both sleep start-day and wake-day to prevent stale overnight bars.
- Cardio rendering now ignores malformed/null rows to prevent `timestamp` null crashes.
- Setup/Main header text simplified to `HelioLan`; server help continues to show same-Wi-Fi private dashboard URL.
- Splash updated to green jump-rope animation styling and force-dark overrides disabled for consistent colors.

## Core capabilities

- Health Connect ingestion (heart rate, sleep, steps, resting HR, HRV, calories, distance, nutrition, SpO2)
- Incremental sync pipeline with aggregation and data freshness tracking
- Embedded Ktor dashboard server with LAN security hardening
- Optional passcode authentication and optional local TLS mode
- CSV and ZIP exports
- Dashboard frontend bundled in-app (no CDN dependencies)

## Project modules

- `app`: Android shell, setup flow, lifecycle, foreground service, release packaging
- `data`: Room entities/DAOs/repositories, SQLCipher integration
- `healthconnect`: permission management and record readers/mappers
- `sync`: sync scheduling, incremental sync engine, aggregation engine
- `server`: embedded Ktor server, API routes, auth/security middleware, export routes
- `dashboard`: static dashboard assets and minification build step

## Quick start

1. Install Android Studio + SDK 35 + JDK 17.
2. Copy `local.properties.template` to `local.properties` and set `sdk.dir`.
3. Build and run checks:

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug ktlintCheck detekt
```

4. Install debug build:

```bash
./gradlew installDebug
```

## Release

- Release process and signing setup: `RELEASE.md`
- Manual test matrix: `MANUAL_TEST_MATRIX.md`
- End-user APK install steps: `INSTALLATION.md`

## CI

- `.github/workflows/android-ci.yml`: debug build, tests, lint, and release/R8 verification
- `.github/workflows/release-apk.yml`: release APK build and GitHub Release publishing
- `.github/workflows/device-matrix.yml`: 3-device emulator instrumentation matrix (manual trigger)
