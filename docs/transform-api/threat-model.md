# ImageShare Transform API Threat Model

## Scope

This threat model covers the public Transform API exposed by the ImageShare Android app through `content://com.imageshare.app.transform/v1/...`. It includes host request construction, URI parsing, source grant handling, provider execution, cache storage, result streaming, and SDK error mapping. It does not cover ImageShare's standalone UI flows except where they share the same image processing pipeline. The contract details remain anchored in `docs\RFCs\0001-transform-api.md`; the executable integration patterns are represented by `samples\minimal-host` and `samples\picker-host`.

The API processes one image at a time. A host provides a `content://` source URI, explicitly grants ImageShare read access, and receives encoded result bytes. There is no server component, no network permission requirement for the transform, and no telemetry requirement. The largest new risk is that ImageShare becomes an exported provider callable by arbitrary installed apps.

## Assets

| Asset | Owner | Security property | Notes |
| --- | --- | --- | --- |
| Source image bytes | User and source provider | Confidentiality, integrity | Read only through host-provided URI grant |
| Transformed output bytes | User and host | Confidentiality, integrity | Streamed to the host; may be cached temporarily |
| Metadata such as EXIF location | User | Confidentiality | Controlled by `metadata` segment |
| Transform URI parameters | Host | Integrity | Must not be rewritten by ImageShare or attacker |
| ImageShare cache entries | ImageShare | Confidentiality, bounded retention | Stored under private `cacheDir\transform-cache` |
| Caller identity | Android platform | Authenticity | Derived from Binder UID, not request fields |
| Device CPU, memory, battery, disk | User/device | Availability | Protected by rate, pixel, concurrency, and cache limits |
| API compatibility contract | Host and ImageShare | Stability | Versioned by `/v1/` path segment |
| Error messages | Host and ImageShare | Integrity, limited disclosure | Stable codes; avoid leaking local paths or secrets |

## Trust boundaries

Text diagram:

- Host app process: chooses source, builds transform URI, grants read permission, reads result.
- Android framework boundary: Binder identity, package manager, URI grant table, `ContentResolver` routing.
- ImageShare provider process: parses request, validates caller and source, opens source, processes bytes, writes cache, streams result.
- Source provider boundary: MediaStore, photo picker, SAF document provider, or host provider controls the original bytes.
- Private storage boundary: ImageShare `cacheDir` stores temporary cache entries unavailable to other apps on a non-rooted device.

Data crosses from host to ImageShare in the transform URI and read grant. Data crosses from ImageShare to the source provider when the provider opens `source`. Data crosses back to the host through a stream returned by `openInputStream`. The cache is inside ImageShare's sandbox and should not become a communication channel between unrelated hosts beyond ordinary same-output cache hits.

## Adversary model

The primary adversary is an installed Android app with no special permissions that can call exported providers, send malformed URIs, withhold or revoke grants, and issue many requests. A stronger adversary is a host that legitimately has access to sensitive user-selected images and intentionally misuses ImageShare to transform or strip metadata. That is not preventable by ImageShare once the user has granted the host access; the API is not a DRM boundary. We also consider buggy hosts, old ImageShare versions, low-storage devices, oversized images, and feature-probing hosts. Physical attackers, rooted devices, kernel compromise, and hostile users operating their own device are out of scope.

## STRIDE threats

### Spoofing

A malicious host may claim in URI text that it is a trusted partner, premium app, or known package. Controls: never trust caller-supplied package names, extras, referrers, or query parameters for identity. Use `Binder.getCallingUid()` and `PackageManager.getPackagesForUid`. Any future privileged tier must use platform permission checks or signed vouchers, not URI flags. SDK convenience methods must not hide this provider-side rule.

### Tampering

An attacker may tamper with path tokens, query parameters, or percent encoding to force unexpected processing. Controls: parse strictly in the documented order, require exactly `/v1/{format}/{quality}/{resize}/{metadata}`, enforce numeric bounds, reject invalid cross-field combinations, and reject self-referential sources whose authority is `com.imageshare.app.transform`. Source bytes are read directly from the granted provider; ImageShare does not trust filenames or MIME claims as proof of content.

### Repudiation

A host may deny causing high CPU use or failed requests. ImageShare intentionally avoids telemetry and persistent caller logs, so non-repudiation is limited. Controls: derive caller UID for rate limiting and expose only in-process/debug information where appropriate. Public docs should set expectations that ImageShare does not provide an audit trail for third-party hosts. Error codes are deterministic enough for host-side logs without requiring ImageShare to collect data.

### Information disclosure

Risks include reading a source without a valid grant, leaking source identifiers in logs, preserving sensitive metadata unexpectedly, or exposing cached outputs to other apps. Controls: require `content://` sources and a temporary read grant; translate missing grants to `GRANT_LOST`; make metadata mode explicit; recommend `stripall`; store cache entries under private `cacheDir`; name cache files by strong hashes rather than user filenames; sweep entries after 24 hours; and avoid network telemetry. Hosts must treat transform URIs as sensitive because the encoded source may include provider identifiers.

### Denial of service

A host can spam cheap malformed URIs, send many valid requests, request expensive codecs, provide giant images, or create recursive transform chains. Controls: cheap parse-before-work validation, per-UID rate limit, per-UID and process-wide concurrency caps, pixel budget preflight, target byte and dimension ranges, bounded cache size, 24-hour expiry, and self-reference rejection. Long work should happen off Binder threads using an output pipe model. When capacity is exhausted, return `RATE_LIMIT`, `SYSTEM_BUSY`, or `PIXEL_BUDGET_EXCEEDED` instead of crashing.

### Elevation of privilege

A host may try to use ImageShare as a confused deputy to read data it could not otherwise access, or to access future premium transforms without authorization. Controls: ImageShare opens exactly the URI supplied in `source`, only after the platform grant exists; it does not enumerate, walk, or follow arbitrary links. Future premium decisions must be based on caller UID and platform permission state, not on tokens in the transform URI. No app dependency, premium gating, or publishing configuration is added by these docs.

## Controls inventory

- URI authority fixed to `com.imageshare.app.transform` and versioned under `/v1/`.
- Required `source` query parameter must decode to `content://`.
- Transform provider self-reference is rejected in both app parser and SDK request validation.
- Host must call `grantUriPermission("com.imageshare.app", source, FLAG_GRANT_READ_URI_PERMISSION)`.
- Errors use stable public codes: `MALFORMED_URI`, `MISSING_SOURCE`, `GRANT_LOST`, `UNSUPPORTED_FORMAT`, `UNSUPPORTED_VERSION`, `RATE_LIMIT`, `SYSTEM_BUSY`, `PIXEL_BUDGET_EXCEEDED`, and `PROCESSING_FAILED`.
- Rate limiting is per calling UID, with concurrency caps to protect CPU and memory.
- Decode bounds and dimension checks prevent catastrophic bitmap allocation.
- Cache is private, content-addressed, bounded, and time-limited.
- Metadata handling is explicit and defaults are not inferred by the URI layer.
- Synchronous SDK transform is annotated as worker-thread only, and async SDK APIs dispatch to I/O.
- Samples demonstrate background I/O and user-readable error handling.

## Open items

The RFC still identifies product questions around distribution, telemetry posture, partnership outreach, and future monetization. From a security perspective, the safest public posture is to keep v2.0 free, local, and telemetry-free; any later premium tier must go through a separate threat review. Additional open engineering items include out-of-process transform chain detection beyond first-level self-reference, final tuning of rate-limit defaults with real alpha data, golden compatibility samples for every released contract version, clear public guidance for hosts that need long-running or batch transforms, and backup-posture verification that `transform-cache` remains excluded from Android Auto Backup as documented in `docs\DATA_SAFETY.md`. None of these should block publishing the v1 threat model, but each should be tracked before stable launch.
