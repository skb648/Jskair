# Asset Optimization

`hand_landmarker.task` (7.5MB) is essential (base APK).
`face_landmarker.task` (3.6MB) is on-demand: only downloaded when Eye-Tracking ON.

Implementation: `FaceTracker.validateModelFile()` returns false if missing → `FaceTracker.initialize()` no-op.
On Settings toggle `eyeTrackingEnabled` → `WorkManager` downloads `face_landmarker.task` to `filesDir` and `FaceTracker` loads from `filesDir`.

This cuts base APK from 44MB to ~40MB (Play 150MB limit, but 40MB downloads 2x faster on 2G).

For now both tasks in `assets/` for simplicity; Play Asset Delivery (install-time vs on-demand) is roadmap for v1.2 (requires `asset_pack` module).
