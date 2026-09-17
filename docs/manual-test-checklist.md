# AirControl Manual Test Checklist

## Pre-Release Verification

Complete ALL items before tagging a release. Mark each with ✅ or ❌ and add notes for failures. Automated CI results must be attached to the release record; device-only items must be executed on physical hardware.

---

## 1. Device Matrix

At minimum validate one current Samsung/One UI device plus representative Android 15+ hardware. Expand this matrix as devices are available.

| # | Device | API | Android | Result | Notes |
|---|---|---:|---|---|---|
| 1 | Samsung current-generation device | 35+ | 15+ | | |
| 2 | Samsung mid-range device | 34+ | 14+ | | |
| 3 | Pixel current-generation device | 35+ | 15+ | | |
| 4 | Pixel mid-range device | 33+ | 13+ | | |
| 5 | OnePlus current-generation device | 34+ | 14+ | | |
| 6 | Xiaomi current-generation device | 34+ | 14+ | | |
| 7 | Motorola mid-range device | 33+ | 13+ | | |
| 8 | Physical tablet / large screen | 35+ | 15+ | | |
| 9 | Android Emulator | 35 | 15 | | |
| 10 | Lowest supported API device | 26 | 8.0 | | |

### Per-Device Tests
- [ ] App installs and launches without crash
- [ ] Onboarding flow completes successfully
- [ ] Camera preview shows in debug screen
- [ ] Hand detection works (open palm → arming → armed)
- [ ] At least one gesture action dispatches correctly (e.g. swipe → scroll)
- [ ] Eye tracking center/left/right/up/down directions are correct
- [ ] Settings persist across app restart
- [ ] Accessibility service state is reflected accurately in app UI
- [ ] Service survives app swipe-away where platform behavior permits

---

## 2. Vision and Tracking Adversarial Conditions

- [ ] Dim indoor light
- [ ] Very low light
- [ ] Strong backlight
- [ ] Motion blur / fast hand movement
- [ ] Partial hand occlusion
- [ ] Partial face/eye occlusion
- [ ] Hand at frame edge
- [ ] Face at frame edge
- [ ] Camera shake
- [ ] Glasses on/off when relevant
- [ ] Multiple hands entering/leaving/crossing
- [ ] Tracking recovers without stale cursor/gesture replay

---

## 3. Gaze Direction and Pose

- [ ] Center gaze → center cursor
- [ ] Look left → cursor moves left
- [ ] Look right → cursor moves right
- [ ] Look up → cursor moves up
- [ ] Look down → cursor moves down
- [ ] Diagonal gaze is consistent
- [ ] Head neutral
- [ ] Head left/right yaw
- [ ] Head up/down pitch
- [ ] Head roll
- [ ] Combined yaw + pitch + roll
- [ ] One eye occluded: cursor may degrade/move, but no action fires
- [ ] Two eyes disagree: action is blocked
- [ ] Face lost: pending click/dwell/blink is cancelled
- [ ] Face reacquired: cursor returns without a visible teleport

---

## 4. Rotation / Display Mapping

- [ ] Portrait → landscape during tracking
- [ ] Landscape → portrait during tracking
- [ ] Cursor remains aligned at center, all four edges, and all four corners
- [ ] Mapping remains correct at different resolutions/densities
- [ ] Multi-window / split-screen where supported
- [ ] Keyboard/IME shown and hidden
- [ ] No double normalization or inset-offset error is visible

---

## 5. Gesture Reliability

- [ ] Open palm arms consistently
- [ ] Pinch START → click once
- [ ] Pinch HOLD → drag only after intentional movement
- [ ] Pinch END always releases the gesture
- [ ] Fast double pinch does not become duplicate clicks
- [ ] Swipe left/right/up/down dispatches exactly once per intentional swipe
- [ ] Swipe does not fire from pointer motion when open-palm gating is enabled
- [ ] Pose actions require stable deliberate holds
- [ ] Palm → Home fires once when the optional feature is enabled
- [ ] Custom gesture fires once per deliberate occurrence
- [ ] Low-confidence hand tracking suppresses discrete actions

---

## 6. Accessibility Action Safety

- [ ] dispatchGesture tap lands where the visible cursor is shown
- [ ] Drag begins only after the configured drag slop
- [ ] Drag releases on PINCH_END
- [ ] Swipe cancellation leaves no stuck press
- [ ] Screen/content change during an in-flight action is handled safely
- [ ] Keyguard / lock screen blocks unsafe gesture injection
- [ ] Accessibility interruption clears transient gesture state
- [ ] Service stop clears click/drag state
- [ ] Re-enable service starts from a clean state

---

## 7. Camera / Frame Ownership / Lifecycle

- [ ] Every camera analyzer frame is closed exactly once
- [ ] Camera startup failure recovers or reports a clear blocked state
- [ ] Camera permission revoked while running → safe stop
- [ ] Permission restored → tracking resumes
- [ ] Another camera app takes the camera → AirControl recovers when released
- [ ] Screen off → camera analysis pauses as designed
- [ ] Screen on / unlock → tracking resumes as designed
- [ ] Process/service recreation does not reuse closed MediaPipe objects

---

## 8. Thermal / Long Session

Run at least once for **60 minutes** on physical hardware.

- [ ] No ANR during 60-minute session
- [ ] No crash during 60-minute session
- [ ] Memory is bounded; no monotonic growth: `adb shell dumpsys meminfo com.aircontrol`
- [ ] CPU usage is recorded
- [ ] Battery drain is recorded
- [ ] Camera FPS is recorded
- [ ] Inference FPS is recorded
- [ ] Cursor update FPS is recorded
- [ ] Dropped frames are recorded
- [ ] End-to-end latency is recorded
- [ ] Thermal state remains within the chosen product profile
- [ ] Tracking remains usable after thermal throttling
- [ ] No repeated watchdog/restart loop

Repeat a shorter 15-minute soak after significant runtime changes.

---

## 9. Permissions / Boot / Notifications

- [ ] Camera permission denied → clear recovery path
- [ ] Camera permission revoked during runtime → clear state
- [ ] Accessibility disabled → app reports service unavailable
- [ ] Accessibility re-enabled → app reports connected state
- [ ] Notifications denied → core tracking still works; only affected notification features are unavailable
- [ ] Boot resume enabled → after reboot, a resume notification is shown; user taps Resume to start tracking where platform policy requires confirmation
- [ ] Boot resume disabled → no resume notification is posted by AirControl
- [ ] Notification Pause keeps user-paused state until explicit Resume

---

## 10. Calibration

- [ ] 9-point calibration UI and copy both say 9-point
- [ ] Stable samples only are collected
- [ ] Blink/closed-eye samples are rejected
- [ ] Monocular samples are not used for calibration
- [ ] Outliers are rejected
- [ ] Insufficient data fails safely with a retry path
- [ ] Linear fallback model can be saved and reloaded
- [ ] Quadratic model can be saved and reloaded
- [ ] Corrupt serialized model is rejected without crash
- [ ] Feature-schema mismatch is rejected
- [ ] Transform-signature mismatch is rejected
- [ ] Recalibration after large distance/lighting/glasses/device-position changes restores accuracy

---

## 11. Settings / UX Consistency

- [ ] Analysis FPS label matches the configured/actual supported operating point
- [ ] Eye tracking dependencies are explicit
- [ ] Blink Click explicitly explains that Eye is Mouse is required
- [ ] Cursor Smoothing is not mislabeled as pointer speed
- [ ] Cursor Reach / movement gain is distinct from smoothing
- [ ] Boot behavior is described as Resume Prompt, not unconditional auto-start
- [ ] Notification permission is described as optional
- [ ] Accessibility disclosure matches the actual `canRetrieveWindowContent` capability
- [ ] No stale "Vision Pro" terminology

---

## 12. TalkBack / Accessibility of the App UI

- [ ] All interactive elements have meaningful labels
- [ ] Sliders announce current values
- [ ] Toggles announce on/off state
- [ ] Gesture names and actions are readable by TalkBack
- [ ] Navigation works with TalkBack gestures
- [ ] 200% font scaling remains usable
- [ ] High contrast / reduced motion preferences do not break controls

---

## Sign-Off

| Role | Name | Date | Result |
|---|---|---|---|
| QA Lead | | | |
| Dev Lead | | | |
| Product | | | |

**Release is blocked if any P0/P1 device test fails or if required automated CI gates are not green.**

## Regression Scenarios

| # | Scenario | Expected |
|---|---|---|
| R1 | Enable Accessibility from Settings while AirControl is in the background | service connects without disabling itself |
| R2 | Toggle Accessibility off/on 5× | no crash, overlays and engine return cleanly |
| R3 | Force-stop, then re-enable service | no stale MediaPipe or overlay state |
| R4 | Camera service disappears while gestures enabled | watchdog attempts recovery and reports blocked reasons when recovery is not permitted |
| R5 | Move pointer across a list | pointer motion alone does not scroll when open-palm swipe gating is enabled |
| R6 | Fist for ~1s while armed | disarm occurs once; no unintended action |
| R7 | Deliberate thumb-up hold | volume action occurs once |
| R8 | Pinch Next during calibration | Next is activated once; no stray background action |
| R9 | Notification Stop | tracking remains stopped until user enables/resumes it |
| R10 | Rotate a tablet mid-session | mapping/overlay recompute without jump or freeze |
| R11 | Corrupt local DataStore state | app falls back to safe defaults; no crash loop |
| R12 | Start without camera permission | onboarding/recovery path is clear; no crash |
| R13 | Screen off for 1 min | camera analysis pauses; unlock recovery follows documented lifecycle |
| R14 | Calibration: pinch + hard sweep simultaneously | intended calibration click wins; unrelated navigation does not fire |
| R15 | Another camera app takes camera | AirControl recovers cleanly after release |
