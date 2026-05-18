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

A host can spam cheap malformed URIs, send many valid requests, request expensive codecs, provide giant images, or create recursive transform chains. Controls: cheap parse-before-work validation, per-UID 100 requests/minute and 2 concurrent requests, 8 concurrent requests process-wide, pixel budget preflight, target byte and dimension ranges, 200 MB hard disk cache budget, 2000 cache entry hard count, 24-hour hard expiry, FIFO cache eviction by `createdAtMs`, and self-reference rejection. Long work should happen off Binder threads using an output pipe model. When capacity is exhausted, return `RATE_LIMIT`, `SYSTEM_BUSY`, or `PIXEL_BUDGET_EXCEEDED` instead of crashing.

### Elevation of privilege

A host may try to use ImageShare as a confused deputy to read data it could not otherwise access, or to access future premium transforms without authorization. Controls: ImageShare opens exactly the URI supplied in `source`, only after the platform grant exists; it does not enumerate, walk, or follow arbitrary links. Future premium decisions must be based on caller UID and platform permission state, not on tokens in the transform URI. No app dependency, premium gating, or publishing configuration is added by these docs.

## Abuse scenarios

### D2 — Denial of service: rate flooding
- AD1 calls API rapidly to exhaust ImageShare's resources.
- **Control C2**: per-UID 100/min + 2 concurrent, process-wide 8 concurrent (`TransformRateLimiter`).
- **Residual**: ImageShare's UI may degrade during sustained AD1 flood; rate limiter prevents OOM but not all latency impact.

### D3 — Denial of service: cache fill
- AD1 generates many distinct cache keys to evict legitimate entries.
- **Control C5**: 200 MB hard budget + 2000 entry hard cap + 24h hard expiry + FIFO by `createdAtMs` (`TransformCache`).
- **Residual**: AD1 can fill cache; legitimate entries get evicted earlier; tolerable because cache is best-effort.

## Controls inventory

| ID | Control | Implementation | RFC ref | Tested by |
|----|---------|----------------|---------|-----------|
| C1 | `checkUriPermission` on source URI | `app\src\main\java\com\imageshare\app\transform\TransformContentProvider.kt:286` (`callerCanReadSource`) | §2.2 | `app\src\androidTest\java\com\imageshare\app\transform\TransformContentProviderTest.kt:94` (`callerWithoutGrantOnSourceThrowsGrantLost`) |
| C2 | Per-UID + process-wide rate limit | `app\src\main\java\com\imageshare\app\transform\TransformRateLimiter.kt:6`; wired at `app\src\main\java\com\imageshare\app\transform\TransformContentProvider.kt:332` (100/min/UID, 2 concurrent/UID, 8 process-wide from `app\build.gradle.kts:22-24`) | §2.4 | `TransformContentProviderTest.kt:128` (`perUidRateLimitRejects101stTransform`), `TransformContentProviderTest.kt:137` (`getTypeReturnsNullUnderRateLimit`) |
| C3 | Pre-flight pixel budget (200 Mpx) | `app\src\main\java\com\imageshare\app\transform\TransformContentProvider.kt:138` (`transformToFile`), `TransformContentProvider.kt:232` (`enforcePixelBudget`), `app\build.gradle.kts:25` | §2.5 | `TransformContentProviderTest.kt:122` (`oversizedSourceThrowsPixelBudgetExceeded`) |
| C4 | `targetBytes` upper bound (100 MB) | `app\src\main\java\com\imageshare\app\transform\TransformUriParser.kt:104` (`parseTargetBytes`), `app\build.gradle.kts:26` | §2.5 | `TransformUriParserTest.kt:36` (`parsesTargetBytesUpperBound`), `TransformUriParserTest.kt:94` (`targetBytesOverUpperBoundRejected`) |
| C5 | Cache key includes `callerUid` | `app\src\main\java\com\imageshare\app\transform\TransformCache.kt:28` (`key`) | §3.4 | `app\src\test\java\com\imageshare\app\transform\TransformCacheTest.kt:33` (`keyDiffersByCallerUid`) |
| C6 | Self-referencing source URI rejected | `app\src\main\java\com\imageshare\app\transform\TransformUriParser.kt:49` | §1.5 step 7 | `TransformUriParserTest.kt:73` (`selfReferenceSourceIsMalformed`), `TransformUriParserTest.kt:110` (`sourceSelfAuthorityRejected`) |
| C7 | Kill switch | `app\build.gradle.kts:21` (`TRANSFORM_API_ENABLED`), gates `app\src\main\java\com\imageshare\app\transform\TransformContentProvider.kt:35`, `app\src\main\java\com\imageshare\app\transform\TransformContentProvider.kt:48`, `app\src\main\java\com\imageshare\app\transform\TransformContentProvider.kt:78`, `app\src\main\java\com\imageshare\app\transform\TransformContentProvider.kt:92` | §7.2 | (compile-time gate) |
| C8 | Signature permission declared (passive) | `app\src\main\AndroidManifest.xml:5` (`com.imageshare.app.permission.TRANSFORM_PRO`) | §7.1 | (manual) |

## Open items

The RFC still identifies product questions around distribution, telemetry posture, partnership outreach, and future monetization. From a security perspective, the safest public posture is to keep v2.0 free, local, and telemetry-free; any later premium tier must go through a separate threat review. Additional open engineering items include out-of-process transform chain detection beyond first-level self-reference, final tuning of rate-limit defaults with real alpha data, golden compatibility samples for every released contract version, clear public guidance for hosts that need long-running or batch transforms, and backup-posture verification that `transform-cache` remains excluded from Android Auto Backup as documented in `docs\DATA_SAFETY.md`. None of these should block publishing the v1 threat model, but each should be tracked before stable launch.
