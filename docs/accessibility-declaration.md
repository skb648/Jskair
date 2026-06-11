# Accessibility API Usage Declaration

## Service Type
AirControl uses `android.accessibilityservice.AccessibilityService` to enable system-wide air gesture control.

## Declared Capabilities
```xml
<accessibility-service
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="false"
    android:notificationTimeout="100" />
```

## Purpose
AirControl's Accessibility Service allows users with motor impairments—or anyone seeking touch-free device interaction—to control their Android device using hand gestures detected via the front camera.

## How It Works
1. The front camera captures hand position using MediaPipe Hand Landmarker
2. A pure-Kotlin gesture engine classifies hand poses and dynamic gestures
3. Mapped actions are dispatched through the Accessibility Service API:
   - `dispatchGesture()` for touch simulations (tap, scroll, drag)
   - `performGlobalAction()` for navigation (back, home, recents, notifications)
   - `AudioManager` for volume and media control

## What We Do NOT Do
- Read or observe any on-screen content or window hierarchy
- Collect, transmit, or store any user data
- Intercept or modify user input
- Access personal information (messages, contacts, etc.)
- Require network access

## User Control
- The service can be enabled/disabled at any time via Android Settings → Accessibility
- All gesture mappings are user-configurable
- The service can be stopped via the persistent notification or the app's home screen
- No action is performed without explicit user arming (open palm hold gesture)

## Data Handling
All processing is on-device. No data leaves the device at any point. Camera frames are processed in real-time and immediately discarded. No recordings, screenshots, or images are ever stored.
