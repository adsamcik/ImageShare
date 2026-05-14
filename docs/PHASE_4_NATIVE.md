# Phase 4 native codec scaffolds

Phase 4 option (c), full native scope, was chosen speculatively before Phase 3
benchmark numbers were captured. This change therefore keeps the native JPEG
path reversible: `Encoder` attempts `NativeJpegEncoder` only when the feature
flag is enabled and the JNI library loads, then falls back to platform
`Bitmap.compress(JPEG, ...)` on any load or encode failure.

## v1.0 launch policy: native is opt-IN, defaults OFF

After auditing `p4-decision-speculative`, both native paths default to **disabled**
at the Gradle level (`imageshare.skipNativeJpegBuild=true`,
`imageshare.skipNativeAvifBuild=true` as fallback values). This satisfies the
plan's §4 gating policy — the speculative scope no longer ships as the build
default — while preserving every byte of the scaffolding for future activation.

A fresh clone running `./gradlew assembleDebug` therefore produces a
Kotlin-only build with platform `Bitmap.compress` JPEG + platform
`AvifWriter` (Android 14+) and no CMake/NDK steps. `BuildConfig.ENABLE_NATIVE_JPEG`
and `ENABLE_NATIVE_AVIF` are both `false`, so `Encoder` skips the native dispatch
branch at runtime as well.

### Activation checklist

To activate the native paths for a future release:

1. Vendor the prebuilts listed in the next two sections.
2. Set `imageshare.skipNativeJpegBuild=false` and/or
   `imageshare.skipNativeAvifBuild=false` in `gradle.properties` (project- or
   user-scoped) or pass them on the command line.
3. Capture macrobenchmarks on real hardware (Pixel 4a, Pixel 6, Pixel 8) using
   the new `pixel*Api*` ManagedVirtualDevices wired in `:benchmark:macro`.
4. Compare median encode time, APK size, and battery impact against the
   platform fallback. Native paths should ship only when benchmarks justify
   the additional binary size and licensing complexity (see AOM Patent
   License 1.0 implications for AVIF).

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

With `imageshare.skipNativeJpegBuild=false` and those files missing,
`:core:processing:assembleDebug` fails at CMake configuration with an actionable
missing-prebuilt error. The default (skip=true) path skips native configuration
entirely so the project always builds out of the box.

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

Until those files are present and `imageshare.skipNativeAvifBuild=false` is
set, `:core:processing:assembleDebug` does not attempt to compile libavif. The
default (skip=true) skips native AVIF configuration. To opt in, vendor the
prebuilts and set `imageshare.skipNativeAvifBuild=false`.

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
