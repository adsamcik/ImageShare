# ImageShare Transform API Migration Guide

## Why migrate from share intents or in-app transforms?

Before the Transform API, host apps had two common options: launch ImageShare through `ACTION_SEND`, or implement their own image resizing, encoding, and metadata policy. `ACTION_SEND` is user-visible and useful when the user wants to leave the host flow, but it is awkward for attachment preparation, profile-photo upload, forum posting, or chat compression. A custom pipeline keeps users in the host app but duplicates codec handling, EXIF decisions, target-size logic, and device-specific fallbacks.

The Transform API is a middle path. The host keeps control of its UI, while ImageShare performs a local one-shot transform through a versioned provider. The RFC source of truth is `docs\RFCs\0001-transform-api.md`; `samples\minimal-host` shows the smallest direct-provider migration and `samples\picker-host` shows a fuller picker-based flow.

| Approach | User flow | Host code | Image quality policy | Best fit |
| --- | --- | --- | --- | --- |
| `ACTION_SEND` to ImageShare | Leaves host UI | Low | User chooses in ImageShare | Manual sharing |
| Host-owned pipeline | Stays in host | High | Host maintains all rules | Apps needing total control |
| Transform API | Stays in host | Moderate or low with SDK | ImageShare contract | Upload prep and conversion |

## When not to migrate

Do not migrate flows that require interactive editing, batch jobs, foreground progress owned by ImageShare, server-side processing, streaming transforms, video, or non-image formats. Do not use the provider as a hidden way to read data the user did not choose for your app. If your app already has strict byte-for-byte output compatibility requirements, migrate only after adding golden tests around the exact Transform API output you depend on. If ImageShare is optional in your product, keep a fallback for devices where it is not installed or the requested format is unsupported.

## Migration steps

1. Inventory existing image outputs: format, max dimensions, quality, metadata handling, and upload byte limits.
2. Map each output to a Transform API URI: for example, JPEG q85, `longEdge2048`, `stripall`.
3. Add Android package visibility queries for `com.imageshare.app` and `com.imageshare.app.transform`.
4. Decide whether to use the SDK (`TransformRequest` and `ImageShareTransform`) or direct provider URIs.
5. Move transform reads to a worker thread. The synchronous SDK call and direct stream reads block.
6. Forward the source read grant to ImageShare before opening the transform URI.
7. Parse stable error codes and connect them to host UI recovery.
8. Cache or save the result in host-owned storage if you need it after the stream closes.
9. Add tests for URI construction, permission cleanup, unsupported formats, and fallbacks.

## Before and after: direct provider

Before, a host might bounce the user to ImageShare:

```kotlin
val send = Intent(Intent.ACTION_SEND)
    .setType("image/*")
    .putExtra(Intent.EXTRA_STREAM, sourceUri)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
startActivity(Intent.createChooser(send, "Share with ImageShare"))
```

After migration, the host stays in place and reads transformed bytes:

```kotlin
val target = Uri.Builder()
    .scheme("content")
    .authority("com.imageshare.app.transform")
    .appendPath("v1")
    .appendPath("jpeg")
    .appendPath("q85")
    .appendPath("longEdge2048")
    .appendPath("stripall")
    .appendQueryParameter("source", sourceUri.toString())
    .build()

context.grantUriPermission("com.imageshare.app", sourceUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
val bytes = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(target)?.use { it.readBytes() }
}
```

The picker host sample expands this by letting users choose format, quality, resize, metadata, and optional `targetBytes`, then writing the result to cache or MediaStore.

## Before and after: SDK

If you prefer typed construction, replace string URI assembly with the SDK:

```kotlin
val request = TransformRequest.builder(sourceUri)
    .format(TransformRequest.Format.WebpLossy)
    .fixedQuality(82)
    .longEdge(1600)
    .metadata(TransformRequest.Metadata.StripAll)
    .build()

val result = try {
    val bytes = ImageShareTransform.transformAsync(context, request)
    TransformResult.Success(bytes, request.format.mimeType, bytes.size.toLong())
} catch (error: TransformException) {
    TransformResult.Error(error.code, error.message.orEmpty())
}
```

The SDK validates ranges and rejects a source that points back at the Transform API itself, preventing accidental recursive requests before they cross process boundaries.

## Result handling

Treat the provider stream like any other content stream: drain it once, close it, and write the bytes where your app needs them. For uploads, you may stream to a temporary host cache file first so retries do not require a second transform. For display, use a file, byte array, or MediaStore item that your image loader supports. Error handling should branch on stable codes, not free-form messages. `UNSUPPORTED_FORMAT` can fall back to JPEG or WebP; `GRANT_LOST` should ask the user to pick again; `RATE_LIMIT` and `SYSTEM_BUSY` should back off; `MALFORMED_URI` is a bug in host construction.

## Permissions cleanup

Add the package visibility `queries` block shown in the integration guide. Continue to request whatever permissions or picker contracts your app needs to obtain the original source. The Transform API does not require a new runtime permission for standard transforms. Grant ImageShare read access only to the source URI and keep the grant alive until the output stream is fully consumed. If you explicitly revoke grants, do it after the `use` block returns. Do not add dependencies or permissions to the ImageShare app module for host migration work.

## Test matrix note

At minimum, run the migration against: ImageShare installed and unavailable, missing grant, revoked-too-early grant, malformed URI, JPEG/WebP/PNG outputs, HEIF/AVIF fallback, very large source, `qauto` with and without `targetBytes`, metadata strip/preserve choices, and process recreation after the user picks an image. Include at least one test that compares SDK-built and manually-built URIs for your common preset.

## Feature mapping

| Existing feature | Transform API mapping |
| --- | --- |
| Resize longest edge to 2048 px | `longEdge2048` |
| Keep original dimensions | `original` |
| Fixed square or thumbnail | `exactWxH` with default aspect lock, or `aspectLock=false` when intentional |
| Scale by percentage | `percentN` |
| JPEG upload quality | `qN` with `jpeg` |
| Target upload size | `qauto&targetBytes=N` |
| Remove EXIF/location | `stripall` |
| Keep safe metadata | `preservesafe` |
| Preserve all metadata | `preserveall` |
| Convert to WebP | `webp` or `webplossless` |
| Convert to HEIF/AVIF | `heif` or `avif`, with fallback |

## Hybrid pattern

A practical migration is hybrid: use your existing pipeline as fallback and prefer Transform API when available. For example, call `ImageShareTransform.isAvailable(context)` before enabling the ImageShare option, keep direct JPEG compression for old devices, and use the provider for privacy-sensitive metadata stripping or target-byte encoding. This lets you ship without forcing every user to install ImageShare, while giving users who do have ImageShare a consistent on-device transform.

## Pitfalls

The most common pitfall is doing I/O on the main thread. The next is forgetting that the source grant must target `com.imageshare.app`, not the transform URI authority. Avoid constructing URIs from unchecked text fields without validating tokens. Do not revoke the source grant in a `finally` block that runs before the output stream has been drained. Do not assume HEIF or AVIF are universally available. Do not point `source` at another Transform API URI; current app and SDK validation reject self-reference.

## Q&A

**Does this upload images to ImageShare servers?** No. The transform is local to the device.

**Can I use it from a service?** Yes for the raw provider or SDK call, as long as you have a valid `Context`, grant, and worker thread. Activity-result style wrappers, if added later, may require an Activity or Fragment.

**Can I keep using `ACTION_SEND`?** Yes. Use share intents when user-visible handoff is the goal and Transform API when your app needs bytes back inline.

**What version should I target?** Use `/v1/` until a future RFC announces another version. Handle `UNSUPPORTED_VERSION` gracefully.

**Where should I copy from?** For direct URIs, copy the shape of `samples\minimal-host` first, then consult `samples\picker-host` for a more complete UI and result handling pattern.
