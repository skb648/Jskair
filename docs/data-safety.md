# AirControl Data Safety Disclosure

## Data Processing Summary
AirControl performs camera, face, eye, and hand tracking on the Android device. The tracking pipeline does not upload camera or gesture data to a remote server.

| Data / Signal | Remote collection | Remote sharing | Local use | Purpose |
|---|---|---|---|---|
| Camera frames | No | No | Transient | Real-time hand/face tracking |
| Eye landmarks / gaze values | No | No | Transient | Cursor and gaze interaction |
| Hand landmarks / gesture values | No | No | Transient | Gesture recognition and actions |
| User preferences | No | No | Yes | Settings and feature configuration |
| Hand calibration | No | No | Yes | Personalize gesture thresholds |
| Personalized gaze calibration | No | No | Yes, when created | Personalize gaze mapping |
| Accessibility UI metadata | No | No | Transient | Native-like cursor feedback / hit testing |

### What stays on the device
Camera and tracking information can exist in volatile memory while processing. User preferences and calibration state are persisted locally with Android DataStore when the relevant features are used.

### Accessibility Service
AirControl requests accessibility capabilities needed to perform user-configured actions. The service also enables `canRetrieveWindowContent=true` and `flagRetrieveInteractiveWindows` for the native-like cursor path.

The cursor hit-test reads a bounded subset of accessibility metadata under the pointer, such as window bounds and basic node properties. Raw `AccessibilityNodeInfo` objects are not retained after the lookup. This metadata is not transmitted to remote servers.

### Network Access
AirControl does not request `android.permission.INTERNET` for its runtime tracking pipeline and does not use a remote inference backend.

### Third-Party Libraries
MediaPipe, CameraX, Hilt, Compose, Coroutines, DataStore, and other dependencies execute according to their Android runtime behavior. AirControl's tracking data flow is local to the device.

### Release Data-Safety Review
The declarations in this document must be reviewed against the actual manifest, accessibility-service XML, persistence layer, and release APK whenever those components change. This document does not replace the platform store's own data-safety form or legal review.
