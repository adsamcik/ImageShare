# Release signing setup

The GitHub release workflow builds, verifies, and publishes a signed Android APK. It never exposes the release key in the repository or release assets.

## Required GitHub Actions secrets

```text
IMAGESHARE_KEYSTORE_BASE64
IMAGESHARE_KEYSTORE_PASSWORD
IMAGESHARE_KEY_ALIAS
IMAGESHARE_KEY_PASSWORD
```

The workflow decodes the keystore only into the runner's temporary directory, verifies the APK with `apksigner`, publishes a SHA-256 checksum alongside the APK, and removes the temporary file when it finishes.

## Creating and storing the release key

- Generate one long-lived Android keystore with a strong, unique password and key password.
- Store the keystore and credentials in a private, backed-up credential store.
- Add only the four values above to the repository's GitHub Actions secrets.
- Never commit the keystore, passwords, or encoded keystore data to this repository.
