# AirControl — Air Gesture Control for Android

> Control your device with intuitive air gestures — no touch required. Privacy-first, on-device processing.

![AirControl](app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)

## ✨ Features
- **Hand Tracking:** MediaPipe Hand Landmarker on the **CPU delegate**, adaptive analysis with idle scanning and a watchdog that can recover a missing camera pipeline.
- **Gesture Engine:** Pure Kotlin module — static poses, dynamic swipes, pinch click/drag, and 9-point personalized gaze calibration with head-pose-aware regression and a safe fallback.
- **Cursor:** OneEuro-based smoothing, bounded latest-wins transport, dwell-to-click, and blink-to-click.
- **Gaze Safety:** Cursor visibility and action eligibility are separate. Monocular or invalid gaze evidence may keep the cursor visible but cannot authorize a click/action.
- **Privacy:** Camera frames and tracking data are processed on-device. AirControl does not request `INTERNET`, and no camera/gesture data is transmitted to a remote server.
- **Accessibility:** `TYPE_ACCESSIBILITY_OVERLAY` is used for the cursor. The accessibility service can retrieve limited UI metadata under the cursor so it can choose native-like pointer feedback; this metadata is processed locally and not stored or transmitted.

## 🚀 Quick Start
1. Install the debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
2. Grant **Camera** + **Accessibility (AirControl)**. Notification permission is optional and only affects status/resume notifications.
3. Show an open palm briefly to arm, then perform gestures.

## 🔧 Build
```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```
`compileSdk 37`, `minSdk 26`, targetSdk 37, Kotlin 2.2.10, AGP 9.1.1, Gradle 9.3.1

## 🧠 Behaviour worth knowing

- **Arming** needs a visible, reasonably sized hand for a moment; a **fist held for a second disarms** again.
- **Swipes require an open palm** by default, so pointer movement does not accidentally scroll pages.
- **Thumb up / thumb down** require a deliberate hold and stable tracking.
- **Palm → Home is opt-in** and off by default.
- **Pinch clicks land on the pointer position** captured by the active modality, not on an older raw landmark position.
- **Eye mode:** gaze moves the cursor; enabled click mechanisms are separately safety-gated. Monocular/invalid gaze cannot authorize an action.
- **Calibration:** gaze uses a 9-point calibration. Re-run it after major changes in seating distance, lighting, glasses, device placement, or orientation.
- **Screen off:** tracking pauses around screen/keyguard state so the camera is not held while the device is locked; resume behavior depends on the active camera/service lifecycle and platform restrictions.
- **Boot resume:** Android may require explicit user confirmation before camera tracking can resume after reboot, so AirControl uses a resume notification rather than claiming unconditional background camera auto-start.

## 📚 Docs
- `docs/privacy-policy.md` — privacy and local-storage practices
- `docs/data-safety.md` — Play Data Safety guidance
- `docs/manual-test-checklist.md` — QA matrix
- `docs/archive/` — historical audits

## 📄 License
Private — see `docs/release-signing.md` for signing.
