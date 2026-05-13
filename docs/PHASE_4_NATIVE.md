# Phase 4 native JPEG scaffold

Phase 4 option (c), full native scope, was chosen speculatively before Phase 3
benchmark numbers were captured. This change therefore keeps the native JPEG
path reversible: `Encoder` attempts `NativeJpegEncoder` only when the feature
flag is enabled and the JNI library loads, then falls back to platform
`Bitmap.compress(JPEG, ...)` on any load or encode failure.

## Manual prerequisites

The repository intentionally does not vendor libjpeg-turbo source or binaries.
Before the native path can build or activate, add libjpeg-turbo 3.0.4 prebuilts:

- `core/processing/src/main/cpp/prebuilts/arm64-v8a/libturbojpeg.a`
- `core/processing/src/main/cpp/prebuilts/armeabi-v7a/libturbojpeg.a`
- `core/processing/src/main/cpp/prebuilts/x86_64/libturbojpeg.a`
- `core/processing/src/main/cpp/prebuilts/x86/libturbojpeg.a`
- `core/processing/src/main/cpp/include/turbojpeg.h`

Build them from the official 3.0.4 source release with the Android NDK
toolchain. Follow libjpeg-turbo's `BUILDING.md` Android section and use NDK
r26 or newer for 16-KB page-size compatibility; this repo currently targets the
latest installed NDK, 29.0.14206865.

Until those files are present, `:core:processing:assembleDebug` fails at CMake
configuration with an actionable missing-prebuilt error. Quality-only tasks
(`lint`, `detekt`, unit tests) skip native configuration so they remain usable
before binaries are vendored. To validate Kotlin-only assembly, run Gradle with
`-Pimageshare.skipNativeJpegBuild=true`.

## Reversibility

If follow-up benchmarks show less than a 2x JPEG encode speedup, revert the
native path by deleting:

1. `core/processing/src/main/cpp/`
2. the `externalNativeBuild`, `ndk`, `ndkVersion`, `prefab`, and native
   `buildConfigField` entries from `core/processing/build.gradle.kts`
3. `NativeJpegEncoder.kt`
4. the JPEG-native call site inside `Encoder`

## Size impact

Static linkage is expected to add roughly 50-150 KB per ABI split after App
Bundle ABI splitting, so each user should only download the library for their
device ABI.
