# libavif prebuilt binaries

This directory expects per-ABI static libraries for the optional AVIF beta path:

```text
prebuilts-avif/
  arm64-v8a/libavif.a
  arm64-v8a/libaom.a
  armeabi-v7a/libavif.a
  armeabi-v7a/libaom.a
  x86_64/libavif.a
  x86_64/libaom.a
  x86/libavif.a
  x86/libaom.a
include-avif/avif/avif.h
```

Source libavif from https://github.com/AOMediaCodec/libavif/releases and aom
from https://aomedia.googlesource.com/aom/. Build each Android ABI with the
same NDK used by the app and 16-KB page-size-compatible linker flags.

`libavif` requires an AV1 codec for encoding; this scaffold links `libaom.a`.
`dav1d` is not required for encode-only support, but may be needed if decode
support is added later.

Size warning: `libavif` plus `libaom` can add roughly 1-3 MB per ABI. App
Bundle ABI splits help, but every user still downloads one ABI's native AVIF
payload when the beta path is bundled.

Native AVIF compilation defaults to **disabled** (the build property
`imageshare.skipNativeAvifBuild` falls back to `true`). The build assembles
without these prebuilts and `Encoder` uses platform `AvifWriter` on Android 14+
(throwing `EncodeError.AvifUnavailable` on older devices). To enable native AVIF,
vendor the prebuilts above and set `imageshare.skipNativeAvifBuild=false`.
