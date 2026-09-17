# AirControl Privacy Policy

**Last updated: September 17, 2026**

## Overview
AirControl is designed to process camera and interaction signals locally on the Android device. This policy describes what is processed, what is stored locally, and what is not transmitted to remote servers.

## Camera and Tracking Data
Camera frames are processed on-device for real-time hand and face tracking. AirControl does not record, upload, or transmit camera frames, images, video, eye landmarks, hand landmarks, or gesture frames to a remote server.

Tracking values may exist transiently in volatile memory while the app is running. They are discarded as the processing pipeline advances unless explicitly represented by user settings or calibration state described below.

## Local Data Storage
AirControl stores limited configuration data locally on the device, including:
- user preferences such as sensitivity, hand preference, enabled features, and gesture mappings;
- onboarding state;
- hand calibration measurements; and
- personalized gaze calibration data when the user creates it.

This information is stored locally through Android DataStore. AirControl does not synchronize these settings with a remote server.

## Network Access
The app does not request the `android.permission.INTERNET` permission and does not use a remote backend for its tracking or gesture-processing pipeline. The application code and release manifest explicitly remove inherited network permissions where applicable.

## Accessibility Service
AirControl uses Android's Accessibility Service to perform enabled taps, swipes, drags, navigation, volume/media actions, and other user-configured actions.

The service also has `canRetrieveWindowContent=true` and `flagRetrieveInteractiveWindows` because the optional native-like cursor can inspect a bounded accessibility view of the location under the cursor. The cursor hit-test reads limited UI metadata such as window bounds and basic node properties (for example class, clickable, editable, enabled, and supported click action). The app does not retain raw `AccessibilityNodeInfo` objects after the lookup.

This accessibility metadata is processed locally. It is not uploaded to a remote service and is not intended to identify the user.

## Third-Party Processing
MediaPipe and CameraX run as local application dependencies. AirControl does not send tracking data to an analytics, advertising, or cloud inference provider.

## Security and Secrets
Release signing credentials are supplied through the build/release environment rather than committed to application source code. Private keys and passwords should never be added to the repository.

## Children's Privacy
The application is not designed as an online service and does not transmit tracking data to remote servers. Local settings and calibration information can still exist on the device, so users should manage device access appropriately.

## Changes to This Policy
This policy should be updated whenever the application's data flows, local persistence, accessibility capabilities, or network behavior materially changes.

## Contact
For privacy questions, open an issue on the GitHub repository.
