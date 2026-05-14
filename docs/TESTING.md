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

## Benchmarks
- Microbenchmarks compile in CI and run manually on a connected physical device with `./gradlew :benchmark:micro:connectedReleaseAndroidTest`. Use a real device, not an emulator, for representative decode/resize/encode/metadata numbers.
- Macrobenchmarks compile in CI and run manually with `./gradlew :benchmark:macro:connectedBenchmarkAndroidTest`. The macro test module targets the app release variant through its `benchmark` build type and signs the test APK with the debug signing config for local installability.
- Benchmarks do not run in CI because stable numbers require a dedicated real device with low background noise.
- Commit captured macro medians to the matching per-device entry in `benchmarks/baseline.json`.
- Use the managed device keys in `benchmarks/baseline.json` (`pixel4aApi30`, `pixel6Api31`, `pixel8Api34`) when recording macrobenchmark baselines.

### Capture workflow
1. Connect real Pixel devices that cover the target range: Pixel 4a plus Pixel 8 minimum.
2. Run `./gradlew :benchmark:micro:connectedReleaseAndroidTest` and `./gradlew :benchmark:macro:connectedBenchmarkAndroidTest`.
3. Find JSON outputs under `benchmark/micro/build/outputs/connected_android_test_additional_output/.../androidx.benchmark.json` and the matching macro output directory.
4. Manually copy median macrobenchmark values into the matching `benchmarks/baseline.json` device entry; a merge script can be added later if this becomes repetitive.
5. Commit `benchmarks/baseline.json` with the captured numbers.
6. Record device profile details in the commit or release notes: model, API level, CPU governor, charging state, and thermal posture. Run on charger to reduce throttling noise.

### Baseline Profiles
- Generate committed startup profiles with `./gradlew :app:generateBaselineProfile`; this uses the configured managed Pixel 6 API 31 device. With an already-connected device, `./gradlew :benchmark:macro:connectedBenchmarkAndroidTest` can be used as a fallback.
- The generated profile is written to `app/src/main/baseline-prof.txt` and is bundled into release artifacts by the AndroidX Baseline Profile plugin.
- Baseline Profile generation is not run in CI because managed devices require emulator boot, which is slow and flaky on Ubuntu runners. Commit the generated profile and refresh it manually.
- Regenerate after major UI changes, before releases, or when cold-start metrics regress by more than 10%.
