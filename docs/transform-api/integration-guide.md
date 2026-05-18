# ImageShare Transform API Integration Guide

## Overview

The ImageShare Transform API lets an Android host app ask ImageShare to transform a single image without launching ImageShare UI. The contract is a `ContentProvider` URI: the host builds a `content://com.imageshare.app.transform/...` request, grants ImageShare read access to the source image, then reads the transformed bytes from `ContentResolver.openInputStream`. The source of truth for the wire contract is `docs\RFCs\0001-transform-api.md`; the canonical working examples are `samples\minimal-host` and `samples\picker-host`.

The API is designed for attachment shrinking, privacy-preserving metadata stripping, thumbnail generation, and format conversion. It is not a batch API, streaming video API, or remote service. ImageShare performs the work on-device and does not require network access.

## Installation: SDK or direct provider

Use the SDK module when you want typed request construction and stable error mapping:

```kotlin
val request = TransformRequest.builder(sourceUri)
    .format(TransformRequest.Format.Jpeg)
    .fixedQuality(85)
    .longEdge(2048)
    .metadata(TransformRequest.Metadata.StripAll)
    .build()
// Synchronous (must be off the main thread)
val bytes = ImageShareTransform.transform(applicationContext, request)
```

Use the direct provider when you do not want an SDK dependency. The minimal host sample does exactly this by constructing the URI string and opening it with `openInputStream`. The picker host sample shows a more complete direct integration with format, quality, resize, metadata, target byte, cache, and gallery-save controls.

## Prerequisites and package visibility

On Android 11 and newer, hosts should declare package visibility for ImageShare and the provider. The sample manifests contain the canonical declaration:

```xml
<queries>
    <package android:name="com.imageshare.app" />
    <provider android:authorities="com.imageshare.app.transform" />
</queries>
```

The host also needs a readable `content://` source URI, commonly from the Android photo picker, Storage Access Framework, MediaStore, or the host app's own provider. `file://` sources are not part of the contract because they cannot carry temporary URI grants safely.

## Quick start

Direct provider integration has three steps: build, grant, read. Do the read on a background dispatcher.

```kotlin
suspend fun shrinkForUpload(context: Context, source: Uri): ByteArray? = withContext(Dispatchers.IO) {
    val transform = Uri.Builder()
        .scheme("content")
        .authority("com.imageshare.app.transform")
        .appendPath("v1")
        .appendPath("jpeg")
        .appendPath("q85")
        .appendPath("longEdge2048")
        .appendPath("stripall")
        .appendQueryParameter("source", source.toString())
        .build()

    context.grantUriPermission(
        "com.imageshare.app",
        source,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )

    try {
        context.contentResolver.openInputStream(transform)?.use { it.readBytes() }
    } catch (error: FileNotFoundException) {
        // Parse messages like: ImageShareTransform: RATE_LIMIT: Transform rate limit exceeded
        null
    }
}
```

Do not revoke the source grant until the returned stream has been fully drained and closed. The provider may still be reading the source while your process is reading the output pipe.

## URI schema reference

The stable v1 schema is:

```text
content://com.imageshare.app.transform/v1/{format}/{quality}/{resize}/{metadata}?source={encoded-content-uri}
```

`format` supports `jpeg`, `png`, `webp`, `webplossless`, `heif`, and `avif`. Device codec availability can still reject `heif` or `avif`.

`quality` supports `q1` through `q100`, or `qauto` when `targetBytes=N` is also present. `qauto` asks ImageShare to search for an output near the target byte count.

`resize` supports `original`, `longEdgeN`, `exactWxH`, and `percentN`. Exact resize may include `aspectLock=false`; otherwise aspect lock is implicit. Dimension and pixel limits protect the device from oversized decodes.

`metadata` supports `stripall`, `preservesafe`, and `preserveall`. Hosts must choose explicitly; `stripall` is recommended for privacy-sensitive uploads.

Query parameters are `source` (required), `targetBytes` (required with `qauto`), and `aspectLock` (only valid with exact resize).

## Permissions and grants

The host must forward a read grant to the ImageShare package before opening the transform URI:

```kotlin
context.grantUriPermission(
    "com.imageshare.app",
    sourceUri,
    Intent.FLAG_GRANT_READ_URI_PERMISSION,
)
```

ImageShare does not infer permission from caller identity and does not bypass grants for host-owned providers. If the grant is missing or revoked too early, the provider reports `GRANT_LOST`. Grant the original source URI, not the transform URI.

## Error codes

Provider failures are surfaced as `FileNotFoundException` messages with the prefix `ImageShareTransform:`. The SDK maps these to `TransformException` and `TransformResult.Error`.

| Code | Meaning | Typical recovery |
| --- | --- | --- |
| `MISSING_SOURCE` | No valid `source` query parameter | Rebuild the URI with a `content://` source |
| `MALFORMED_URI` | Bad token, invalid range, invalid cross-field combination, or self-reference | Fix request construction |
| `UNSUPPORTED_VERSION` | Installed ImageShare does not serve that path version | Use `/v1/` or disable the feature |
| `UNSUPPORTED_FORMAT` | Codec unavailable on this device | Fall back to JPEG, PNG, or WebP |
| `GRANT_LOST` | Source read grant missing or revoked | Re-grant and retry after user repicks if needed |
| `RATE_LIMIT` | Per-UID rate limit exceeded | Back off and retry later |
| `SYSTEM_BUSY` | Provider concurrency cap reached | Retry with jitter |
| `PIXEL_BUDGET_EXCEEDED` | Source dimensions exceed safety limits | Ask user for a smaller source |
| `PROCESSING_FAILED` | Decode, encode, or I/O failure | Show a generic failure and allow retry |

The SDK maps unrecognized or forward-compatible provider codes to `TransformResult.ErrorCode.Unknown`; hosts should surface a generic error and allow normal retry or fallback handling.

## Threading

Opening the transform stream blocks while ImageShare reads, decodes, resizes, encodes, and returns bytes. Never call the SDK synchronous `ImageShareTransform.transform()` or direct `openInputStream(...).readBytes()` on the main thread. Use `ImageShareTransform.transformAsync()`, `Dispatchers.IO`, a Java executor, or another worker mechanism. UI samples use coroutines and switch to `Dispatchers.IO` before file I/O.

## Caching

ImageShare maintains a per-caller content-addressed disk cache of recent transforms:

- **Budget**: 200 MB hard disk cap + 2000 entry hard count
- **Expiry**: 24 hours from creation (`createdAtMs` in `.meta` sidecar)
- **Eviction**: FIFO by `createdAtMs`, oldest first
- **Isolation**: cache key is SHA-256(callerUid + source + paramsSig); two apps with the same source+params get different cache entries
- **Reliability**: cache is best-effort; if disk is full or files are corrupted, ImageShare falls back to fresh decode/encode

Repeated identical requests typically return cached bytes in ~10-30 ms vs ~200-600 ms for a fresh decode/encode. Hosts should still cache their own result if they need to display, upload, or save it later, as both samples write the output to the host cache directory.

## Performance expectations

| Request shape | Expected behavior | Host guidance |
| --- | --- | --- |
| JPEG/WebP, `longEdge1024`, `stripall` | Fastest common path | Good default for previews |
| JPEG q85, `longEdge2048` | Balanced upload preset | Good default for attachments |
| PNG or WebP lossless | Larger outputs | Use for screenshots or alpha |
| HEIF/AVIF | Device-dependent and slower | Provide JPEG/WebP fallback |
| `qauto` with `targetBytes` | May run multiple encodes | Use when upload limit matters |
| Large originals | Bounds check then heavier decode | Show progress and allow cancel |

## Best practices

Prefer `stripall` unless the user explicitly asked to preserve metadata. Provide a local fallback when ImageShare is not installed or `ImageShareTransform.isAvailable(context)` is false. Reuse canonical builders from the SDK or mirror `samples\picker-host` rather than concatenating unchecked user input. Log full transform URIs only in debug builds because the encoded source can contain provider-specific identifiers. Treat transformed bytes as user data and store them no longer than necessary.

## Versioning

The path segment `/v1/` is the compatibility boundary. Additive features may appear under v1, but breaking changes require a new path such as `/v2/` served alongside v1 during a deprecation window. Public integrations should pin to `/v1/`, handle `UNSUPPORTED_VERSION`, and watch the RFC and release notes for new optional tokens.
