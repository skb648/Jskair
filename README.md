# AirControl — Air Gesture Control for Android

> Control your device with intuitive air gestures — no touch required. Privacy-first, on-device only.

![AirControl](app/src/main/res/mipmap-xxxhdpi/ic_launcher.png) <!-- If preview broken, see app/src/main/res/mipmap-xxxhdpi/ic_launcher.png locally -->

## ✨ Features
- **Hand Tracking:** MediaPipe Hand Landmarker (GPU → CPU fallback), 24 FPS adaptive, 5 FPS scan when idle
- **Gesture Engine:** Pure Kotlin module — static poses (pinch, victory, thumb, etc.) + dynamic swipes + 5-point gaze calibration
- **Cursor:** Apple Vision Pro-level smoothing (OneEuro + dead-zone), 60fps overlay, dwell-to-click, blink-to-click
- **Privacy:** `INTERNET` permission never requested, all processing on-device, `canRetrieveWindowContent=false`
- **Accessibility:** `TYPE_ACCESSIBILITY_OVERLAY` (no SYSTEM_ALERT_WINDOW needed on Android 6+)

## 🚀 Quick Start
1. Install debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
2. Grant **Camera** + **Accessibility (AirControl)** — overlay auto-granted via accessibility
3. Show open palm 250ms to arm → perform gestures

## 🔧 Build
```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```
`compileSdk 35`, `minSdk 26`, Kotlin 2.0.21, AGP 8.9.2, Gradle 8.11.1

## 📚 Docs
- `docs/privacy-policy.md` — no data collected
- `docs/data-safety.md` — Play Data Safety answers
- `docs/manual-test-checklist.md` — QA
- `docs/archive/` — historical audits & Vision Pro notes

## 📄 License
Private — see `docs/release-signing.md` for signing.

