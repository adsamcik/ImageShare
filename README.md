# ImageShare

ImageShare is a fast Android image preparation and sharing utility; see `plan.md` in the session workspace for the broader delivery plan.

This repository is currently Phase 0 scaffolding: a Kotlin-only Android project with Jetpack Compose UI, placeholder feature/core modules, lint, unit tests, and detekt wired into Gradle.

## Android baseline

- minSdk: 29
- targetSdk: 36
- compileSdk: 36
- Java toolchain: JDK 17
- Permissions: no broad media permissions and no explicit `<uses-permission>` entries in the app manifest.

Installed SDK note: this scaffold uses the highest stable installed platform found locally (`android-36`) and installed build tools are available through `37.0.0-rc1`; no SDK packages were installed during scaffolding.

## Build and test

```powershell
.\gradlew.bat --version
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lint
.\gradlew.bat detekt
```

The initial unit test setup uses JUnit 4 to keep the Android Gradle test pipeline simple for Phase 0.

## Launcher icon assets

ImageShare ships a Material-style adaptive launcher icon (`mipmap-anydpi-v26`) with a geometric photo-card/share-arrow foreground on a solid blue background. The app supports Android 10+ (minSdk 29), so density-specific pre-API-26 PNG launcher fallbacks are intentionally not generated; generate `mipmap-mdpi` through `mipmap-xxxhdpi` PNGs from the vector source only if minSdk is lowered below 26.
