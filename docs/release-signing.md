# Release Signing Setup

## Overview

AirControl requires a signed APK/AAB for distribution through Google Play or trusted direct installation. Release signing credentials must stay outside source control.

## Quick Start

### 1. Generate a Release Keystore

```bash
keytool -genkeypair -v \
  -keystore app/release.keystore \
  -alias release \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Store the passwords securely. Do not commit the keystore or password files.

### 2. Set Environment Variables

For a local signed build:

```bash
export KEYSTORE_PASSWORD="..."
export KEY_PASSWORD="..."
export KEY_ALIAS="release"
```

For GitHub Actions, configure:
- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_PASSWORD`
- `KEY_ALIAS`

The workflow materializes the keystore only for the release build and removes the file in an `always()` cleanup step.

### 3. Build Release APK

```bash
./gradlew assembleRelease
```

The signed APK will be at:

```text
app/build/outputs/apk/release/app-release.apk
```

A local `assembleRelease` fails rather than silently producing an unsigned release when signing credentials are missing.

## Versioning

The app's `versionCode` is derived from the larger of the configured baseline, `VERSION_CODE`, and GitHub Actions run number. Release CI additionally compares the built APK's versionCode with the latest published release APK when that asset is available and fails if the candidate is not greater.

Before publishing a release:

1. confirm the release version name has intentionally advanced;
2. confirm CI's versionCode check passed;
3. retain the signed release artifact and checksum according to your release process.

## Google Play Store

For Play distribution, prefer Google Play App Signing with a separate upload key.

Build an AAB when required:

```bash
./gradlew bundleRelease
```

## Keystore Security

Never commit:
- `*.jks`
- `*.keystore`
- `keystore.properties`
- password files
- base64-encoded secret material

If a signing key is compromised, rotate it according to the distribution channel's recovery process. Do not print signing passwords in CI logs.

## Verification

```bash
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
keytool -printcert -jarfile app/build/outputs/apk/release/app-release.apk
```

## Troubleshooting

### "Release build requires release.keystore"
Provide `app/release.keystore` and the required signing environment variables. The repository intentionally fails closed for unsigned local release builds.

### "Keystore was tampered with or password was incorrect"
Check the keystore file, alias, and credentials through your secret manager. Do not echo passwords to the terminal or CI log.
