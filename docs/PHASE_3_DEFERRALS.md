# Phase 3 Gate Findings — Deferred Items

This document tracks gate findings from the Phase 3 audit that are deliberately deferred. Each item links to the gate review that surfaced it and indicates the natural home for the fix.

## Resolved in v10-design-polish

- **P1-D5/P2-D7**: FilterChip a11y now preserves selected-state semantics and uses a 48dp minimum touch target. ✅
- **P1-D8/P2-D6**: Idle batch status chrome is hidden. ✅
- **P2-D8**: `CustomDimensionsCard` header now has a rotating chevron, button role, and expanded/collapsed state semantics. ✅
- **P2-D9/D11**: `CustomDimensionsCard` lock-aspect and allow-upscaling controls are toggleable rows with merged switch semantics. ✅
- **P2-D12**: Disabled Save copy button no longer applies manual alpha over Material disabled colors. ✅
- **P2-D15**: Size and dimension formatters now use locale-aware `NumberFormat` for non-ASCII digits. ✅
- **P3-D8**: Foreground batch notification title is branded as ImageShare. ✅
- **P3-D13**: Recents `LazyRow` now uses vertical padding instead of a tight height cap, leaving focus-ring breathing room. ✅
- **P3-D14**: Audited `getStringOrFallback`; the helper has no documented real-crash rationale for the `runCatching` wrapper. Defer source cleanup to v1.1 because `BatchProcessWorker.kt` is owned by the parallel perf-polish pass in this cycle.
- **P3-D15**: Open documents button no longer duplicates the tooltip as its accessibility name. ✅

## Resolved in v10-perf-polish

- **P1-P1**: `Encoder.flattenAlpha` now flattens through `Canvas.drawColor + drawBitmap`.
- **P1-P2**: `Decoder` now consolidates bounds, orientation, and alpha probing into a single buffered metadata pass, with documented reset fallback.
- **P2-P1**: `ComparisonScreen` now remembers Coil `ImageRequest`s across slider recompositions.
- **P3-P4**: `BatchProcessWorker` now samples progress emissions at 100 ms and applies the final completion emission synchronously.
- **P3-P2/P3**: Macrobench journeys now cover share-intent batch processing and SAF/recents rendering scaffolds.

## Architecturally-deferred items

- **P1-P3**: `Encoder` ByteArray double-buffer remains deferred. Streaming directly into `MetadataApplier`'s temp file would couple the isolated encoder API to metadata application for a bounded encoded payload allocation that is typically 500 KB-2 MB. Keep the testable ByteArray boundary until real benchmark numbers show this is a measurable bottleneck.
- **P1-P4**: `MetadataApplier` temp-file roundtrip remains deferred with P1-P3. Without an Encoder-to-file contract, changing only the metadata side adds complexity without removing the final readback needed by current callers. Revisit both together if benchmark capture proves the file roundtrip dominates.

## Resolved prior to prerelease

- **P3-S3**: `LICENSE` + `NOTICE` + in-app OSS attribution screen. → Addressed by root `LICENSE`, `NOTICE`, `THIRD_PARTY_LICENSES`, and the in-app "Open-source licenses" screen.

## Resolved in v1.0-launch hardening

- **P1-S1**: `MetadataApplier` now strips PNG `tEXt`/`zTXt`/`iTXt`/`eXIf`/`tIME` privacy metadata for `StripAll`, while preserving image-affecting chunks. `PreserveSafe` and `PreserveAll` remain byte-for-byte PNG pass-through by the v1.0 contract. ✅
- **P2-S1**: `MediaStoreSaver` now makes a best-effort deletion of the just-inserted `IS_PENDING` row if copying output fails, preventing orphaned MediaStore rows without masking the original copy failure. ✅
- **P1-S2**: Share-intent URI retrieval now reads legacy extras as raw `Parcelable`s, safely filters to `Uri`, and rejects malformed parcel data instead of crashing the launcher activity. ✅
- **P3-S1**: Backup is now an explicit allowlist containing only the default-preset DataStore across legacy Auto Backup and Android 12+ cloud/device-transfer rules. URI state, batch state, app-usage preferences, and caches are excluded by default. ✅
- **P3-S4**: Batch failures persist only the allowlisted `BatchItemError` code; exception messages, including URI/path strings, are never saved. ✅
- **P3-P5**: Cancellation cleanup and final sampled progress persistence run in `NonCancellable`, so manifest rows converge even after cancellation. ✅
- **P3-S8**: `BatchProcessWorker` uses the purpose-built `mediaProcessing` foreground-service type for WorkManager image batches. `shortService` is not used because arbitrary user images cannot be guaranteed to complete within its approximately three-minute limit. On Android 15+, `mediaProcessing` has a cumulative six-hour-per-24-hour background budget; batches begin from the user-initiated processing flow. ✅

## Carryover from Phase 1+2 (still unaddressed)

### Performance

### Design
- No remaining Phase 1/2 design carryover.

## Phase 3 specific (deferred to later phase)

### Performance
- **P3-P6**: Benchmark medians not yet captured. Hard prerequisite for v1.0 sign-off. → v1.0-launch phase.
- **P3-P7**: Re-baseline profile on a wider device matrix before Play submission. → v1.0-launch phase.

### Design
- No remaining Phase 3 design carryover.

## Launcher icon fallback note

The Phase 3 gate-fix adds adaptive launcher icons under `mipmap-anydpi-v26`. Density-specific PNG launcher fallbacks remain deferred because the app's minimum SDK is 29, so pre-API-26 launcher fallback assets are not used by supported devices. If minSdk is ever lowered below 26, generate `mipmap-mdpi` through `mipmap-xxxhdpi` PNG fallbacks from the vector source during that release.

## Recommended next phases (between p3-gate and p4-decision)

1. **v1.0 release validation**: capture benchmark medians on a real device and re-baseline the profile on a wider device matrix.
2. **v1.0 design-polish phase**: address the carryover D-* design items plus the P3 design medium/low items. Roughly one focused commit.
3. **v1.0 store readiness**: finalize real `STORE_LISTING.md` copy and capture screenshots on a real device.

Only after these three close should `p4-decision` (native acceleration) be presented to the user.
