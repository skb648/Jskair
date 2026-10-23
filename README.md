# AirControl — Air Gesture Control for Android

> Control your device with intuitive air gestures — no touch required. Privacy-first, on-device processing.

![AirControl](app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)

## ✨ Features
- **Simultaneous Dual-Hand Tracking:** MediaPipe Hand Landmarker (`numHands = 2`) with `MultiHandAssociator` temporal association, preserving per-hand `trackId`, handedness (`LEFT`/`RIGHT`), confidence, and 21-landmark skeletons simultaneously across frames with hand-crossing ambiguity protection.
- **Binocular Gaze & Head-Pose Compensation:** MediaPipe Face Landmarker (`outputFacialTransformationMatrixes = true`) with rotation-aligned left/right iris extraction, binocular fusion, disagreement detection, and 9-point ridge-regressed personalized calibration reporting both pixel and visual-angle (degree) error.
- **Hardware Delegate with Safe CPU Fallback:** Supports MediaPipe GPU delegate initialization with automatic `CPU_FALLBACK` on initialization or runtime pipeline error, reporting truthful delegate status (`UNINITIALIZED`, `GPU`, `CPU_FALLBACK`, `CPU`, `ERROR`).
- **Dual-Hand Gesture Engine:** Pure Kotlin module with independent per-hand state machines, active-pinch pointer ownership locking, cross-hand discrete action arbitration (`180 ms`), static poses, dynamic swipes, and pinch click/drag.
- **Low-Latency Cursor & Response Profiles:** Confidence-aware OneEuro filtering (`INSTANT`, `BALANCED`, `PRECISION`) with frame-gap velocity reset (`> 120 ms`), bounded latest-wins VSYNC transport, dwell-to-click, and blink-to-click.
- **Gaze Safety:** Cursor visibility and action eligibility are separate. Monocular or binocular-disagreement frames keep the cursor visible for feedback but immediately cancel pending dwell progress and cannot authorize a click/action.
- **Privacy:** Camera frames and tracking data are processed 100% on-device. AirControl does not request `INTERNET`, and no camera/gesture data is transmitted to a remote server.
- **Accessibility:** `TYPE_ACCESSIBILITY_OVERLAY` is used for the cursor. The accessibility service can retrieve limited UI metadata under the cursor so it can choose native-like pointer feedback; this metadata is processed locally and not stored or transmitted.

## 🚀 Quick Start
1. Install the debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
2. Grant **Camera** + **Accessibility (AirControl)**. Notification permission is optional and only affects status/resume notifications.
3. Show an open palm briefly to arm, then perform gestures with one or both hands.

## 🔧 Build & Verify
```bash
./gradlew :gesture-engine:test :app:testDebugUnitTest assembleDebug
```
`compileSdk 37`, `minSdk 26`, `targetSdk 37`, Kotlin 2.2.10, AGP 9.1.1, Gradle 9.3.1

## 🧠 Behaviour worth knowing

- **Arming** needs a visible, reasonably sized hand for a moment; a **fist held for a second disarms** that hand's state machine.
- **Dual-hand arbitration:** when both hands are visible, the primary/preferred hand drives the pointer, an active pinch locks pointer ownership to the pinching hand until release, and a secondary hand losing tracking never resets the active hand.
- **Swipes require an open palm** by default, so pointer movement does not accidentally scroll pages.
- **Thumb up / thumb down** require a deliberate hold and stable tracking.
- **Palm → Home is opt-in** and off by default.
- **Pinch clicks land on the pointer position** captured by the active modality, not on an older raw landmark position.
- **Eye mode:** gaze moves the cursor; enabled click mechanisms are separately safety-gated. Monocular/invalid gaze cannot authorize an action and cancels pending dwell fixation.
- **Calibration:** gaze uses a 9-point calibration. Re-run it after major changes in seating distance, lighting, glasses, device placement, or orientation.
- **Screen off:** tracking pauses around screen/keyguard state so the camera is not held while the device is locked; resume behavior depends on the active camera/service lifecycle and platform restrictions.
- **Boot resume:** Android may require explicit user confirmation before camera tracking can resume after reboot, so AirControl uses a resume notification rather than claiming unconditional background camera auto-start.

## 📚 Docs
- `docs/audits/2026-10-23-end-to-end-tracking-audit.md` — end-to-end source audit, dual-hand + binocular gaze architecture, and root-cause fixes
- `docs/privacy-policy.md` — privacy and local-storage practices
- `docs/data-safety.md` — Play Data Safety guidance
- `docs/manual-test-checklist.md` — QA matrix
- `docs/archive/` — historical audits

## 📄 License
Private — see `docs/release-signing.md` for signing.
