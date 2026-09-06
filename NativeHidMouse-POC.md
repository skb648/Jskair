# Native HID Mouse — Phase 1 Proof of Concept

**Status: CODE-COMPLETE, CI-GREEN, HARDWARE-UNVERIFIED.**
**Native cursor behavior remains hardware-unverified.**

Jskair (transmitter) acts as a real Bluetooth HID mouse. The RECEIVER Android
device's own input system renders its native cursor — Jskair renders nothing.
Phase 1 is **movement only** (no click, no drag, no scroll; those are Phases 2–4).

---

## API requirement (corrected)

`android.bluetooth.BluetoothHidDevice` is a **public API since API level 28
(Android 9, Pie)** — verified against
[developer.android.com/reference/android/bluetooth/BluetoothHidDevice](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice)
("Added in API level 28"). An earlier revision of this doc said API 29; that was wrong.

- **API < 28:** Native HID = `UNSUPPORTED` (shown in Settings; feature stays inert).
- **API >= 28:** HID API available in principle; registration still depends on
  runtime Bluetooth state, permissions, and OEM support.

`minSdk` is unchanged (26). The guard is runtime-only
(`Build.VERSION.SDK_INT >= Build.VERSION_CODES.P`).

## Architecture (unchanged from Phase 1 build)

```
Device A (Jskair, transmitter)                 Device B (receiver)
──────────────────────────────                 ────────────────────
existing HandTracker (untouched)
   ↓ read-only HandFrames
NativeHidInputAdapter
  palm anchor = mean(lm 0,5,9,13,17)
  → delta → dead zone 0.0015 → GAIN 1600      Bluetooth HID profile
  → fractional carry → ±127 clamp                        ↑
NativeHidMouseController                                        │
  BluetoothHidDevice.registerApp → sendReport(relative X/Y) ─┘
                                                        Android input system
                                                        → NATIVE SYSTEM CURSOR
```

Guarantees kept: no `AccessibilityService.dispatchGesture()` on this path, no
cursor/overlay rendering, no absolute coordinates, no second hand detector,
feature OFF by default and byte-identical app behavior when OFF.

## A. What has been proven by code/CI (no hardware claims)

1. The app compiles, all unit tests pass, and GitHub Actions produces a signed
   debug APK artifact (run IDs recorded in the repo Actions history).
2. The HID report descriptor is a standard relative 3-button + wheel mouse
   (Report ID 1, 4 bytes), written from the public USB HID usage tables.
3. Adapter math is unit-proven on the JVM: scaling (1/16 camera step → exactly
   100 counts), dead-zone silence, fractional carry (6.25 → 6 + remainder that
   tips a later report to 7), re-prime after hand loss (no fling), ±127 clamping
   with the excess preserved as carry, NaN/∞ landmarks re-prime instead of
   poisoning the carry.
4. State machine covers UNSUPPORTED / OFF / AVAILABLE / REGISTERING / REGISTERED
   / CONNECTING / CONNECTED / ERROR with reasons; every Bluetooth call is
   SecurityException-safe; Bluetooth-off/ service-death paths recover.
5. OFF (default) ⇒ zero HID initialization: the GCAService collector no-ops and
   the controller stays down.

## B. What still requires hardware validation

- `registerApp()` actually being accepted by a real OEM Bluetooth stack.
- A real host accepting the HID connection (`connect()` → `CONNECTED`).
- **Relative reports moving the receiver's native cursor.**
- Range/latency/pointer-speed feel; GAIN 1600 is an untested first guess.
- Galaxy Tab A9+ 5G (SM-X216B) as receiver — see J below.

## C. Device A / Device B test architecture

- **Device A (transmitter):** the Android phone running Jskair. Needs camera +
  Bluetooth; plays the HID **Device** (peripheral) role.
- **Device B (receiver/host):** any Android 8+ phone/tablet (target: Galaxy Tab
  A9+). It pairs with Device A and, if the POC works, shows its own pointer
  driven by Device A's hand tracking.
- Only Device A installs Jskair. Device B needs no app — its OS input pipeline
  is the thing under test.

## D. Pairing steps

1. Device A: install the CI APK, open Jskair → Settings → **Experimental** →
   *Native HID Mouse (Bluetooth)* → toggle ON; grant **BLUETOOTH_CONNECT** if asked.
2. Device A: enable Bluetooth. Pair Device A ↔ Device B in system Bluetooth
   settings (either direction; accept the pairing dialog on BOTH devices).
3. Back in Jskair: **Refresh paired devices** → tap **Connect** next to Device B.

## E. Expected REGISTERED state

Within a few seconds of toggling ON (Bluetooth on, API 28+), the card should
show `HID status: REGISTERED`. This means the OEM stack accepted our HID Device
app registration. If it shows `ERROR — registerApp returned false`, the OEM
refuses the HID Device role on this build — record the exact message.

## F. Expected CONNECTED state

After tapping Connect on the paired host: `CONNECTING`, then `CONNECTED`
(when Device B accepts the HID channel). The card then explicitly says:
**“Connected — hardware cursor test required”** — CONNECTED is a Bluetooth
fact, NOT cursor proof.

## G. How to verify the native cursor (the ONLY success criterion)

With status CONNECTED on Device A, move your open hand and **watch Device B's
screen**:

- **PASS:** Device B shows its normal Android pointer (arrow/dot) moving with
  your hand — Jskair is NOT drawing that pointer (Device B runs no Jskair code).
- **FAIL:** nothing on Device B moves, or only a Jskair-side overlay moves.
  A Jskair overlay moving is NOT native HID — it means the POC failed.

## H. Failure troubleshooting

| Symptom | Likely cause / next step |
|---|---|
| `UNSUPPORTED … API 27` | Device below API 28 — expected, not fixable. |
| `AVAILABLE — Bluetooth is off` | Turn Bluetooth on; registration auto-retries. |
| `ERROR — Missing BLUETOOTH_CONNECT` | Deny→re-enable the toggle and accept the permission. |
| `ERROR — registerApp returned false` | OEM blocks HID Device role; capture `adb logcat -s NativeHidController` and the device model. |
| Stuck `REGISTERING` | Bluetooth stack slow/off; toggle feature off/on once. |
| `CONNECTED` but receiver cursor still | See G — may need to enable “Bluetooth input device” in Device B's settings, or unpair/re-pair. |
| Cursor drifts at rest | Raise DEAD_ZONE (0.0015) — do not change other tuning blindly. |

## I. Current limitations

- Movement only — no click/drag/wheel (Phases 2–4), no gesture logic added.
- Single hard-coded GAIN/DEAD_ZONE; no speed curve; ±127 per report bounds
  fast motion (excess is carried into later reports).
- Same-device control (VirtualDeviceManager/Shizuku/root) is out of scope and
  intentionally not implemented (documented research only).
- No third-party code used; PhonePad (GPL) was never copied.
- `callbackExecutor` runs callbacks inline on binder threads (state posts to
  main via handler); adequate for a movement POC.

## J. Galaxy Tab A9+ 5G (SM-X216B) hardware test status

**NOT TESTED** — no physical SM-X216B is available in the development
environment. It is the intended primary receiver. Until the D→G procedure runs
on it, no statement about its HID host behavior is made. During the test,
record: Android/API version shown in the Experimental card, whether Device A
reached REGISTERED, CONNECTED, and whether the native cursor moved.

---

## Field-test reporting checklist

When testing, please report: Device A model + Android version, Device B model +
Android version, card status at each step (E/F), G result (PASS/FAIL), and
`adb logcat -s NativeHidController NativeHidAdapter` output if anything fails.
