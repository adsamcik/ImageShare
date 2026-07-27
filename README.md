# ImageShare

> Privacy-first Android image sharing with on-device format conversion, quality optimization, and metadata stripping. Now exposes a cross-app **Transform API** for other apps to leverage the same pipeline without users leaving them.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![CI](https://github.com/adsamcik/ImageShare/actions/workflows/ci.yml/badge.svg)](https://github.com/adsamcik/ImageShare/actions/workflows/ci.yml)
[![min sdk](https://img.shields.io/badge/minSdk-29-brightgreen)](https://developer.android.com/about/versions/oreo)

## Features

- **Privacy-first**: nothing leaves the device. No telemetry, no analytics, no network calls.
- **Smart sharing**: top-3 most-used targets + alphabetical fallback, learned from your behavior.
- **Format conversion**: JPEG, PNG, and WebP in the shipping app.
- **Quality presets**: Email (200 KB target), Web (1 MB target), Original, custom.
- **Metadata control**: strip all, preserve orientation-safe subset, or keep all.
- **Transform API**: other apps can leverage ImageShare's pipeline without users leaving them. See [`docs/transform-api/`](./docs/transform-api/).

## Quick start (users)

Download the signed APK from the latest [GitHub Release](https://github.com/adsamcik/ImageShare/releases), install it on an Android device, then share images into ImageShare to convert, optimize, strip metadata, and forward them to your destination app.

## Integration (developers)

```kotlin
// Gradle (when published)
implementation("com.imageshare:imageshare-api:<TBD>")
```

See [the integration guide](./docs/transform-api/integration-guide.md), [migration guide](./docs/transform-api/migration-guide.md), and [threat model](./docs/transform-api/threat-model.md).

Two sample host apps are available in [`samples/`](./samples/):
- `samples/minimal-host` — barebones URI demo
- `samples/picker-host` — full user flow

## Modules

| Module | Purpose |
|--------|---------|
| `:app` | Main Android app |
| `:core:io` | Storage + URI handling primitives |
| `:core:processing` | Decode / resize / encode pipeline |
| `:feature:preset` | Preset selection UI + smart-sharing chooser |
| `:sdk:imageshare-api` | Public SDK wrapper for third-party hosts |
| `:samples:minimal-host` | Minimal Transform API sample |
| `:samples:picker-host` | Picker-driven Transform API sample |

## Building

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest        # needs emulator
./gradlew :sdk:imageshare-api:assembleDebug :sdk:imageshare-api:testDebugUnitTest
```

GitHub releases build a signed APK from repository secrets. For a separately managed Google Play bundle, configure the upload key as described in [`docs/RELEASE_SIGNING_SETUP.md`](./docs/RELEASE_SIGNING_SETUP.md), then run `./gradlew :app:bundleRelease`.
Google Play publication is always manual: CI can build a signed AAB artifact, but it never uploads or rolls out a Play release.

The native JPEG and AVIF prebuilts are skipped by default; the Kotlin-only fallback handles all current functionality.

## Project status

Version 0.1.0 is a public prerelease for GitHub distribution. Google Play publication is a separate, manually managed process.

## Android baseline

- App minSdk: 29
- SDK minSdk: 24
- Sample-host minSdk: 26
- targetSdk: 36
- compileSdk: 36
- Java toolchain: JDK 17

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md). All contributors must abide by our [Code of Conduct](./CODE_OF_CONDUCT.md).

## Security

For vulnerability reports see [SECURITY.md](./SECURITY.md). Do **not** open public issues for security issues.

## License

ImageShare is licensed under the [GNU General Public License v3.0](./LICENSE).

Note: The SDK module (`:sdk:imageshare-api`) is also GPL v3; apps that embed it via Gradle become subject to GPL v3. Apps that interact with the Transform API directly via ContentProvider/Intent (no SDK linkage) are not affected by the SDK's license, since IPC is not "linking" under GPL.

Copyright © 2024–2026 adsamcik.
