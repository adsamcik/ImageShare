# Phase 4 native codec scaffolds

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

## AVIF platform and native beta paths

AVIF encode dispatch is platform-first. `Encoder` probes for `image/avif`
through `MediaCodecList` and uses AndroidX `AvifWriter` when a platform encoder
is present (Android 14+ is the expected easy path, with possible vendor support
on some Android 13 devices).

Older devices can use a native `libavif` scaffold only when callers explicitly
pass `enableAvifBeta = true`. The beta flag gates both the JNI load and runtime
fallback path; existing call sites keep the default `false`, so unsupported
devices throw `EncodeError.AvifUnavailable` instead of crashing.

### AVIF manual prerequisites

The repository intentionally does not vendor libavif, aom, or their binaries.
Before the beta native path can build or activate, add:

- `core/processing/src/main/cpp/prebuilts-avif/<abi>/libavif.a`
- `core/processing/src/main/cpp/prebuilts-avif/<abi>/libaom.a`
- `core/processing/src/main/cpp/include-avif/avif/avif.h`

Build libavif from https://github.com/AOMediaCodec/libavif/releases and aom
from https://aomedia.googlesource.com/aom/ for each Android ABI. This scaffold
is encode-only, so it links libavif + aom; dav1d is not bundled because it is a
decoder and can be deferred until decode support exists.

Until those files are present, `:core:processing:assembleDebug` without opt-out
flags fails at CMake configuration with an actionable missing-libavif error.
Use `-Pimageshare.skipNativeAvifBuild=true` together with
`-Pimageshare.skipNativeJpegBuild=true` for Kotlin-only assembly.

### AVIF size impact

libavif plus aom can add roughly 1-3 MB per ABI. AAB ABI splits prevent users
from downloading every ABI, but every user still receives one ABI's worth of
native AVIF code when the beta path is bundled. This is materially larger than
the JPEG native scaffold.

### AVIF reversibility

If benchmarks or APK-size review show the AVIF beta path is not justified,
revert it by deleting `NativeAvifEncoder.kt`, `AvifAvailability.kt`, the AVIF
CMake block and `imageshare_avif.cpp`, the `prebuilts-avif` and `include-avif`
directories, and the AVIF native dispatch branch. Keep the platform `AvifWriter`
path if AndroidX/platform support alone is sufficient.
