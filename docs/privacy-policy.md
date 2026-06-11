# AirControl Privacy Policy

**Last updated: June 11, 2026**

## Overview
AirControl is built with a privacy-first architecture. This policy explains our data practices—which are minimal by design.

## Data We Collect
**None.** AirControl does not collect, transmit, store on remote servers, or share any personal data whatsoever.

## On-Device Processing
AirControl processes camera data **entirely on your device** using Google's MediaPipe Hand Landmarker. This processing happens in real-time:
- Camera frames are analyzed for hand landmark positions
- Hand positions are classified into gestures
- Gestures are mapped to device actions via the Accessibility Service

**No camera frames, images, video, or hand position data is ever:**
- Recorded or saved
- Transmitted to any server
- Shared with any third party
- Used for any purpose other than real-time gesture recognition

## Local Data Storage
AirControl stores the following data **locally on your device only**:
- User preferences (sensitivity, hand preference, gesture mappings, etc.)
- Onboarding completion status
- Calibration data (hand size, pinch distance)

This data is stored using Android's DataStore and is never backed up to or synchronized with any cloud service.

## Network Access
AirControl does not request the `INTERNET` permission. The app has no network capability and cannot transmit data even if it wanted to.

## Accessibility Service
AirControl uses Android's Accessibility Service solely to perform gesture actions on the user's behalf. We do not read, observe, collect, or transmit any on-screen content. The `canRetrieveWindowContent` capability is explicitly disabled.

## Third-Party Services
AirControl does not use any third-party analytics, advertising, or data collection services. Period.

## Children's Privacy
Since we collect no data, our practices are inherently compliant with children's privacy regulations (COPPA, GDPR-K).

## Changes to This Policy
If we ever change our data practices (we don't plan to), we will update this policy and notify users through the app.

## Contact
For privacy questions, open an issue on our GitHub repository.
