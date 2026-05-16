# Changelog

All notable changes to ImageShare are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] — 2026-05-16

First release. Privacy-first Android image processing utility.

### Features

- **5 built-in presets**: Small file (default), Best quality, Social upload (WebP), Email (200KB target-size JPEG), Custom.
- **Source intake** via Android 14+ photo picker (no broad media permissions) and SAF Open Documents (advanced).
- **`ACTION_SEND` / `ACTION_SEND_MULTIPLE` intake** from any sharing-capable app.
- **Format support — input**: JPEG, PNG, WebP, HEIF, AVIF (API 31+), animated GIF (first frame), screenshots.
- **Format support — output**: JPEG, PNG, WebP-lossy, HEIF (when device supports), AVIF (Android 14+ platform encoder).
- **Custom dimensions** with optional aspect lock; upscaling-prevention warning blocks accidental quality loss.
- **Target-size encoding** (e.g., Email preset) iteratively reduces quality/dimensions until the byte target is hit.
- **Alpha-aware processing**: when sharing a transparent image with a JPEG-output preset, the user is asked whether to flatten with a white background or switch to PNG. The selected strategy is remembered.
- **Background batches** via WorkManager + foreground service; survives app backgrounding and process death.
- **Recents row**: re-stage previously-picked files across reboots (via persistable URI permissions).
- **EXIF orientation handling**: portrait photos display and process at their logical (rotated) dimensions, not file pixels.
- **EXIF metadata stripping** by default (privacy preservation); presets can opt in to `PreserveSafe` (keep DateTime, drop GPS/serial).
- **Save copy** flow via SAF `ACTION_CREATE_DOCUMENT` for persisting outputs outside the cache.
- **Material 3 DayNight theme** with dynamic color (Material You) on Android 12+.
- **Accessible-first UI**: TalkBack-friendly chip state descriptions, mergeDescendants on result cards, accessible string variants (`1,600 by 1,200 pixels` vs `1600×1200`), accessible format labels (`J P E G` for screen readers).

### Privacy

- **No `INTERNET` permission** declared.
- **No analytics, no telemetry, no third-party SDKs** that report usage.
- **No broad media permissions** (`READ_MEDIA_IMAGES`/etc.): photo picker (API 33+) and SAF only.
- **FileProvider scoped to `cacheDir/shared-output/`** for outputs; `shared-intake/` is app-private and never exposed.
- **Default `MetadataPolicy.StripAll`** on output.
- **Backup configuration**: preset preferences roam to a new device on backup/restore; private data (DB, URI registry, caches) is excluded.

### Build characteristics

- `minSdk = 29`, `compileSdk = 36`, Kotlin 2.2.21, Jetpack Compose, Material 3.
- Apache 2.0 licensed.
- Release APK ~30.6 MB after R8 + resource shrinking (-23% vs debug).
- Native libjpeg-turbo + libavif scaffolds present but disabled by default; activate by vendoring prebuilts and setting `imageshare.skipNativeJpegBuild=false` / `imageshare.skipNativeAvifBuild=false`.

### Verified on

- `Medium_Phone(AVD) - API 36` x86_64 emulator (full end-to-end smoke tests + 36+18+2 connected tests + 135+ unit tests).
- Light + dark mode, font scale 200%, POST_NOTIFICATIONS runtime grant flow.

### Test coverage

- ~135 unit tests across `:app`, `:core:io`, `:core:processing`, `:feature:preset` modules.
- 48 connected (instrumented) tests across all modules.
- Robolectric coverage for SAF launchers, MainViewModel, preset codec, persistable URI registry, batch DAO migration.

### Notable bugs caught + fixed during verification

8 production bugs surfaced only via real-device emulator testing (none caught by 4 prior Opus 4.7 code reviews):

1. **Decoder double-EXIF rotation** (CRITICAL) — portrait photos shared sideways
2. **InputCoordinator Elvis-on-use** (HIGH) — every image lost width/height
3. **InputCoordinator pre-EXIF dimensions** (HIGH) — portrait photos displayed file dimensions instead of logical
4. **Decoder JPEG falseAlpha** (MEDIUM) — JPEGs triggered alpha-conflict dialogs incorrectly
5. **PresetSheet selected-chip semantics** (MEDIUM) — TalkBack didn't announce selection
6. **Email preset target was 1 MiB instead of 200 KB** (HIGH) — Email attachments 5× promised size
7. **`PresetPipeline` ignored `targetSizeBytes`** (CRITICAL) — entire target-size feature was a no-op
8. **Material 3 DayNight theme was light-only** (CRITICAL) — dark-mode users would see bright UI

Each bug has a regression test pinning the contract.

[1.0.0]: https://example.com/imageshare/releases/tag/v1.0.0
