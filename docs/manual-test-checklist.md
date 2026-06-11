# AirControl Manual Test Checklist

## Pre-Release Verification

Complete ALL items before tagging a release. Mark each with ✅ or ❌ and add notes for failures.

---

## 1. Device Matrix (10 devices / API range)

| # | Device | API Level | Android Version | Result | Notes |
|---|--------|-----------|-----------------|--------|-------|
| 1 | Pixel 8 Pro | 34 | 14 | | |
| 2 | Samsung Galaxy S24 | 34 | 14 | | |
| 3 | Samsung Galaxy A54 | 33 | 13 | | |
| 4 | Pixel 6a | 33 | 13 | | |
| 5 | OnePlus 12 | 34 | 14 | | |
| 6 | Xiaomi 14 | 34 | 14 | | |
| 7 | Pixel 4a | 30 | 11 | | |
| 8 | Samsung Galaxy S21 FE | 31 | 12 | | |
| 9 | Motorola Moto G Power | 29 | 10 | | |
| 10 | Emulator (API 26) | 26 | 8.0 | | |

### Per-Device Tests
- [ ] App installs and launches without crash
- [ ] Onboarding flow completes successfully
- [ ] Camera preview shows in debug screen
- [ ] Hand detection works (open palm → arming → armed)
- [ ] At least one gesture action dispatches correctly (e.g., swipe → scroll)
- [ ] Settings persist across app restart
- [ ] Service survives app swipe-away from recents

---

## 2. Low Light Conditions

- [ ] Hand detection works in dim indoor lighting (single desk lamp)
- [ ] Hand detection degrades gracefully in very low light (no crash, no ANR)
- [ ] Confidence indicator reflects reduced accuracy in low light
- [ ] Camera auto-exposure adjusts within 3 seconds of lighting change

---

## 3. Left Hand

- [ ] Hand preference set to "Left" — detection works correctly
- [ ] Hand preference set to "Any" — left hand detected and tracked
- [ ] Swipe directions are correct (not mirrored) with left hand
- [ ] Cursor moves in expected direction with left hand
- [ ] Handedness label shows "LEFT" in debug screen

---

## 4. Gloves (Expected Failure)

- [ ] With thin gloves: hand detection may partially work, gestures unreliable
- [ ] With thick gloves: hand detection fails gracefully (no crash)
- [ ] No ANR or infinite processing loop when hand is partially occluded
- [ ] Service recovers when gloves removed and bare hand shown

---

## 5. Rotation Mid-Gesture

- [ ] Rotate device portrait → landscape while tracking: no crash
- [ ] Cursor overlay repositions correctly after rotation
- [ ] Gesture pipeline continues (no frames dropped) during rotation
- [ ] Status pill remains visible after rotation
- [ ] Screen metrics update correctly in accessibility service

---

## 6. Incoming Call Interruption

- [ ] Incoming call while tracking: service handles pause correctly
- [ ] After call ends: tracking resumes automatically
- [ ] Camera is not held during phone call (other apps can access it)
- [ ] No duplicate camera binding after call ends
- [ ] Notification remains active through call

---

## 7. Split-Screen

- [ ] Split-screen mode: cursor moves to correct position on active half
- [ ] Gestures dispatch to the correct (focused) app
- [ ] Overlay appears in correct position (not stretched)
- [ ] Exit split-screen: cursor and overlays return to normal
- [ ] No crash when entering/exiting split-screen

---

## 8. Extended Use (30-Minute Continuous Session)

- [ ] No ANR during 30-minute session
- [ ] No crash during 30-minute session
- [ ] Memory usage stable (no monotonic increase): check via `adb shell dumpsys meminfo com.aircontrol`
- [ ] Battery drain < 8% per hour on mid-range device
- [ ] FPS remains stable (no degradation over time): check debug screen
- [ ] Thermal status does not reach SEVERE during normal indoor use
- [ ] No leaked activities/fragments (LeakCanary shows no notifications in debug)

---

## 9. Service Lifecycle

- [ ] Force-stop app from Settings: all overlays removed
- [ ] Re-open app after force-stop: service restarts cleanly
- [ ] Accessibility service disabled from Settings: app shows warning
- [ ] Accessibility service re-enabled: app recovers without restart
- [ ] Camera permission revoked while tracking: graceful stop
- [ ] Camera permission re-granted: tracking resumes
- [ ] Overlay permission revoked: app shows warning
- [ ] Boot receiver: device reboot with "Start on Boot" enabled → service auto-starts
- [ ] Boot receiver: device reboot with "Start on Boot" disabled → no auto-start

---

## 10. Accessibility Service Edge Cases

- [ ] Keyguard locked: no gesture injection (except Home/Notifications)
- [ ] Keyguard unlocked: gesture injection resumes
- [ ] Screen off → screen on: tracking pauses and resumes correctly
- [ ] dispatchGesture cancellation: retried once automatically
- [ ] Volume adjustment works via gesture (thumb up/down)
- [ ] Media play/pause works via gesture (victory sign)
- [ ] Screenshot action works (API 28+)
- [ ] Lock screen action works (API 28+)
- [ ] Back/Home/Recents global actions work

---

## 11. Gesture Map Configuration

- [ ] All 9 gesture triggers display correct hand icons
- [ ] Tap gesture → action picker bottom sheet opens
- [ ] Changing action to an already-mapped action shows conflict dialog
- [ ] Swap option in conflict dialog works correctly
- [ ] Reset to defaults: shows undo snackbar
- [ ] Undo: restores previous gesture map
- [ ] Gesture map persists after app restart
- [ ] Migration: install older version, upgrade, gesture map migrates correctly

---

## 12. Onboarding Flow

- [ ] First launch: onboarding appears
- [ ] Skip onboarding: goes to home screen with warning cards
- [ ] Complete onboarding: no warning cards on home screen
- [ ] Camera permission denied: shows "Fix Now" card
- [ ] Accessibility not enabled: shows "Fix Now" card
- [ ] Overlay not granted: shows "Fix Now" card
- [ ] Re-run setup from home screen: onboarding flow restarts

---

## 13. Calibration Flow

- [ ] Start calibration: camera preview shows
- [ ] Palm detection: hand skeleton overlay visible
- [ ] Measurement step: progress bar advances
- [ ] Test gestures: 3 gestures recognized
- [ ] Skip calibration: skips to home screen
- [ ] Re-run from Settings: calibration starts again
- [ ] Calibration data persists after app restart

---

## 14. Settings Persistence

- [ ] Sensitivity slider: value persists after restart
- [ ] Cursor speed slider: value persists after restart
- [ ] Hold duration slider: value persists after restart
- [ ] Hand preference: selection persists after restart
- [ ] FPS selection: persists after restart
- [ ] All toggle switches: persist after restart
- [ ] Font scaling 200%: all UI remains readable
- [ ] RTL layout: all screens display correctly

---

## 15. TalkBack Accessibility

- [ ] Home screen: all elements have content descriptions
- [ ] Settings: all sliders have value announcements
- [ ] Gesture map: hand icons have descriptions
- [ ] Onboarding: step descriptions announced
- [ ] Navigation: all routes reachable via swipe
- [ ] Power button: state change announced

---

## Sign-Off

| Role | Name | Date | Result |
|------|------|------|--------|
| QA Lead | | | |
| Dev Lead | | | |
| Product | | | |

**Release blocked until ALL critical tests pass on ≥3 devices from the matrix.**
