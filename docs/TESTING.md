# ImageShare Testing Guide

## Layers
- Unit tests (`./gradlew :app:testDebugUnitTest` + per-module equivalents) — JVM, fast.
- Instrumented compile-only (`./gradlew :app:assembleDebugAndroidTest` + per-module) — verifies androidTest sources compile against the device SDK without needing an emulator. Required to pass in CI.
- Instrumented device-run (`./gradlew connectedAndroidTest`) — requires an attached emulator or device. NOT run in CI for Phase 1.

## Test fixture conventions
- All image fixtures are GENERATED at test time (Bitmap.compress, manual byte writes) and live in cache/temp dirs.
- Binary fixtures larger than 100 KB MUST NOT be committed to source.
- The generator helpers live in:
  - `core/processing/src/androidTest/java/.../TestImages.kt` — image generators (synthetic JPEG/PNG/WebP/AVIF with optional EXIF tags).
  - `core/io/src/androidTest/java/.../GeneratedImageProvider.kt` — content URI provider for InputCoordinator/SharedIntakeStager tests.
  - `app/src/androidTest/java/.../...` — end-to-end fixtures.

## Coverage matrix (current)
| Input type                | Module           | Test file                                             |
| ------------------------- | ---------------- | ----------------------------------------------------- |
| Large JPEG (≥4k px)       | :core:processing | DecoderInstrumentedTest                               |
| EXIF-rotated JPEG         | :core:processing | DecoderInstrumentedTest                               |
| PNG with alpha            | :core:processing | DecoderInstrumentedTest                               |
| Screenshot PNG (no alpha) | :core:processing | DecoderInstrumentedTest                               |
| WebP                      | :core:processing | DecoderInstrumentedTest                               |
| AVIF (decode-only, API 31+) | :core:processing | DecoderInstrumentedTest                             |
| Animated GIF (1st frame)  | :core:processing | DecoderInstrumentedTest                               |
| Corrupt input             | :core:processing | DecoderInstrumentedTest                               |
| Zero-byte file            | :core:processing | DecoderInstrumentedTest                               |
| MIME mismatch             | :core:processing | DecoderInstrumentedTest                               |
| Full GPS EXIF scrub       | :core:processing | MetadataApplierTest, MetadataApplierInstrumentedTest  |
| JPEG quality monotonicity | :core:processing | EncoderInstrumentedTest                               |
| End-to-end decode→encode  | :core:processing | EncoderInstrumentedTest                               |
| Share-intent intake       | :app             | MainActivityShareIntentTest                           |
| Preset-driven share flow  | :app             | MainActivityShareIntentTest                           |
| Picker maxItems           | :core:io         | PhotoPickerLauncherTest                               |
| SharedIntakeStager        | :core:io         | SharedIntakeStagerTest                                |
| OutputStore + ShareLauncher | :core:io       | OutputStoreTest, ShareLauncherTest                    |

HEIC is deferred to Phase 3: Android framework HEIC encoding is not available across the Phase 1 minSdk range, and committing a binary fixture is out of scope.

## Adding a new fixture
1. Add a generator function in the relevant `TestImages.kt` (or analogous helper).
2. Use the generator from your new test method.
3. Write to `context.cacheDir` or `Files.createTempFile(...)`; clean up in a `@After` or `finally`.
4. Do NOT commit the binary.

## Running locally
- Unit + lint + detekt: `./gradlew assembleDebug testDebugUnitTest lint detekt`
- All instrumented compile checks: `./gradlew assembleDebugAndroidTest`
- Single module: e.g., `./gradlew :core:processing:testDebugUnitTest`

## CI behavior
Every PR runs the unit + lint + detekt + instrumented-compile suite via `.github/workflows/ci.yml`. Lint and test reports are uploaded as artifacts on failure.
