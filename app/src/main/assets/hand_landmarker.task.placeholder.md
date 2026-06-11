# Hand Landmarker Model - IMPORTANT

This is a placeholder file. The actual MediaPipe hand_landmarker.task model
MUST be downloaded and placed in this directory for the app to function.

## Download URL

https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task

## Installation

1. Download the model from the URL above
2. Place the downloaded file at: `app/src/main/assets/hand_landmarker.task`
3. DELETE this placeholder file (`hand_landmarker.task.placeholder.md`)
4. Rebuild the project

## Model Details

- **Name**: hand_landmarker.task
- **Format**: MediaPipe Task Bundle (float16)
- **Size**: ~10MB
- **Source**: Google MediaPipe Solutions
- **License**: Apache 2.0
- **Version**: latest

## Verification

The HandTracker class validates the model file exists in assets during
initialization. If the model is missing, an error is logged and tracking
will not start.

## Note for CI/CD

Add the model file to your .gitignore but ensure it's available in your
build environment. You can download it as part of your CI pipeline:

```bash
curl -o app/src/main/assets/hand_landmarker.task \
  https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task
```
