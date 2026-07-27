# Release Signing Setup — ImageShare

Use this template to create the Android release keystore and wire it into Gradle without committing secrets.

## 1. Generate keystore

Run this from a secure shell. Replace every `TODO` value before use:

```powershell
keytool -genkeypair `
  -v `
  -keystore "$env:USERPROFILE\.keystores\<TODO: keystore-name, e.g. imageshare-release.jks>" `
  -storetype JKS `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000 `
  -alias "<TODO: key alias, e.g. imageshare-release>" `
  -dname "CN=<TODO: your name or company>, OU=<TODO: org unit>, O=<TODO: org>, L=<TODO: city>, S=<TODO: state/region>, C=<TODO: 2-letter country code>"
```

Security warnings:

- **DO NOT commit** the `.jks` file, passwords, or generated credentials.
- Back up the keystore immediately. Losing it can block future updates if Play App Signing is not configured correctly.
- Use a long, unique keystore password and key password stored in a password manager.
- Treat the keystore like production infrastructure, not a disposable build artifact.

## 2. Store keystore safely

Recommended location outside the repository:

```text
~/.keystores/imageshare-release.jks
```

On Windows, that expands to:

```text
C:\Users\<TODO: your Windows username>\.keystores\imageshare-release.jks
```

Recommended secret sources:

1. Environment variables for CI or one-off local builds.
2. `local.properties` for local development only.
3. User-level `gradle.properties` if already protected and excluded from source control.

Use these key names consistently:

```properties
IMAGESHARE_KEYSTORE_PATH=<TODO: absolute path to imageshare-release.jks>
IMAGESHARE_KEYSTORE_PASSWORD=<TODO: keystore password>
IMAGESHARE_KEY_ALIAS=<TODO: key alias>
IMAGESHARE_KEY_PASSWORD=<TODO: key password>
```

Before continuing, confirm `.gitignore` excludes local secret files. Never add these values to tracked files.

## 3. Gradle integration

The release signing configuration is already wired into `app/build.gradle.kts`.
It reads the four values above from environment variables first and then from the ignored root `local.properties` file.

Behavior:

- `assembleRelease` remains available without credentials for CI/R8/lint verification and produces an unsigned APK.
- `bundleRelease` runs `validateReleaseSigning` and fails before packaging if any credential is missing or the keystore path is invalid.
- When all four values are present, the release APK/AAB is signed and R8 plus resource shrinking are enabled.
- Partial credentials never produce a publishable bundle.

For GitHub Actions, add these repository secrets:

```text
IMAGESHARE_KEYSTORE_BASE64
IMAGESHARE_KEYSTORE_PASSWORD
IMAGESHARE_KEY_ALIAS
IMAGESHARE_KEY_PASSWORD
```

Encode the keystore without line wrapping and store the output as `IMAGESHARE_KEYSTORE_BASE64`. The manual **Play release bundle** workflow decodes it only into the runner's temporary directory, verifies the AAB signature, records a SHA-256 checksum, and removes the temporary key.

## 4. Verify

Build the release APK:

```powershell
.\gradlew.bat :app:assembleRelease
```

Expected result when credentials are configured:

- A signed APK is produced under `app\build\outputs\apk\release\`.
- The output filename should not contain `unsigned`.

Verify the signature with Android SDK Build Tools:

```powershell
$apksigner = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\apksigner.bat"
& $apksigner verify --verbose .\app\build\outputs\apk\release\<TODO: signed release apk filename>.apk
```

For Play Store upload, prefer an App Bundle:

```powershell
.\gradlew.bat :app:bundleRelease
```

## 5. Play App Signing (recommended)

Google Play App Signing stores the final app signing key in Google-managed infrastructure. You upload an APK or AAB signed with your upload key, then Play re-signs the distributed app for users.

Recommended flow for ImageShare:

1. Create the local upload/release keystore above.
2. Build a signed `.aab` for Play Console.
3. Enroll in Play App Signing during first upload.
4. Let Google manage the app signing key.
5. Keep the upload key backed up. If it is lost, Play can help reset the upload key, which is safer than losing the final app signing key.

## Security checklist

- [ ] Keystore stored outside the repository.
- [ ] Keystore file never committed.
- [ ] Passwords stored in a password manager.
- [ ] Encrypted backup copy stored in at least two locations.
- [ ] Access limited to maintainers who can publish releases.
- [ ] `local.properties` or user-level Gradle properties used only for local secrets.
- [ ] CI secrets stored in the CI provider secret manager.
- [ ] Play App Signing enabled before public production release.
- [ ] `<TODO: record who owns release signing credentials and recovery access.>`
