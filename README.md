# AirControl — Air Gesture Control for Android

> Control your device with intuitive air gestures — no touch required. Privacy-first, on-device only.

![AirControl](app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)

## ✨ Features
- **Hand Tracking:** MediaPipe Hand Landmarker on the **CPU delegate** (deliberate: a GPU
  delegate can die inside OEM graphics drivers before Kotlin can catch anything), 24 FPS
  adaptive, 5 FPS scan when idle, plus a watchdog that rebuilds the pipeline if the
  tracker ever goes missing
- **Gesture Engine:** Pure Kotlin module — static poses (pinch, victory, thumb, etc.) + dynamic swipes + 5-point gaze calibration
- **Cursor:** OneEuro + dead-zone smoothing, dwell-to-click, blink-to-click
- **Privacy:** `INTERNET` permission never requested, all processing on-device, `canRetrieveWindowContent=false`
- **Accessibility:** `TYPE_ACCESSIBILITY_OVERLAY` (no `SYSTEM_ALERT_WINDOW` needed)

## 🚀 Quick Start
1. Install the debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
2. Grant **Camera** + **Accessibility (AirControl)**. Notification permission is optional and only affects boot/resume notifications.
3. Show an open palm briefly to arm, then perform gestures.

## 🔧 Build
```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```
`compileSdk 35`, `minSdk 26`, Kotlin 2.0.21, AGP 8.9.2, Gradle 8.11.1

## 🧠 Behaviour worth knowing

Things the app does on purpose, all of them adjustable in Settings:

- **Arming** needs a visible, reasonably sized hand for a moment; a **fist held for a second
  disarms** again, so you can rest your hand without the app acting on it.
- **Swipes require an open palm** (on by default). Moving the pointer across the screen with a
  pointing hand therefore never scrolls the page. Turn it off in Settings if you want
  swipe-while-pointing.
- **Thumb up / thumb down** only fire while the hand is held still for ~0.6 s, which is what keeps
  "closing my hand" from being read as a thumb gesture; the fist-disarm still wins if you keep
  closing.
- **Palm → Home is opt-in** (off by default): an open palm is the resting pose while armed, so
  firing on it by default threw people out of their apps. When enabled, it needs a still palm.
- **Pinch clicks land on the dot** you see, not on where the raw fingertip was a frame earlier, and
  "ignore pinches while moving" scales with the sensitivity slider instead of being a fixed wall.
- **Calibration and custom-gesture capture suppress actions** while those screens are open, so
  tapping *Next* with a pinch does not also tap the screen behind it.
- **Screen off pauses, it does not stop**: the camera session stays alive and resumes in a few
  hundred ms after unlock (restarting a camera foreground service from the background is illegal
  on Android 14+ and blocked outright by some OEM skins).
- **Notification "Stop" turns the master switch off** on purpose, so the watchdog does not
  immediately restart what you just stopped.

## 📚 Docs
- `docs/privacy-policy.md` — no data collected
- `docs/data-safety.md` — Play Data Safety answers
- `docs/manual-test-checklist.md` — QA
- `docs/archive/` — historical audits & Vision Pro notes

## 📄 License
Private — see `docs/release-signing.md` for signing.
