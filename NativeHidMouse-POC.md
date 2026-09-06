# Native HID Mouse — Phase 1 Proof of Concept

**Status: UNVERIFIED on hardware (needs a real receiver test).** Code-complete, unit-tested, CI-green.

## A. Files added

| File | Purpose |
|---|---|
| `app/src/main/java/com/aircontrol/nativeinput/NativeHidMouseStatus.kt` | State model: OFF / UNSUPPORTED / AVAILABLE / REGISTERING / REGISTERED / CONNECTING / CONNECTED / ERROR (+reason) |
| `app/src/main/java/com/aircontrol/nativeinput/HidMouseDescriptor.kt` | Standard relative 3-button+wheel mouse HID descriptor (Report ID 1) |
| `app/src/main/java/com/aircontrol/nativeinput/HidMouseReport.kt` | Report builder (movement-only wired; buttons/wheel reserved for Phase 2+) |
| `app/src/main/java/com/aircontrol/nativeinput/NativeMouseInput.kt` | Transport abstraction — `move(dx, dy)` only |
| `app/src/main/java/com/aircontrol/nativeinput/NativeHidMouseController.kt` | BluetoothHidDevice lifecycle: proxy, registerApp, host connect, sendReport, BT state recovery, cleanup |
| `app/src/main/java/com/aircontrol/nativeinput/NativeHidInputAdapter.kt` | HandFrame → palm anchor → relative deltas → HID counts (gain/dead-zone/remainder carry) |
| `app/src/main/java/com/aircontrol/ui/settings/NativeHidMouseCard.kt` | Experimental Settings card (opt-in switch, status, host picker) |
| `app/src/test/java/com/aircontrol/nativeinput/NativeHidInputAdapterTest.kt` | Pure-JVM adapter tests (5) |

## B. Files modified (minimal, additive)

- `UserPreferences.kt` (+`nativeHidMouseEnabled=false`), `SettingsRepository(.kt)` + `SettingsRepositoryImpl.kt` (+update fn + DataStore key) — opt-in persistence.
- `di/AccessibilityServiceEntryPoint.kt` (+1 provision method).
- `GestureControlAccessibilityService.kt` — ONE isolated collector block (`"native hid mouse"`): prefs→controller.start/stop + handFrames→adapter. No-ops entirely when the pref is off.
- `ui/settings/SettingsScreen.kt` (+section header + card call), `SettingsViewModel.kt` (+5 pass-through fns).
- `strings.xml`, `AndroidManifest.xml` (permissions below).

## C. Architecture

```
HandTracker.handFrames (existing, untouched)
   ↓ read-only
NativeHidInputAdapter  (palm anchor → dx/dy → gain/dead-zone → HID counts w/ remainder carry)
   ↓ NativeMouseInput.move(dx,dy)
NativeHidMouseController (BluetoothHidDevice.registerApp → sendReport(RELATIVE X/Y))
   ↓ Bluetooth HID
Receiver Android device → ANDROID INPUT SYSTEM → NATIVE SYSTEM CURSOR
```

No overlay, no drawn cursor, no `dispatchGesture()`, no absolute coordinates. Movement only (Phase 1).

## D. API requirements
`BluetoothHidDevice` public API = **Android 10 (API 29)+**. minSdk 26 → runtime check `isHidApiAvailable`; older devices get `UNSUPPORTED`, app unaffected. Detected at runtime: adapter presence, BT on/off, registerApp success/failure, host connection — all surfaced in the state model, never a crash.

## E. Permissions added
- `BLUETOOTH_CONNECT` (runtime-requested on Android 12+ from the Settings card)
- `BLUETOOTH` / `BLUETOOTH_ADMIN` with `maxSdkVersion="30"` (legacy)
- `uses-feature android.hardware.bluetooth required="false"`

## F/G. Third-party code reused
**None.** PhonePad was inspected as a concept only; nothing copied (license risk). The descriptor is the standard USB HID usage-table mouse layout; everything else is written against public AOSP documentation.

## H. Enabling
Settings → **Experimental** → *Native HID Mouse (Bluetooth)* → toggle (grant BLUETOOTH_CONNECT when asked).

## I. Pairing with a receiver
1. Pair both devices in Android Bluetooth settings (either direction).
2. Jskair Settings card → **Refresh paired devices** → **Connect** next to the receiver.
3. Receiver shows a connected Bluetooth mouse; move your open hand.

## J. Test procedure (TEST A–J from the plan)
Build/install → HID OFF: existing behavior unchanged → enable → status REGISTERED → pair+Connect → status CONNECTED → hand movement → receiver's native cursor moves → hand still → cursor stops → disconnect → app stable → BT off → state AVAILABLE("Bluetooth is off"), no crash → BT on → auto re-register attempt.

## K. Galaxy Tab A9+ 5G (SM-X216B)
**Not tested — no physical device in this environment.** Runtime reporting is built in: Android version, API level, HID API availability, registration success/failure and OEM limitations all appear in the Settings card status line and `NativeHidController` logs.

## L/M. Limitations & native cursor
The Jskair side draws NO cursor in HID mode. The native-cursor claim can only be confirmed on hardware — per the plan, this POC is **UNVERIFIED** until TEST F succeeds on a receiver.

## N. Verdict
Code-complete Phase 1; **unverified until the two-device test passes.** Phases 2–4 (click/drag/scroll), same-device investigation (VirtualDeviceManager/Shizuku — documented only), and OEM behavior (Samsung/Xiaomi/…) are future work.
