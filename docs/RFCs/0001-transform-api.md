# RFC 0001 — Transform API v2.0

- **Status:** Draft (pre-implementation; awaiting product/business sign-off on §7)
- **Author:** ImageShare core team
- **Target release:** ImageShare v2.0
- **Affects:** `:app` (new ContentProvider), `:core:processing` (reused as-is)
- **Supersedes:** the informal "ContentProvider design sketch" referenced in earlier planning notes
- **Related:** v1.0.0 release tag `a8bd904`, privacy policy at `docs/PRIVACY_POLICY.md`

---

## Introduction

ImageShare v1.0 is a standalone Android app: the user opens it, picks an
image (or batch), chooses a preset, processes locally, then shares the
result. The pipeline — `:core:processing`'s `Decoder` → `Resizer` →
`Encoder` (with `TargetSizeEncoder` for size-targeted runs) → `MetadataApplier`
— is fast, deterministic, fully on-device, and well-tested
(`EncoderResizerTest`, `TargetSizeEncoderTest`, `MetadataApplierTest`,
plus instrumented twins under `androidTest/`).

A recurring piece of feedback is that *other* apps would like to invoke this
pipeline without bouncing the user through ImageShare's UI: a mail client
wants to shrink an attachment to under 5 MB; a forum app wants to strip EXIF
before upload; a chat app wants long-edge-1600 JPEG q85. Today their only
option is `ACTION_SEND` round-tripping, which is interactive and breaks the
host app's flow.

The **Transform API** is a `ContentProvider` exposed by the ImageShare app
that lets a host app describe a transform declaratively in a URI, hand us a
`content://` source URI with a temporary read grant, and read back the
processed bytes through a normal `openInputStream` call — with no user
interaction and no foreground activity from ImageShare.

This RFC fixes the wire contract (URI schema, errors, versioning), the
security model, the caching policy, the sample integration shape, the
threat model, and the open product questions that must be resolved before
implementation begins. **It deliberately ships no code.** A follow-up RFC
will specify the provider implementation against the contract pinned here.

### Design goals

1. **Host integration in ~5 lines.** A `Uri.parse` plus an
   `openInputStream`. No SDK required for casual use.
2. **No user interaction in the host's path.** The provider must not start
   activities, show notifications, or require foreground service for
   on-demand single-image transforms. Batch / long-running variants are
   explicitly out of scope for v1 of the contract.
3. **Reuse `:core:processing` verbatim.** No new image code; the provider
   is a thin URI-to-`Pipeline` adapter. This keeps the v1.0 standalone
   product and the Transform API on a single, well-tested codepath.
4. **Preserve ImageShare's privacy posture.** No network, no analytics, no
   identifiers leave the device. The Transform API must not be the seam
   through which telemetry sneaks in (see §7).
5. **Additive rollout.** v2.0 must not change any v1.0 user-visible
   behavior. The Transform API is opt-in for hosts and invisible to
   ImageShare's own users unless they look at the new `debug-info` screen.

### Non-goals

- Real-time / streaming transforms (single-shot only).
- Cross-process bitmap sharing (we deal in encoded bytes).
- Server-side processing or fan-out.
- A general-purpose image-editing IPC surface; this is a *transform*
  endpoint, not a canvas.

---

<a id="section-1"></a>

## 1. URI schema specification (D1)

### 1.1 Authority and version

The provider's authority is derived from `applicationId`:

```
content://com.imageshare.app.transform/v1/...
```

The `applicationId` is `com.imageshare.app` (see `app/build.gradle.kts:14`),
so the authority is **`com.imageshare.app.transform`** and the version
segment **`/v1/`** is mandatory. The version segment is the contract
surface: anything documented under `/v1/` is stable; new behaviors land at
`/v1/` only if they are strictly additive (see §4). Anything backward-
incompatible bumps to `/v2/`.

### 1.2 Path grammar

```
/v1/{format}/{quality}/{resize}/{metadata}
```

Exactly four path segments after `/v1/`, in this fixed order. Positional
parsing keeps URIs short and grep-friendly; named parameters would push us
toward 200-character URIs that are awkward to share in bug reports.

#### `format` — required

| Token          | Mapping in `:core:processing`         | Notes                              |
|----------------|---------------------------------------|------------------------------------|
| `jpeg`         | `EncodeFormat.JPEG`                   | Default for photos.                |
| `png`          | `EncodeFormat.PNG`                    | Lossless, alpha-safe.              |
| `webp`         | `EncodeFormat.WEBP` (lossy)           | `quality` honored.                 |
| `webplossless` | `EncodeFormat.WEBP` (lossless)        | Any `qN` accepted; ignored at encode time (see §1.5 note). |
| `heif`         | `HeifEncoder` path                    | Subject to `HeifAvailability`.     |
| `avif`         | `NativeAvifEncoder` path              | Subject to `AvifAvailability`.     |

If a format is requested that the device cannot encode (`heif` on a device
where `HeifAvailability.isSupported()` is false, AVIF on pre-API-31), the
provider returns `IntakeError.UnsupportedFormat` (see §1.5).

#### `quality` — required

`q1` through `q100` inclusive. The integer is parsed and clamped to
`MIN_QUALITY..MAX_QUALITY` as enforced by `Encoder.encode`.

The literal **`qauto`** is allowed *if and only if* the query string carries
`targetBytes=N` with `N > 0`. In that case the provider routes through
`TargetSizeEncoder` and ignores the `q*` token entirely. Combining `qauto`
with a missing/zero `targetBytes` is a `MalformedUri` error.

#### `resize` — required

| Token             | Meaning                                          |
|-------------------|--------------------------------------------------|
| `original`        | No resize; pass through `Resizer` as identity.   |
| `longEdge{N}`     | Constrain longest edge to `N` px (aspect kept). |
| `exact{W}x{H}`    | Resize to exactly `W`×`H`. See `aspectLock`.     |
| `percent{N}`      | Scale to `N`% of original (`1 <= N <= 200`).     |

`N`, `W`, `H` are positive decimal integers. Upper bounds: `N <= 32768` for
longEdge, `W*H <= 200_000_000` for exact (matches the §2 DoS guard).
Out-of-range values yield `MalformedUri`.

#### `metadata` — required

| Token            | `MetadataMode`                  |
|------------------|---------------------------------|
| `stripall`       | `MetadataMode.STRIP_ALL`        |
| `preservesafe`   | `MetadataMode.PRESERVE_SAFE`    |
| `preserveall`    | `MetadataMode.PRESERVE_ALL`     |

Defaulting at the URI layer is deliberately disallowed: host apps must
make an explicit choice. `stripall` is recommended and is what ImageShare's
own UI defaults to.

### 1.3 Query parameters

| Name          | Required?            | Meaning                                                      |
|---------------|----------------------|--------------------------------------------------------------|
| `source`      | yes                  | URL-encoded source `content://` URI.                         |
| `targetBytes` | when `qauto`         | Positive integer; drives `TargetSizeEncoder`.                |
| `aspectLock`  | optional with `exact`| `true` (default) keeps aspect; `false` allows non-uniform.   |

`source` is **always required** and must URL-decode to a `content://` URI.
`file://` sources are rejected — they can't carry temporary URI grants and
they encourage path-traversal mistakes by hosts.

### 1.4 URI breakdown diagram

```
  content://  com.imageshare.app.transform  / v1 / jpeg / q85 / longEdge1600 / stripall  ? source=content%3A%2F%2F...&targetBytes=204800
  ─────────   ─────────────────────────────   ──   ────   ───   ────────────   ────────    ──────────────────────────  ──────────────────
   scheme           authority (provider)      ver  fmt   qual     resize       metadata     required (grant-bearing)   optional (qauto)
```

### 1.5 Parsing precedence and error model

URI validation runs in this fixed order; the first failure wins:

1. **Scheme/authority check** — anything not matching
   `content://com.imageshare.app.transform/*` is not even our problem
   (`UnsupportedOperationException` from `ContentProvider.query`).
2. **Version gate** — non-`v1` segment → `UnsupportedVersion`.
3. **Path arity** — not exactly four path segments under `/v1/` →
   `MalformedUri`.
4. **Token grammar** — per-segment regex (`q\d+`, `longEdge\d+`, etc.) →
   `MalformedUri`.
5. **Cross-field consistency** — `qauto` without `targetBytes`,
   `aspectLock` without `exact` → `MalformedUri`. Note: `webplossless`
   accepts any `qN`; the quality token is ignored at encode time because
   the lossless WebP encoder does not consume a quality knob. This is
   identical to `qauto`'s "token-ignored" behavior — the URI is
   unambiguous, so a cross-field rule would buy nothing but reject
   otherwise-valid URIs.
6. **Range checks** — quality in `1..100`, dimensions within DoS bounds.
7. **Source URI present and decodable** → otherwise `MissingSource`. The
   source authority **MUST NOT** equal `${applicationId}.transform`
   (i.e., our own provider authority): self-referencing source URIs are
   rejected with `MalformedUri`. See §6 row 13.
8. **Source URI grant present** at open time → otherwise
   `IntakeError.GrantLost`.
9. **Format/feature availability on device** → `UnsupportedFormat`.

Errors are surfaced two ways:

- For `query()` and `getType()`, return `null` for the unrecognized cases
  and a structured `MatrixCursor` of `(error_code, message)` for the
  parseable-but-rejected cases.
- For `openFile()` / `openInputStream()`, throw `FileNotFoundException`
  with a stable, parseable message prefix (`"ImageShareTransform: <code>: <msg>"`).
  A typed SDK wrapper (§5) translates these to `TransformError`.

---

<a id="section-2"></a>

## 2. Security model (D2)

The Transform API is one of the largest attack surfaces ever added to
ImageShare. The v1.0 app has *no* `INTERNET` permission and exports
nothing; v2.0 will deliberately expose a provider. This section pins the
mitigations before implementation begins.

### 2.1 Source URI permission grants

The host **must** call:

```kotlin
context.grantUriPermission(
    "com.imageshare.app",
    sourceUri,
    Intent.FLAG_GRANT_READ_URI_PERMISSION,
)
```

before opening the transform URI. At `openFile()` time the provider opens
the source via `ContentResolver.openInputStream(sourceUri)`. If the host
forgot the grant, the platform throws `SecurityException` and the provider
translates it to `IntakeError.GrantLost`. **The provider never falls back
to reading without the grant**, even if the source URI is in the host's
own provider — we treat the missing grant as a contract violation, not a
permission to be inferred.

### 2.2 Caller identification

For any decision that depends on *which* app is calling — premium gating,
rate limiting, cache partitioning — we use:

```
Binder.getCallingUid() → PackageManager.getPackagesForUid(uid)
```

We **never** trust caller-supplied extras, package names from the URI,
referrer headers, or anything else the caller controls. UID is the only
ambient identity the kernel guarantees.

### 2.3 Optional signature-level permission

A signature-level permission, **`com.imageshare.app.permission.TRANSFORM_PRO`**,
gates premium-tier transform paths (e.g., AVIF at high quality, target-byte
encoding above a threshold) *if* §7's monetization question lands on
"premium API tier". Standard (free) presets do not require any permission;
the URI grant alone is sufficient. The permission is signature-level rather
than runtime so the user is never prompted — the host either has the
matching signing certificate (or a server-issued voucher we accept) or it
doesn't.

If §7 lands on "everything free", this permission is simply never declared
and the gating code path is dead. The URI contract does not change either
way; only the set of accepted `format`/`targetBytes` combinations does.

### 2.4 Per-UID rate limiting

A naive provider invites a single misbehaving host to chew through the
device's CPU and battery. Defaults (tunable via `BuildConfig`):

- **100 transforms / minute / UID** (sliding window).
- **2 concurrent transforms / UID**.
- **8 concurrent transforms process-wide** (caps total CPU pressure).

Implementation: in-memory `LruCache<Int, RateState>` keyed by `callingUid`,
swept on every request and on `onTrimMemory`. Excess requests return
`IntakeError.RateLimited` with a `retryAfterMs` hint in the cursor /
exception message. No persistent counters: a process restart resets the
window, which is acceptable because Android can kill us anyway and we'd
otherwise need disk writes on every transform.

### 2.5 DoS / OOM surface

The provider's worst case is a host handing us a multi-gigapixel source.
Mitigations in order:

1. **Bounds pre-flight.** Before any real decode, call `BitmapFactory`
   with `Options.inJustDecodeBounds = true` against the source stream.
   Reject when `outWidth * outHeight > 200_000_000` (configurable via
   `BuildConfig.MAX_PIXELS`). This is the single most important guard;
   the `:core:processing` `Decoder` already does sample-size math but it
   assumes a sane input.
2. **Concurrent-transform cap** (see §2.4) limits how many large bitmaps
   we can be holding at once.
3. **Cache-dir isolation** (see §3) ensures even successful giant outputs
   don't accumulate on disk.
4. **Foreground-service refusal.** The provider is synchronous from the
   host's perspective; if the work would exceed an internal SLA (e.g.,
   30 s wall clock), we abort with `IntakeError.Timeout` rather than
   promoting to a foreground service. Long-running batches stay in the
   standalone app's existing batch flow.

### 2.6 What we are *not* trying to prevent

- A host app that has legitimately been granted access to a sensitive
  source URI by the user, and chooses to transform it through us, can
  obviously see the result. That is the entire point. We are not a DRM
  boundary.
- We do not attempt to detect "abusive" but-within-quota query patterns
  (e.g., 99 transforms/minute every minute forever). The rate limit is a
  blunt instrument; sophistication here is yagni for v1.

---

<a id="section-3"></a>

## 3. Caching policy (D3)

The pipeline is deterministic: same source bytes + same params ⇒ same
output bytes. That makes a content-addressed cache both safe and very
effective for the expected workload (chat clients re-uploading the same
sticker; mail clients regenerating the same thumbnail).

### 3.1 Cache key

```
key = SHA-256( source_content_bytes || 0x00 || canonical_params_utf8 )
```

`canonical_params_utf8` is the URI's path-plus-relevant-query, normalized:
lowercase tokens, `targetBytes` rendered in decimal, `aspectLock` defaulted
explicitly, `source` excluded (it's hashed via its bytes, not its URI
string — two different content URIs pointing at byte-identical sources
should share a cache slot).

We hash the full source bytes, not a partial prefix. Partial-hash
shortcuts open us to crafted collisions (see §6 cache-poisoning row);
SHA-256 at full length is cheap relative to the decode/encode that follows.

### 3.2 Cache location

`context.cacheDir/transform-cache/{key-prefix}/{key}.bin` plus a sibling
`.meta` file holding `(mime, params, callingUid, createdAtMs)`.

`cacheDir` is per-app private storage, ABI-isolated, and *Android cleans it
when storage is low* — which is exactly the right semantics for an
opportunistic cache. The existing v1.0 app already uses `cacheDir/incoming/`
and `cacheDir/output/` for its own working files; `transform-cache/` is a
peer.

### 3.3 FIFO eviction (with 24 h hard expiry)

Bounded by **total bytes**, default **200 MB** (configurable via
`BuildConfig.TRANSFORM_CACHE_MAX_BYTES`). When a write would exceed the
cap, evict by oldest `createdAtMs` until under cap — i.e., **FIFO, not
LRU**. Additionally, any entry with `createdAtMs > 24 h ago` is pruned
on the sweep path (§3.4) regardless of pressure. Eviction is
synchronous on the writer's thread — simple, predictable, and avoids a
background thread that the OS might reap.

### 3.4 24-hour sweep

A `WorkManager`-free coroutine (we cannot add deps and we already do
this for `cacheDir/output/` in v1.0) walks `transform-cache/` and
deletes entries with `createdAtMs` older than 24 h. This matches the
privacy-policy statement: *"Cached copies of incoming and processed
images (auto-cleared after 24 hours)"*. Keeping the Transform API under
the same window means no privacy-policy edit is needed.

**When the sweep runs.** The provider may be invoked entirely via IPC
without ImageShare's `Application.onCreate` ever executing on the
critical path — `ContentProvider.onCreate` runs *before*
`Application.onCreate` when a process is IPC-started for a provider
query. Tying the sweep to `Application.onCreate` would therefore miss
IPC-only invocation paths. Instead, the sweep is scheduled from
`TransformProvider.onCreate` itself, gated by a sentinel file
(`transform-cache/.last-sweep`) whose mtime is consulted: if the last
sweep was >24 h ago, the coroutine runs and updates the sentinel.
This guarantees the sweep runs at least once per 24-h window on any
process that ever hosts the provider, whether reached via UI or IPC.

### 3.5 Per-host partitioning (optional)

Default v1 behavior: shared cache, keyed only by content hash. This
maximizes hit rate across hosts.

If a measurement shows that one heavy host evicts other hosts' useful
entries, we can flip to a per-UID partition (`transform-cache/{uid}/...`)
with per-UID caps. This is a single-flag change; the URI contract does
not change. **Decision deferred until we have real traffic data, which
we won't have until we ship — so this is a v2.1 knob, not v2.0.**

### 3.6 Cache lifecycle diagram

```
   Host                Provider                 Cache (cacheDir/transform-cache)
   ───────────         ────────────────         ────────────────────────────────
   open(uri) ─────▶    parse + validate
                       │
                       ▼
                       hash source bytes ──────▶  key lookup
                       │                         │
                       │              hit ◀──────┘
                       │               │
                       │               ▼
                       │           stream cached bytes (no metadata write —
                       │           FIFO eviction means hits do not touch
                       │           any timestamp; see §3.3, §3.7)
                       ◀───────────────┘
                       │
                       │ miss
                       ▼
                       Decoder → Resizer → Encoder → MetadataApplier
                       │
                       ▼
                       write {key}.bin + {key}.meta  ──▶  evict-while-over-cap
                       │
                       ◀── stream fresh bytes
                       │
   bytes ◀─────────────┘
                                                          ╱
                                            (on next provider onCreate
                                             whose sentinel mtime is
                                             > 24 h: walk + delete > 24 h)
```

### 3.7 Why FIFO and not LRU

A real LRU would require writing `lastAccessMs` back to disk (or fsync-ing
a sidecar) on every cache *hit*. For an opportunistic disk cache backing
an IPC pipeline that may serve hundreds of hits per minute on a busy
device, write-on-hit is real flash wear and real I/O latency on the
binder path. The eviction-policy precision LRU buys over FIFO is small
for content-addressed caches with a 24-h hard expiry — the working set
turns over on a wall-clock timer, not an access timer. We accept FIFO
imprecision (a hot entry can be evicted under pressure before a cold one
that simply happens to be younger) as the right trade against the
write-on-hit cost. If real-world cache hit-rate measurement in v2.1
shows FIFO costing >10 percentage points of hit rate, a true LRU with
batched, debounced metadata writes is a single-PR upgrade — the on-disk
layout already carries enough fields (`createdAtMs` plus the planned
`lastAccessMs` re-introduction) for the change to be additive.

---

<a id="section-4"></a>

## 4. Versioning policy (D4)

The Transform API is the first piece of ImageShare with a real wire
contract. We are going to live with these decisions for years, so the
policy errs on the conservative side.

### 4.1 Path-based versioning

The `/v1/` path segment is the contract. The rules:

- **Additive change** (new format token, new metadata mode, new optional
  query parameter): lands under `/v1/`. Hosts that don't know about the
  new feature are unaffected; hosts that do, opt in by using it.
- **Bugfix — additive** (output already matched documented behavior; the
  fix is internal, e.g., a crash on a malformed input that we now reject
  cleanly): lands under `/v1/`. No golden-sample edit needed; the
  existing samples continue to pass.
- **Bugfix — corrective** (output bytes change because we are aligning
  with intended-but-undocumented behavior, e.g., a codec quantization
  table fix that shifts every JPEG byte): lands under `/v1/` but requires
  all of:
  - Reviewer sign-off on a PR description documenting the
    original-vs-corrected behavior delta and why it is "corrective" and
    not "breaking".
  - Golden-sample replacement with **both** the old sample (renamed
    `case-X.before-fix-{commit-sha}.bin`) and the new
    (`case-X.bin`) committed in the same PR, so `git log` answers
    "what changed and when".
  - A release-note callout in `CHANGELOG.md` flagged as a corrective
    bugfix so integrators with their own byte-comparisons are warned.
- **Breaking change** (renaming a token, changing a default, changing a
  field's meaning, retiring a format, or a semantic change that hosts
  could reasonably depend on): requires a new path segment (`/v2/`)
  served *alongside* `/v1/` during the 12-month deprecation window.

### 4.2 Deprecation procedure

When we decide to retire a contract version:

1. **Announce** in `docs/RFCs/{N}-deprecate-vX.md` with a hard end-of-life
   date no sooner than **12 months** out.
2. **SDK** (§5): bump the library's major version; mark the affected
   builder methods `@Deprecated(level = WARNING)` with a link to the RFC.
3. **App**: continue serving `/vX/` for the full window. Add a one-shot
   `Log.w` (no telemetry, no UI) on first `/vX/` query per process so a
   developer attaching `adb logcat` sees the deprecation notice. Note
   that "once per process" can effectively mean "once per IPC-spawned
   transform" when our provider-hosting process is started cold for a
   single query and then reaped by Android — this is not a bug, just an
   explicit consequence of provider lifecycle; a developer running a
   logcat session across multiple host invocations will see the warning
   repeat.
4. **End-of-life**: on the announced date, `/vX/` requests return
   `IntakeError.UnsupportedVersion` with a "gone" code (analogous to HTTP
   410). The codepaths are removed in the next release.

12 months is a deliberate compromise: long enough that hosts can fold an
upgrade into a normal release cycle, short enough that we are not stuck
maintaining `/v1/` forever if a real flaw is found.

### 4.3 Backward-compatibility tests

Every contract version owns a directory of **golden samples**:

```
:app/src/test/resources/transform-api/v1/golden/
    case-jpeg-q85-le1600-stripall.json   # URI + source-fixture id
    case-jpeg-q85-le1600-stripall.bin    # expected output bytes
    ...
```

A `TransformContractV1Test` runs every case end-to-end through the
provider (in Robolectric where feasible, instrumented otherwise) and
byte-compares the output. Once a sample is committed, it is **never
silently edited** — a corrective bugfix (§4.1) may replace it, but only
with the reviewer sign-off, the `case-X.before-fix-{sha}.bin` preserved
alongside, and the CHANGELOG callout described in §4.1. A test that
"needs" to be updated through any other path is a contract break in
disguise.

Golden directories for retired contract versions are kept in the repo
forever. They are cheap, they document history, and they let us answer
"did `/v1/` actually do X in 2027?" with a `git log` instead of a guess.

---

<a id="section-5"></a>

## 5. Sample integration code (D5)

### 5.1 Kotlin (raw, no SDK)

```kotlin
import android.content.Context
import android.content.Intent
import android.net.Uri

fun transform(context: Context, source: Uri): ByteArray? {
    val target = Uri.parse(
        "content://com.imageshare.app.transform/v1/jpeg/q85/longEdge1600/stripall" +
            "?source=" + Uri.encode(source.toString())
    )
    context.grantUriPermission(
        "com.imageshare.app", source, Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )
    return try {
        // NOTE: do NOT revoke `source` until readBytes() has returned.
        // The provider opens `source` lazily during the openInputStream
        // pipe drain (see §6 row 10 — binder thread returns a
        // ParcelFileDescriptor pipe, real work happens on Dispatchers.IO),
        // so revoking before the pipe is fully drained races the read
        // and yields `IntakeError.GrantLost`. The pipe model means the
        // `finally`-block revoke below is safe: readBytes() blocks until
        // the pipe is closed, which only happens after the provider is
        // done with `source`.
        context.contentResolver.openInputStream(target)?.use { it.readBytes() }
    } catch (e: java.io.FileNotFoundException) {
        null  // parse "ImageShareTransform: <code>: ..." from e.message if needed
    } finally {
        context.revokeUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
```

### 5.2 Java (raw, no SDK)

```java
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public static byte[] transform(Context context, Uri source) {
    Uri target = Uri.parse(
        "content://com.imageshare.app.transform/v1/jpeg/q85/longEdge1600/stripall"
            + "?source=" + Uri.encode(source.toString()));
    context.grantUriPermission(
        "com.imageshare.app", source, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try (InputStream in = context.getContentResolver().openInputStream(target)) {
        // NOTE: do NOT revoke `source` before this try-block completes.
        // The pipe model (§6 row 10) means the provider may still be
        // reading `source` while we are draining its output pipe; the
        // finally-revoke below runs only after `in.close()` returns,
        // which is the safe point. Revoking earlier risks GrantLost.
        if (in == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    } catch (Exception e) {
        return null;
    } finally {
        context.revokeUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
}
```

### 5.3 Optional client library

For hosts that prefer typed APIs:

- **Artifact:** `com.imageshare:imageshare-api:1.0.0`, target ~5 KB AAR.
- **Distribution:** Maven Central (decision pending; see §7).
- **Surface:**
  - `TransformRequest.Builder()` — fluent builder for the URI; validates
    cross-field constraints at build time.
  - `TransformClient.execute(context, request)` — returns
    `Result<TransformResponse, TransformError>` (Kotlin) or throws
    `TransformException` (Java).
  - `TransformError` — sealed class mirroring the §1.5 error model
    (`MalformedUri`, `MissingSource`, `GrantLost`, `RateLimited`,
    `UnsupportedFormat`, `UnsupportedVersion`, `Timeout`, `Unknown`).
- **No transitive deps** beyond `androidx.annotation`. The library is
  pure-Kotlin URI construction plus error parsing; it never touches
  bitmaps itself.

### 5.4 AndroidX-style `ActivityResultContract`

For hosts already wired into the result-contract pattern:

- `TransformContract : ActivityResultContract<TransformRequest, TransformResponse>`
- Internally it uses the provider, **not** an activity — the contract
  shape is borrowed for ergonomics; there's no UI hop. The launcher
  invokes a coroutine that does the same `openInputStream` dance and
  delivers the result on `ActivityResultCallback`. This keeps integration
  parallel to `ActivityResultContracts.GetContent` and friends.
- **Activity / Fragment requirement.** The wrapper *must* be registered
  via `registerForActivityResult` on an `Activity` or `Fragment` because
  callers expect lifecycle-safe callbacks — the result is delivered
  through the host's lifecycle owner, exactly as with first-party
  contracts. This is a constraint of the contract pattern, **not** of
  the underlying provider call: §5.1/§5.2 show that the raw provider
  works fine from any `Context`, including a `Service` or `BroadcastReceiver`.
  The wrapper's KDoc must call this out explicitly so hosts that cannot
  satisfy the Activity requirement know to use the raw API or §5.3's
  `TransformClient.execute` instead.

---

<a id="section-6"></a>

## 6. Threat model (D6)

| # | Threat                          | Vector                                                        | Mitigation                                                                                                            |
|---|---------------------------------|---------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| 1 | Source-URI exfiltration         | Malicious host invokes us on URIs it doesn't own              | URI permission grants required; provider opens source only via `ContentResolver` and propagates `SecurityException`.   |
| 2 | Result-bytes leak               | Output cached on disk after host consumes it                  | Cache lives in `cacheDir/` (per-app, private); filenames are SHA-256 hashes; FIFO eviction + 24 h sweep (§3.3, §3.4).  |
| 3 | DoS via spammed queries         | Host hammers provider with garbage URIs or transforms         | Per-UID rate limit (100/min); per-UID concurrent cap (2); process-wide cap (8); cheap parse-then-reject path.          |
| 4 | Caller spoofing                 | Untrusted host claims to be a known/premium host              | `Binder.getCallingUid()` + `PackageManager.getPackagesForUid`; signature-level permission for premium gating (§2.3).   |
| 5 | Privacy regression in host      | Host assumes metadata is preserved but our default strips     | `metadata` segment is required (§1.2); there is no default. Stripping is opt-in via `stripall`, not implicit.          |
| 6 | Cache poisoning                 | Crafted source hashes to a legitimate result's slot           | Full SHA-256 over full source bytes + canonicalized params; no truncation; meta-file consistency check on read.       |
| 7 | Catastrophic bitmap allocation  | Host passes a 100 000 × 100 000 source                        | `BitmapFactory` bounds pre-flight; reject when `w*h > 200_000_000`; aligned with §1.2 dimension caps.                  |
| 8 | Library version skew            | Host targets `/v1/` but installed app is too old to support it| Provider returns `UnsupportedVersion`; SDK exposes `Transform.requiredAppVersion(...)` so hosts can detect and degrade.|
| 9 | Source-URI confused deputy      | Host tricks us into reading a URI it has access to but the user didn't intend for us to process | We only ever read what the host explicitly hands us *and* explicitly grants; we do not enumerate, walk, or follow.    |
| 10 | Long-running work blocks IPC   | Host's binder thread starves while we encode a 4K AVIF        | All processing on `Dispatchers.IO`; the binder thread parses, dispatches, and returns a `ParcelFileDescriptor` pipe.   |
| 11 | Output covert-channel          | A malicious host inside an isolated user profile uses us as a way to ferry data across profiles | Provider is per-user (Android already isolates `cacheDir/` per profile); we add no cross-profile state of our own.    |
| 12 | Format-feature fingerprinting  | Host enumerates which encoders/quality levels our device supports as a device fingerprint | Acceptable risk: this information is already available via `MediaCodecList` and similar public APIs.                  |
| 13 | Source-URI self-reference / chain | Host constructs `source=` URI pointing at our own provider's path, potentially chained N levels deep, causing recursive `openInputStream` deadlock (provider waiting on itself) and multiplicative work | Reject any `source` whose authority equals `${applicationId}.transform` at parse time (§1.5 step 7) → `MalformedUri`. Out-of-process chain detection (host → us → them → us) is deferred to v2.1; first-level rejection is sufficient because deeper chains require multiple per-UID concurrency slots which we don't grant (§2.4 caps at 2/UID, 8 process-wide). |

### 6.1 Out-of-scope threats

- **The user themselves is hostile.** Out of scope; they can already use
  the standalone app.
- **Physical-access attackers reading `cacheDir/` on an unlocked device.**
  Out of scope; Android's app-sandbox + lockscreen is the boundary.
- **Side-channel timing attacks** to infer source content. The pipeline's
  timing is dominated by content size, which the host already knows.

---

<a id="section-7"></a>

## 7. Open product / business questions (D7)

These must be resolved before implementation, because they shape the
URI contract (premium gating in §2.3), the SDK surface (§5), and the
threat model (§6.12 trades against telemetry decisions).

### 7.1 Monetization model

Options, roughly in order of preference from the engineering side:

- **A. Fully free, Apache-2.0** — matches the standalone app's posture;
  zero new business machinery; maximum adoption. Pays nothing.
- **B. Same one-time-Pro IAP as the standalone app** — host triggers a
  user-facing upgrade flow inside ImageShare the first time it requests
  a premium preset. Awkward: it breaks the "no user interaction" goal.
- **C. Separate API-tier license** — server-issued vouchers, signed,
  cached locally; host calls a free fast-path for free presets and a
  voucher-gated path for premium. Highest business upside, highest
  implementation cost, and introduces a server we don't currently run.

**Recommendation:** ship as A for v2.0. Revisit if we see commercial
hosts in the wild. If we later move to B or C, the URI contract does not
change — only the set of accepted `(format, quality, targetBytes)`
tuples for un-licensed hosts narrows.

### 7.2 Branding

- **A. "ImageShare's Transform API"** — keeps brand equity in one place.
- **B. Separate product identity ("ImageShare Forge", "ImageShare
   Workshop", etc.)** — clearer marketing story to developers; muddier
   relationship with the consumer app.

**Recommendation:** A, for at least the v2.0 launch. Sub-brand only if
the developer audience materially outgrows the consumer one.

### 7.3 Telemetry

ImageShare v1.0's privacy policy is unambiguous: *"We do not use analytics
or crash reporting. … We do not access the network. Our app has no
INTERNET permission."* That stance is a load-bearing part of the brand.

- **A. Stay zero-telemetry.** No counters leave the process. The
   `debug-info` screen (new in v2.0) surfaces an in-process tally —
   number of transforms today, top calling packages, cache hit rate —
   so users can audit what's happening, but nothing is reported.
- **B. Opt-in anonymous metrics.** Requires `INTERNET` permission, which
   changes the manifest, the data-safety declaration, and the marketing
   pitch. Hard to undo.

**Recommendation:** A. The `debug-info` screen is good UX hygiene
regardless and gives developers something concrete to point at when
their host's QA asks "what is this app doing with our images?".

### 7.4 Documentation hosting

- GitHub Pages off the existing repo (cheap, already where everything
  else lives).
- Dedicated `imageshare.dev` (or similar) (better SEO; ongoing cost).
- Both, with Pages as canonical and a marketing redirect.

**Leaning:** GitHub Pages for v2.0; dedicated domain only if adoption
warrants.

### 7.5 Partnership outreach

Once the SDK ships, the highest-leverage targets are open-source Android
apps where a maintainer can integrate and merge a PR themselves: Markor,
Tusky, Element, K-9 Mail, FairEmail, NewPipe, Open Camera, Frost,
Simple Gallery's successor. The order matters less than the consent of
each project's maintainer — outreach should be a PR-with-explanation,
never a drive-by.

**Special case: K-9 Mail and FairEmail.** Both projects maintain
no-non-libre-network-dependencies policies and historically refuse
artifacts pulled from Maven Central where the build cannot be reproduced
from source within their repository. For these two, the recommended
outreach is a *vendored-sources* path: offer the SDK's small (~5 KB
AAR's worth of) Kotlin sources as a copy-in patch they can vendor under
their own license-compatible tree, rather than as a Gradle dependency.
The URI contract is the real interface; the SDK is just ergonomic
sugar, so vendoring loses nothing meaningful.

### 7.6 SDK distribution

- **Maven Central** — table-stakes for Android libraries; ~1 day of
  one-time setup (Sonatype OSSRH, GPG signing).
- **JitPack** — zero setup, but second-class for serious hosts.
- **Sonatype Snapshots** — useful for `-SNAPSHOT` previews alongside
  Central releases.

**Recommendation:** Maven Central for stable; Sonatype Snapshots for
nightlies. Skip JitPack.

### 7.7 API stability commitment

Codify the **12-month minimum support window** for any released
contract version (see §4.2) in the published docs. This is the single
commitment most likely to make a host say yes.

### 7.8 Telemetry-free vs. lightly-instrumented (resolved)

This duplicates §7.3 and is listed for completeness with the prompt;
recommendation is the same — stay telemetry-free, surface in-process
metrics via the `debug-info` screen.

---

## 8. Migration / activation plan

Transform API is **purely additive** — it ships alongside v1.0's
existing UI, intent handlers, share-target, and batch pipeline without
changing any of them. Activation in production follows the staged plan
below.

### 8.1 Code organization

- New module: **`:app:transform`** under `app/src/main/java/...`. Holds
  the provider, the URI parser, the rate limiter, and the cache.
- **`:core:processing`** is referenced as-is. `Decoder`, `Resizer`,
  `Encoder`, `TargetSizeEncoder`, `MetadataApplier` are reused verbatim;
  no source edits in that module are required by this RFC.
- Provider implementation **must not** add new dependencies to
  `app/build.gradle.kts` for v2.0 — everything needed
  (`androidx.core`, `androidx.heifwriter`, the JNI AVIF/JPEG paths) is
  already present for the standalone app.

### 8.2 Rollout phases

1. **v2.0-alpha1 — provider behind a kill switch.** Provider is declared
   in the manifest with `android:enabled="false"` overlaid by a
   `BuildConfig.TRANSFORM_API_ENABLED` flag flipped on only in alpha
   builds. Used for internal testing; not advertised externally.
2. **v2.0-alpha2 — opt-in via in-app developer-options.** A hidden
   developer-options screen (long-press on the about-version row) toggles
   the kill switch at runtime so power users can validate without an
   alpha-only build. **Promotion gate from alpha1 → alpha2:** the rate-
   limit defaults (100/min/UID, 2 concurrent/UID, 8 process-wide; §2.4)
   must be measured against alpha-tester in-process counter data
   surfaced via the `debug-info` screen and harvested from logcat in
   alpha builds. Documented thresholds — concretely, what the observed
   p95 and p99 per-UID and process-wide rates were across the alpha
   cohort, and whether any tester legitimately tripped a limit — must
   be recorded in the alpha2 release notes *before* alpha2 ships. If
   the numbers say the limits are wrong, they are tuned then, not
   after stable.
3. **v2.0-beta — kill switch on by default.** Provider is enabled in the
   manifest. SDK published to Sonatype Snapshots. Sample host app open-
   sourced under `samples/transform-host/` in a separate repo (this repo
   stays focused on the consumer app).
4. **v2.0 stable.** Provider enabled, SDK on Maven Central, RFC published
   to GitHub Pages, partnership outreach (§7.5) begins.

### 8.3 Risk to the standalone app

- **Manifest**: this RFC explicitly does **not** modify
  `AndroidManifest.xml` yet; the provider declaration is a v2.0
  implementation task.
- **Permissions**: no new user-visible permissions. Signature-level
  `TRANSFORM_PRO` (§2.3) is declared only if §7.1 picks option B or C.
- **Cache directories**: new `cacheDir/transform-cache/` sub-tree is
  isolated from v1.0's `incoming/` and `output/` directories. The 24 h
  sweep already documented in the privacy policy covers it.
- **Privacy policy**: no edit required — the existing line about cached
  images already covers the new sub-tree.
- **Test surface**: new `TransformContractV1Test` + per-segment parser
  tests under `:app`. Existing `:core:processing` tests are untouched.

### 8.4 Rollback

Because the provider is gated behind `BuildConfig.TRANSFORM_API_ENABLED`
through alpha and beta, a regression caught late can be disabled with a
single-line config flip and a hotfix release; the manifest stays
identical between "enabled" and "disabled" builds in v2.0 stable, with
the runtime check happening in `ContentProvider.onCreate`. Hosts that
have integrated will receive `UnsupportedVersion` while the flag is off,
which their SDK already knows how to surface.

---

## 9. Conclusion

The Transform API gives ImageShare's well-tested local pipeline a second
front door — one that does not require the user to leave the host app.
The hard parts are not the implementation (the provider is a thin
adapter on top of `:core:processing`) but the contract and the policy
choices around security, caching, versioning, and monetization.

This RFC pins the contract. The follow-up RFC will pin the
implementation, and only once §7 is resolved.

### Decision summary

| Decision area  | This RFC's position                                                         |
|----------------|-----------------------------------------------------------------------------|
| URI schema     | `content://com.imageshare.app.transform/v1/{fmt}/{q}/{resize}/{meta}?source=…` |
| Versioning     | Path-based `/v1/`; 12-month deprecation window; golden-output contract tests |
| Security       | URI grants required; UID-based identity; per-UID rate + concurrency caps    |
| DoS            | 200 Mpx pre-flight cap; 2 concurrent/UID; 8 concurrent process-wide         |
| Caching        | `cacheDir/transform-cache/`, SHA-256 keys, 200 MB FIFO, 24 h sweep           |
| Telemetry      | None. `debug-info` screen surfaces in-process counters only.                 |
| Monetization   | Free for v2.0; revisit on real adoption data                                 |
| Distribution   | Maven Central (stable) + Sonatype Snapshots (nightly)                        |
| Manifest delta | **None in this RFC.** Implementation RFC adds the provider declaration.      |
