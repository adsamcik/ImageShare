# Changelog

All notable changes to ImageShare are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - Unreleased

First public release candidate prepared for Google Play publication.

### Added

- **Transform API (v2.0 RFC approved)** — design doc at `docs/RFCs/0001-transform-api.md`. Implementation in progress.
- **Smart-share chooser** — custom in-app share dialog showing the user's top 3 most-shared apps as chips, then an alphabetical "All apps" list, with a "More…" fallback to the system chooser. Powered by a new `SharingTargetsRepository` (DataStore-backed) that tracks usage counts and recency. Top-K observation, LRU eviction when at the 50-entry cap, ImageShare-self filter.
- **Auto-process when shared in** preference (under "Advanced", default off) — when on, `ACTION_SEND` from another app immediately processes with the current preset and goes straight to the share chooser, skipping the source-review screen.
- **Empty-state illustration** — Material Symbols `add_photo_alternate` icon above the "Hello ImageShare" title so the empty home screen reads as "drop an image" rather than "loading".
- **Recents chip filename labels** — visible 1-line truncated filename under each recent thumbnail so visually-similar recents are distinguishable at a glance.
- **Process summary caption** — "Will process N images, M MB" above the Process button (uses Android `<plurals>`; falls back to image-count-only when total bytes are unknown).
- **22 new instrumented + Robolectric tests** covering source-format chaos, adversarial inputs (truncated/lying-extension/massive/16-bit PNG/CMYK), permission revocation mid-flight, memory pressure, race conditions (double-tap, rapid preset switching, concurrent intent), and boundary dimensions (1×1, 10000×100, etc.).
- **Transform API RFC** (`docs/RFCs/0001-transform-api.md`, 5,769 words) — design document for v2.0's ContentProvider-based silent transform API. Covers URI schema, security model, FIFO cache policy, versioning, sample code, threat model (13 rows), and 7 open product/business questions. Two Opus 4.7 review passes; approved.

### Fixed (3 production bugs caught by chaos testing)

- **Double-tap restart race** — rapid double-tap on "Process & share" could enqueue two batches before state transitioned to Running. `MainViewModel` guard tightened to check both `currentBatchJob != null` and `activeWorkJobId != null`.
- **Partial-`SourceItem` grant loss** — when `openInputStream` threw `SecurityException` after `query()` succeeded, `InputCoordinator.resolve` silently returned a half-loaded `SourceItem` with metadata but no dimensions and no `GrantLost` signal. Now any `SecurityException` always surfaces as `IntakeError.QueryFailed(cause = GrantLost)` regardless of partial successes.
- **`Decoder` did not type `OutOfMemoryError`** — `BitmapFactory`/`ImageDecoder` OOM on adversarial dimensions propagated as raw `Error`, crashing the worker. Decoder now catches and wraps as new `DecodeError.OOM`.

### Changed

- **License relicensed from Apache 2.0 to GNU GPL v3** as part of open-source repo preparation. The previous Apache 2.0 LICENSE existed only during internal pre-launch development and was never published. SDK consumers (`:sdk:imageshare-api`) embedding via Gradle inherit GPL v3; apps interacting with the Transform API purely via ContentProvider IPC are not affected by the SDK license.
- **Material 3 segmented buttons** for `ResizeMode` reduced from 4 to 3 (Original moved to a "Use original size" toggle above the segments) — fits 360 dp width without truncation, Hick's-Law reduction.
- **Reset-to-preset-default** button now disabled when nothing has been changed (`!dirty && customOverride == null`).
- **Recents `LazyRow`** correctly honors RTL locales via native `LayoutDirection` mirroring (an earlier attempt to force `reverseLayout = true` in RTL inverted the intended order; reverted).
- **`BeforeAfterCard` reduction-warning icon** no longer wraps a tooltip that repeats the visible text. Icon `contentDescription` is null; TalkBack reads the message once instead of twice.
- **`Run in background` + `Auto-process when shared` subtitle text** uses explicit `onSurface` colour (was relying on `bodySmall`'s default `onSurfaceVariant`, which dropped contrast under dynamic-colour drift on red/orange wallpapers).

### v1.0-tooth-pulling improvements

- **`MainViewModelTest.assertEnqueuedEventually`** flaky helper replaced with deterministic `advanceUntilIdle()`-based synchronization. Stable across 3 consecutive runs.
- **Tooltip API migration** — `rememberPlainTooltipPositionProvider` → `rememberTooltipPositionProvider` at all 4 call sites (Material 3 deprecation).
- **`PresetPipeline` `SwitchToPng` branch** — replaced tautological `if/else` (both arms returning `Allow`) with explicit `when` mapping format to `AlphaPolicy`. JPEG with `SwitchToPng` policy now safely falls back to `FillBackground(WHITE)` instead of failing at encode (latent fix for future presets).

### Test coverage

- Unit tests: ~135 → ~155 across `:app`, `:core:io`, `:core:processing`, `:feature:preset`.
- Connected tests: 56 → 72 on `Medium_Phone (API 36)` emulator.
- Total: 0 failures, 8 skipped (HEIF/AVIF hardware codec graceful skips on x86_64 emulator).

### Architecture decisions

- ImageShareTheme inner-wrap in `PresetSheet` removed — outer `MainActivity` `ImageShareTheme` is sufficient for production; nested-wrap caused timing-sensitive Compose UI test regression → fixed before any release.
- `SharingTargetsRepository` uses DataStore + `org.json` (no new deps; serializes as JSON-encoded string in a single preferences key).
- `Icons.Outlined.AddPhotoAlternate` shipped as a hand-drawn vector drawable (not `material-icons-extended` dependency) to save ~80 KB APK size. Drawable cites Material Symbols as source.

### Pre-Unreleased development log (work prior to first ship)

These sections summarize foundation work done during pre-launch development. Final v1.0.0 release notes will be consolidated at ship time.

#### Foundation features (developed pre-launch)

- **5 built-in presets**: Small file (default), Best quality, Social upload (WebP), Email (200KB target-size JPEG), Custom.
- **Source intake** via Android 14+ photo picker (no broad media permissions) and SAF Open Documents (advanced).
- **`ACTION_SEND` / `ACTION_SEND_MULTIPLE` intake** from any sharing-capable app.
- **Format support — input**: JPEG, PNG, WebP, HEIF, AVIF (API 31+), animated GIF (first frame), screenshots.
- **Format support — output**: JPEG, PNG, WebP lossy, and WebP lossless.
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
- GNU GPL v3 licensed (relicensed from Apache 2.0 prior to first public release; see `LICENSE`).
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
