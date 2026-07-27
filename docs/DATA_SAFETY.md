# Data Safety — ImageShare v1.0

This document describes ImageShare's data practices in detail. It is the source of truth
for the Play Console "Data safety" form and is provided in plain language for the user.

## Summary
- ImageShare processes images **locally on your device**.
- ImageShare does NOT collect, transmit, sell, or share any user data.
- ImageShare does NOT use any analytics, crash reporting, or advertising SDKs.
- ImageShare does NOT have an account system, login, or cloud component.

## What ImageShare DOES NOT do
- Does not access your photo library directly. Android's photo picker shows you
  which images to share with us; we never see anything else.
- Does not request the `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, or `READ_EXTERNAL_STORAGE` permissions.
- Does not contact the network. The app has no `INTERNET` permission.
- Does not use Firebase, Crashlytics, Sentry, Mixpanel, Amplitude, AppsFlyer, or any other tracking SDK.
- Does not include advertising.
- Does not have any in-app purchases or subscriptions in v1.0.

## What ImageShare DOES store, locally on your device
| What                                  | Where                                              | Lifetime                | Why                                                                 |
|---------------------------------------|----------------------------------------------------|-------------------------|---------------------------------------------------------------------|
| Default preset selection (id only)    | DataStore `imageshare_presets`                     | Until app uninstalled   | Remembers your default preset across launches                       |
| Recently-opened SAF URIs (≤12 entries)| DataStore `imageshare_uri_registry`                | Until app uninstalled OR user removes them | Lets you re-open recent files via the Files app entry              |
| Batch processing manifest             | Room database `imageshare.db`                      | ≤7 days (auto-purged)   | Lets background batches resume across app restarts                  |
| Staged share-intent images            | App-private cache `cacheDir/shared-intake/`        | ≤24 hours (auto-swept)  | Buffers images shared from other apps so processing isn't interrupted |
| Processed output images               | App-private cache `cacheDir/shared-output/`        | ≤24 hours (auto-swept)  | Holds processed images until you save or share them                 |
| Coil image cache                      | App-private cache `cacheDir/image_cache/`          | Coil's default policy    | Speeds up thumbnail rendering                                       |

## What ImageShare DOES NOT store
- No camera, location, microphone, or contacts data.
- No personal information (name, email, phone, address).
- No device identifiers (advertising ID, device ID, IMEI).
- No permanent app-managed image library — originals stay where they were (gallery, other apps); staged and processed copies live in app-private cache for at most 24 hours unless you explicitly save them.

## Backup posture
- Android Auto Backup remains enabled (`android:allowBackup="true"`) only for the default-preset
  selection, so your preferred preset can follow you to a new device.
- Both the Android 11-and-lower rules and Android 12+ cloud/device-transfer rules are explicit
  include-only allowlists. Any new app-private state is excluded until it is deliberately reviewed
  and added to the allowlist.
- The allowlist excludes the Recently-opened SAF URI list, Room batch manifest, sharing-target
  history, auto-process-on-share setting, and all cache/output data. This avoids restoring
  device-bound URIs, app-usage history, ephemeral job state, or image copies.

## EXIF privacy
ImageShare's default behavior is to **strip ALL EXIF metadata** from processed images:
- GPS coordinates (LAT, LON, ALTITUDE, TIMESTAMP, DATESTAMP, PROCESSING_METHOD)
- Camera identifiers (MAKE, MODEL, BODY_SERIAL_NUMBER, MAKER_NOTE, USER_COMMENT)
- Lens identifiers (LENS_MAKE, LENS_MODEL, LENS_SERIAL_NUMBER, LENS_SPECIFICATION)
- Software identifiers (SOFTWARE, ARTIST, COPYRIGHT, IMAGE_DESCRIPTION)
- All "OWNER" or "SERIAL" tags

The user can opt into a "Preserve safe metadata" mode that keeps only:
- DateTime, DateTimeOriginal, DateTimeDigitized
- ColorSpace, ImageWidth, ImageLength, BitsPerSample

(Camera identifiers and GPS are not included in the PreserveSafe subset.)

A "Preserve all" mode also exists for users who want to keep camera info; even then,
orientation is normalized to 1 (NORMAL) because the engine bakes pixel orientation
during processing.

## Permissions ImageShare requests
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROCESSING` — required by Android 14+ to run unbounded image batches with a progress notification when you background the app mid-job. The service is bound to the batch and stops as soon as it finishes. It uses the purpose-built `mediaProcessing` type rather than the three-minute `shortService` type; this path is used only for WorkManager batches, not in-foreground single-share workflows.
- `POST_NOTIFICATIONS` — required by Android 13+ to show the batch progress notification.
  Notifications are silent (`IMPORTANCE_LOW`) and limited to batch progress + cancel.

## Play Console data-safety form mapping
| Play Console field                          | ImageShare answer                                    |
|---------------------------------------------|------------------------------------------------------|
| Does the app collect/share user data?       | **No**                                               |
| Are all collected data optional?            | N/A                                                  |
| Does the app encrypt data in transit?       | N/A — no data in transit                             |
| Can users request deletion?                 | N/A — no server-side data                            |
| Does the app target children?               | No                                                   |
| Other app activity (in-app actions)         | App stores batch progress LOCALLY for resume; not collected/shared. Document under "Files and docs > Other files and docs > Optional" if Play Console requires; this is local-only ephemeral state. |

## Third-party SDKs
The following third-party libraries are used:
- **AndroidX** (Apache 2.0): UI, lifecycle, persistence, work, photo picker contracts.
- **Coil** (Apache 2.0, by Coil Contributors): in-app image loading for thumbnails. Local-only; no network usage in our integration.
- **Material Components for Android** (Apache 2.0): theme parent + adaptive icon support.
- **Kotlin / kotlinx.coroutines** (Apache 2.0): language runtime.

None of these libraries collect or transmit user data in our integration.

See `NOTICE` for full attribution. The in-app "Open-source licenses" screen
provides the same information at runtime.
