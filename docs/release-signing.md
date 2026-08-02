# Release Signing Setup

## Overview

AirControl requires a signed APK/AAB for distribution via Google Play Store or direct APK installation. This guide walks through the signing setup.

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

You'll be prompted to create a password. Remember this — it's your `KEYSTORE_PASSWORD` and `KEY_PASSWORD`.

### 2. Set Environment Variables

For **local builds**:
```bash
export KEYSTORE_PASSWORD="your_keystore_password"
export KEY_PASSWORD="your_key_password"
export KEY_ALIAS="release"
```

For **CI/CD** (GitHub Actions):
Add these as repository secrets:
- `KEYSTORE_PASSWORD`
- `KEY_PASSWORD`
- `KEY_ALIAS`
- Upload `release.keystore` as a CI secret or base64-encode it:
  ```bash
  base64 app/release.keystore > keystore-base64.txt
  ```

For **GitLab CI**:
Add the same variables in Settings > CI/CD > Variables (mark as "masked" and "protected").

### 3. Build Release APK

```bash
./gradlew assembleRelease
```

The signed APK will be at:
```
app/build/outputs/apk/release/app-release.apk
```

## Google Play Store

For Play Store distribution:

1. **Use App Signing by Google Play** (recommended):
   - Google manages the distribution key
   - You sign with an upload key
   - Generate upload key separately from the release keystore

2. **Build AAB** (Android App Bundle):
   ```bash
   ./gradlew bundleRelease
   ```

3. **Upload** to Google Play Console

## Keystore Security

⚠️ **CRITICAL**: Never commit your keystore file to version control.

The `.gitignore` already excludes:
- `*.jks`
- `*.keystore`
- `keystore.properties`

If your keystore is compromised, you must:
1. Generate a new keystore
2. Users who installed the old signed APK **cannot upgrade** — they must uninstall and reinstall
3. Update all CI/CD secrets

## Verification

Verify your APK is signed correctly:

```bash
# Check APK signature
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk

# Or use keytool
keytool -printcert -jarfile app/build/outputs/apk/release/app-release.apk
```

## Troubleshooting

### "No signing config could be found"
- Ensure `release.keystore` exists in `app/` directory
- Ensure `KEYSTORE_PASSWORD` environment variable is set

### "Keystore was tampered with or password was incorrect"
- Double-check your `KEYSTORE_PASSWORD` and `KEY_PASSWORD`
- Ensure `KEY_ALIAS` matches the alias used when generating the keystore

### Build succeeds but APK is unsigned
- The build falls back to unsigned if env vars are missing (for local dev)
- Check that environment variables are actually set: `echo $KEYSTORE_PASSWORD`
