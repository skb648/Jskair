# AirControl Data Safety Disclosure

## Data Collection
AirControl does **not** collect, transmit, or store any user data.

| Data Type | Collected | Shared | Purpose |
|-----------|-----------|--------|---------|
| Camera feed | No* | No | Processed on-device only for hand tracking |
| Hand landmarks | No* | No | Computed on-device, never persisted |
| Gesture events | No | No | Transient, used only for immediate action dispatch |
| Settings | No† | No | Stored locally in DataStore, never transmitted |
| Device info | No | No | Not collected |

\* Camera frames are processed in real-time by MediaPipe on-device. No frames, images, or video are recorded, stored, or transmitted. Hand landmark coordinates exist only in volatile memory and are discarded after each frame.

† User preferences (sensitivity, gesture mappings, etc.) are stored exclusively in the device's local DataStore. No cloud backup or synchronization occurs.

## Network Access
AirControl does not request `android.permission.INTERNET` and has no network capability. All processing is 100% on-device.

## Accessibility Service Usage
AirControl uses Android's Accessibility Service exclusively for:
- Dispatching touch gestures (tap, scroll, drag) on behalf of the user
- Performing global navigation actions (back, home, recents)
- Adjusting volume and media playback

The service does **not** read, observe, or collect any window content. The `canRetrieveWindowContent` capability is explicitly set to `false`.

## Third-Party Libraries
- **MediaPipe Hand Landmarker**: Processes camera frames entirely on-device. No data leaves the device.
- **CameraX**: Camera frame capture only. No recording or transmission.
- **All other libraries** (Hilt, Compose, Coroutines, DataStore, Timber): Standard Android libraries with no data collection.
