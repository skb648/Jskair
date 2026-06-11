# Changelog

All notable changes to AirControl will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-06-11

### Added
- Air gesture control via front camera and MediaPipe hand tracking
- 7 static poses: Open Palm, Fist, Pinch, Pointing, Victory, Thumb Up, Thumb Down
- 4 dynamic swipe gestures: Left, Right, Up, Down
- Gesture state machine: Disarmed → Arming → Armed → Executing → Cooldown
- Accessibility service for system-wide gesture dispatch
- Cursor mode with overlay dot, exponential smoothing, and edge margin expansion
- Configurable gesture-to-action mapping with conflict detection
- 4-step onboarding flow (Welcome, Camera, Accessibility, Overlay)
- Settings: sensitivity, cursor speed, hold duration, hand preference, FPS, haptics
- Calibration flow for personalized hand size and pinch thresholds
- Adaptive FPS: full speed on hand detection, 5fps scan mode when idle
- One Euro Filter for jitter reduction on all 21 hand landmarks
- Start on boot via BOOT_COMPLETED receiver (conditional on prior state + permissions)
- Status overlay pill showing armed/disarmed state
- Debug screen with live camera preview and skeleton overlay
- Dark-first Material 3 theme (#0D1117 / #2F81F7)
- All processing on-device, no network access required
- Thermal monitoring with auto-FPS reduction and pause on severe throttling
- Frame pipeline with supervisorScope error recovery and 5s watchdog
- ProGuard/R8 full mode with verified MediaPipe keep rules
- GitLab CI pipeline (lint, unit tests, assembleRelease, instrumented tests)

### Security
- Accessibility service declared with canPerformGestures=true, canRetrieveWindowContent=false
- No camera data recorded, transmitted, or stored
- No network permissions requested
