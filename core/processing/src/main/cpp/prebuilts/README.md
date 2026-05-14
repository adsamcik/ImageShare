# libjpeg-turbo prebuilt binaries

This directory expects per-ABI static libraries from libjpeg-turbo 3.0.4:

- `arm64-v8a/libturbojpeg.a`
- `armeabi-v7a/libturbojpeg.a`
- `x86_64/libturbojpeg.a`
- `x86/libturbojpeg.a`

Plus the header at `../include/turbojpeg.h`.

## How to build/source

### Option 1: download official source and build

1. Visit https://github.com/libjpeg-turbo/libjpeg-turbo/releases/tag/3.0.4
2. Download `libjpeg-turbo-3.0.4.tar.gz`.
3. Build for each Android ABI using the NDK toolchain. See libjpeg-turbo's
   `BUILDING.md` section "Building libjpeg-turbo for Android".

### Option 2: use a community Android AAR

Unofficial AARs exist on Maven Central. Quality varies; verify the AAR is built
from libjpeg-turbo 3.0.4+ with complete ABI coverage and 16-KB page-size flags.

### Option 3: add a local build script

A future `scripts/build_for_android.py` can automate per-ABI builds via the NDK.
Run once per ABI, then copy outputs into this directory.

## Until binaries are vendored

Native JPEG compilation defaults to **disabled** (the build property
`imageshare.skipNativeJpegBuild` falls back to `true`). The build assembles
cleanly without prebuilts and `Encoder` uses platform `Bitmap.compress(JPEG, …)`.
Runtime fallback remains in place through `NativeJpegEncoder.isAvailable() ==
false` whenever the native library is not packaged or cannot load.

To enable native JPEG, vendor the prebuilts listed above and set
`imageshare.skipNativeJpegBuild=false` (in `gradle.properties` or on the CLI).
With prebuilts missing and the flag flipped, `:core:processing:assembleDebug`
fails with a clear CMake error pointing at the missing files.

## License compliance

libjpeg-turbo is dual-licensed under BSD-style/IJG terms. Keep NOTICE and
THIRD_PARTY_LICENSES attribution current when binaries are bundled.
