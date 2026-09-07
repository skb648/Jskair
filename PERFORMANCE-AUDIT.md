# Performance & Lifecycle Audit — 2026-09-07

**Inputs:** repository `main` @ `470ea5c` (github.com/skb648/Jskair), device logcat
`logcat_2026-09-07_15-12-49.txt` (13,902 lines, 15:06:18 → 15:12:49, front camera
id=1, API 36, debug build `com.aircontrol.debug`, pid 7847).

**Scope guard:** gesture semantics, gesture thresholds, gesture FSM,
ActionDispatcher semantics, cursor behavior and the HID POC were **not**
modified by any change in this pass. The frame-interval/dropout hardening from
commit `7d9bae4` (bug #11) in `gesture-engine/GestureEngine.kt` is intact.

---

## Confirmed issues (root cause proven from source + logcat)

### P1 — Watchdog revival churn while the Activity is invisible
**Evidence (logcat):** 30× repeating every 5 s from 15:06:18 onward:
```
W/GestureControlAccessibilityService  Watchdog: gestures enabled — reviving camera
V/GestureControlAccessibilityService  Camera start deferred: AirControl Activity is not visible
```
**Root cause:** `startCameraWatchdog()` ticks every 5 s; its revival branch only
checks `gesturesEnabled && !isTracking && permissionGranted`, but
`startCameraService()` immediately no-ops when `!MainActivity.isVisible`. Result:
a pointless attempt plus a WARN-level log every 5 s forever while backgrounded
(WARN passes the release `ReleaseTree` filter → visible spam in production).
**Fix:** gate the revival attempt on `MainActivity.isVisible` via a pure,
unit-testable `CameraRevivePolicy.shouldAttemptRevive(...)`; rate-limit the
warning log. Watchdog remains able to revive as soon as the Activity is visible.

### P2 — Camera/tracker lifecycle churn; triple teardown per stop; model reloads
**Evidence (logcat 15:11:44.805→15:11:45.661):** one user stop →
`HandTracker closed` ×3, `Tracking stopped` ×3, `Thermal monitoring stopped` ×2–3,
`CameraService destroyed`; restart at 15:11:55 reloads `HandLandmarker` from
assets (129 ms) and restarts thermal monitoring. Same pattern at 15:09:40–41.
**Root causes:**
1. Two owners send `ACTION_STOP` for one user action (accessibility settings
   collector + UI/debug ViewModel paths) — the duplicate intent re-creates the
   foreground service just so it can stop itself.
2. `CameraService.onDestroy` re-runs the full `stopTrackingLocked()` teardown.
3. `stopTrackingLocked()` closes the `@Singleton` MediaPipe trackers on every
   stop → every start reloads both models from assets ("excessive tracker
   initialization").
**Fix:** make `stopTrackingLocked()` idempotent (early return when not running);
lightweight `onDestroy` (no repeated full teardown); keep initialized trackers
alive across stop/start — close them only on explicit disable, memory pressure,
or the watchdog's restart-recovery path; `CameraServiceManager.stop/pause/resume`
skip the intent entirely when the service is not running.

### P3 — Heavy tracker init/close on the MAIN thread (debug screen)
**Evidence (logcat):** `15:10:09.794 7847:7847 W/HandTrackerImpl HandTracker
already initialized` — tid 7847 = main thread.
**Root cause:** `DebugViewModel.startTracking()` calls `handTracker.initialize()`
(asset load + native model creation) on `viewModelScope` (Main.immediate);
`stopTracking()` calls `handTracker.close()` (which unconditionally awaits a
200 ms latch + native close) on Main; `onCleared()` blocks Main in
`analysisExecutor.awaitTermination(2, SECONDS)`.
**Fix:** run tracker init/close off-Main (`Dispatchers.Default`); drop the
blocking `awaitTermination` on the main path; combine with P7's close redesign.

### P4 — Debug-screen / CameraService shared-tracker race
**Evidence (logcat 15:10:09.79–09.93):** debug screen opens → `initialize()`
no-ops ("already initialized") → debug sends `ACTION_STOP` → CameraService's
`stopTrackingLocked()` closes the shared singleton tracker **after** the debug
screen took it over → debug `processFrame` calls silently no-op until the screen
is closed and the service reloads the model.
**Root cause:** shared `@Singleton` tracker with no ownership handshake.
**Fix:** debug flow waits for `CameraService.isRunning == false` (off-Main, with
timeout) before binding the camera; P2 ensures a service stop no longer kills a
tracker another owner is using; P7 removes the close/process race itself.

### P5 — Thermal FPS mapping applies instantly, no hysteresis/debounce
**Evidence (logcat):** `Thermal status changed: NONE → SEVERE` fires immediately
at every monitor start; caps FPS to 8; 30↔10 FPS oscillation observed.
**Source proof:** `ThermalMonitor` polls every 5 s and
`applyThermalThrottling` maps each single sample directly to an FPS tier with no
debounce — a device oscillating across a thermal threshold flips configured FPS
every poll, and each flap cancels the 30 s recovery ramp.
`stopMonitoring(resetStatus=true)` also synthesizes spurious NONE transitions.
**Fix:** new pure `ThermalGovernor` — worsening applies only after N consecutive
confirming samples (immediate for CRITICAL/EMERGENCY), relaxation requires
sustained lower status + cooldown, giving hysteresis with no FPS oscillation.
Deterministic unit tests included.

### P6 — ~1.2 MB per-frame bitmap allocation in the analyzer hot path
**Evidence (logcat GC):** repeated `Background young concurrent … freed
4(4816KB) LOS objects` (4816/4 = 1204 KB = 640×480 ARGB_8888), also 9(10MB),
10(11MB) variants — large-object space churned every GC while tracking.
**Root cause:** `CameraService.imageProxyToMPImage()` calls `imageProxy.toBitmap()`
on the default YUV_420_8888 stream: CameraX allocates a full ARGB_8888 bitmap per
analyzed frame for CPU YUV→RGB conversion. The rotation/mirror target bitmap is
already reused; the source bitmap cannot be reused in YUV mode.
**Fix:** request `OUTPUT_IMAGE_FORMAT_RGBA_8888` from `ImageAnalysis`; `toBitmap()`
then wraps the existing buffer (no new allocation). Wrapped bitmap is never
recycled. One-time automatic fallback to the YUV path if binding fails.

### P7 — Tracker `close()`: unconditional 200 ms block + close/process race
**Source proof:** `HandTrackerImpl.close()`/`FaceTrackerImpl.close()` set
`isClosing`, then `latch.await(200 ms)` — the latch counts down only when an
async result arrives, so every close with no in-flight inference still blocks
200 ms (on Main via P3). Meanwhile `processFrame()` reads the landmarker without
synchronizing against `close()` — a `detectAsync` submission can interleave with
native `close()` (native crash window; the "camera close contention" on the
tracker side).
**Fix:** synchronize submission vs close under the same lock; drop the
unconditional latch wait (MediaPipe `close()` drains its own graph).

### P8 — Face/eye advanced pipeline runs every frame with no consumer
**Source proof:** `FaceTrackerImpl.handleResult` runs the full advanced pipeline
per frame while initialized: 478 `FaceLandmark` allocations, `EyeFeatureExtractor`,
`HeadPoseEstimator`, `HeadPoseNormalizer`, feature-vector build. The only
consumers of `featureVector` are the personalized model (usually null) and
`GazeCalibrationViewModel` (only while a calibration session is collecting).
CameraService's "face fps" collector reads only `obs.faceDetected`. In normal
eye mode the per-frame feature work is pure overhead competing with the hand
pipeline (which has priority).
**Fix:** gate the advanced pipeline on `personalizedModel != null ||
calibrationCollecting` (flag set by `GazeCalibrationViewModel` during
collection). `faceDetected`/blink path untouched.

### P9 — No memory-pressure / power-saver handling
**Source proof (grep):** no `onTrimMemory`, no `ComponentCallbacks2`, no
`PowerManager.isPowerSaveMode`, no battery-state listener anywhere in
`app/src/main`. The only "battery saver" is a user preference
(`UserPreferences.batterySaver`, default `false`). OS power-saver mode, memory
pressure and battery state are indistinguishable to the app; nothing degrades or
recovers under system pressure.
**Fix:** pure `ResourceGovernor` (deterministic, unit-tested): OS power-saver →
FPS cap; `TRIM_MEMORY_RUNNING_CRITICAL/MODERATE` → release idle trackers;
recovery when the condition clears. **Low battery alone causes no throttle**
(no evidence it implies CPU throttling — per design constraint).

### P10 — Redundant service (re)starts from `CameraServiceManager`
**Source proof:** `stopTracking()`/`pauseTracking()`/`resumeTracking()` call
`startService(...)` unconditionally, even when the service is not running — on a
visible app this **creates** a service instance whose only job is to stop itself
(matches the duplicate lifecycle events in P2's evidence).
**Fix:** skip the intent when `!isRunning` for stop/pause; resume still starts.

### P11 — Watchdog/diagnostic logging not rate-limited
See P1: WARN every 5 s even in release builds. Other hot-path logs verified as
transition-based or debug-only. **Fix:** rate-limited logging on the watchdog
path; new telemetry logs rate-limited to ≥30 s and debug-gated.

### P12 — Startup main-thread stall (partially proven)
**Evidence (logcat):** `Skipped 51 frames!` + `Davey! duration=1173ms` at cold
start 15:09:04. The heavy main-thread items proven in this audit (P3) hit the
debug-screen transition, not cold start. Cold start was measured on a
**debuggable** build (no R8, LeakCanary, first-boot dex verification).
→ **Hardware-validation pending** for release builds; no speculative fix.

### P13 — Dependency/network permission warnings (explained, benign)
**Evidence (logcat):** 12× `W/TransportRuntime Error scheduling event
ConnectivityService: Neither user 10285 nor current process has
android.permission.ACCESS_NETWORK_STATE` over ~6 min.
**Root cause:** the manifest deliberately strips `INTERNET` and
`ACCESS_NETWORK_STATE` (`tools:node="remove"`, privacy fixes #45/#88) while
MediaPipe tasks-core pulls in Google DataTransport, which keeps probing for
connectivity events. Log noise only; no retry storm; negligible cost.
**No code fix** — re-adding the permission would weaken the privacy posture.
Documented here.

### P14 — Storage pressure handling
**Verified:** nothing in the hot path writes to disk (no file I/O in camera or
tracking paths; preferences writes happen only on explicit user actions off the
frame path). Low storage is not a performance factor in this app.
**No fix needed** — documented; low storage ≠ low RAM and is not conflated.

## Verified-sound areas (no change needed)
- **Bounded queues / backpressure:** `ImageAnalysis` uses
  `STRATEGY_KEEP_ONLY_LATEST` + dedicated single-thread executor; hand/face
  SharedFlows use `extraBufferCapacity = 64` with `DROP_OLDEST`. No unbounded
  queue exists.
- **Frame closure:** `imageProxy.close()` in `finally` on all paths (paused,
  throttled, error); `mpImage.close()` in `finally`.
- **Tracker init off-main in CameraService:** `withContext(Dispatchers.Default)`.
- **Frame-interval estimator dropout hardening** (`7d9bae4`, bug #11) intact.
- **setMaxSurfaceArea 1920×1200** in the log is HWUI/display sizing (MainActivity
  955×1200 window), **not** the camera analysis stream (which is 640×480).
- **`W/NativeHidController` disconnect** at startup is benign POC behavior.

## Hardware-validation pending (not provable from source/logs)
1. Cold-start jank magnitude on a **release** build (P12).
2. Real thermal-status dynamics on physical hardware (this capture's environment
   reports a persistent SEVERE; whether real hardware oscillates across tiers
   must be observed via the new telemetry thermal trace).
3. `libpenguin.so` dlopen failure (`E/ircontrol.debug`) — not referenced anywhere
   in this repository; injected by the device/emulator debug environment.
4. Whether `OUTPUT_IMAGE_FORMAT_RGBA_8888` engages zero-copy on the target device
   (fallback keeps the YUV path; telemetry will show the allocation delta).
5. OEM CPU-throttling vs thermal-status correlation — needs per-OEM measurement.

## Instrumentation added (`PerfTelemetry`)
Pure in-memory ring-buffer singleton recording: camera frame intervals
(min/max/p50/p95 + drops), analyzer processing time, hand/face inference
duration, configured-vs-actual FPS, throttle-drop counts, thermal status
transitions, battery/power-saver state changes, memory-pressure callbacks,
watchdog actions, camera lifecycle transitions, tracker init/close events.
Surfaced on the Debug screen; rate-limited (≥30 s) summary logging gated by
`PerfTelemetry.enableLogging` (default = `BuildConfig.DEBUG`; the release log
tree is WARN+ so release logs stay clean).

---

## Additional findings (P15, P17, P18)

### P15 — Debug-screen analyzer leaked one MPImage per frame
**Source proof:** `DebugViewModel.processDebugFrame` built an `MPImage` via
`BitmapImageBuilder` but never closed it (the service path always did) —
reference-counted native storage leaked per frame while the debug screen ran.
**Fix:** close in `finally` after submission (same contract as `CameraService`).

### P17 — Configured-vs-actual FPS never observable
`AdaptiveFpsController.updateConfiguredFps` now records the accepted value into
`PerfTelemetry` (observer only — the controller's scan-mode logic is untouched).

### P18 — No instrumentation existed at all
All "how long / how often" questions (frame intervals, inference latency, drop
counts, lifecycle transitions, watchdog actions) were answerable only from
retrospective logcat archaeology. `PerfTelemetry` (above) plus call sites in
`CameraService`, trackers, `AdaptiveFpsController`, `AirControlApp` and the
watchdog make them first-class, live, and testable. (P16 folded into P5.)

---

## Files changed (exact)

**New production files**
| File | Purpose |
|---|---|
| `app/.../accessibility/CameraRevivePolicy.kt` | Pure decision policy for watchdog revival (P1/P11) |
| `app/.../tracking/ThermalGovernor.kt` | Hysteresis/debounce over raw thermal samples (P5) |
| `app/.../runtime/ResourceGovernor.kt` | Power-save FPS cap + trim-level memory policy (P9) |
| `app/.../runtime/PerfTelemetry.kt` | Bounded ring-buffer telemetry, rate-limited logging (P18) |

**Modified production files**
| File | Change |
|---|---|
| `accessibility/GestureControlAccessibilityService.kt` | Watchdog uses `CameraRevivePolicy`; transition-only rate-limited logging; revive telemetry |
| `camera/CameraService.kt` | Idempotent `stopTrackingLocked`; trackers closed only on explicit disable; lightweight `onDestroy`; RGBA_8888 analysis output + YUV fallback; no source-bitmap recycle; power-save receiver (register on start, unregister on stop/destroy); thermal sampling through `ThermalGovernor` (throttle only on transition); `applyConfiguredFps()` = min(base, power-save cap); analyzer telemetry (interval, drops, duration) |
| `service/CameraServiceManager.kt` | stop/pause early-return when service not running (P10) |
| `tracking/HandTracker.kt` | Synchronized submit-vs-close; no 200 ms latch; idempotent close; inference-latency telemetry; "already initialized" W→D |
| `tracking/FaceTracker.kt` | Same close/submit hardening; `setCalibrationCollecting`; advanced pipeline gated (P8); inference-latency telemetry |
| `tracking/ThermalMonitor.kt` | Added every-poll `thermalSamples` SharedFlow (StateFlow kept) (P5) |
| `tracking/AdaptiveFpsController.kt` | `recordConfiguredFps` observation only (P17) |
| `ui/debug/DebugViewModel.kt` | Init off-Main; bind waits for service stop (2.5 s timeout); executor teardown off-Main; MPImage close (P15); RGBA output + no source recycle |
| `ui/debug/DebugScreen.kt` | Live telemetry row (1 Hz poll, debug screen only) |
| `ui/gazecalibration/GazeCalibrationViewModel.kt` | Sets/clears `calibrationCollecting` around the collection window (try/finally + onCleared) |
| `AirControlApp.kt` | `onTrimMemory` → classify + release idle trackers off-Main (only when no session and no exclusive camera user); telemetry enabled only in debug builds |

**New test files:** `CameraRevivePolicyTest` (10), `ThermalGovernorTest` (11),
`ResourceGovernorTest` (10), `PerfTelemetryTest` (12) — 43 new deterministic JVM
tests (explicit-time, no sleeps).

## Test results (before → after)

| Phase | Baseline | After changes |
|---|---|---|
| `:app` non-Robolectric JVM tests | 12 classes, 116 tests, 0 failures | 16 classes, **159 tests, 0 failures** |
| `:app` Robolectric (`ActionDispatcherTest`, `SettingsRepositoryImplTest`, `PersonalizedGazeCalibrationTest`) | 82 tests, 0 failures | **82 tests, 0 failures** |
| `:gesture-engine:test` | 200 tests, 0 failures | **200 tests, 0 failures** |
| **Total** | 398 / 0 failures | **441 / 0 failures** |

The new tests caught three real defects in the new code before they shipped —
all fixed and re-verified green:
1. `ResourceGovernor.classifyTrim` compared trim levels numerically; Android
   trim bands are not globally ordered (`UI_HIDDEN=20` outranked
   `RUNNING_CRITICAL=15`). Now an explicit per-level mapping.
2. `PerfTelemetry.percentile` used floor interpolation, so a p95 over
   95×40 ms + 5×400 ms reported 40 ms — exactly the stall-hiding failure mode
   the instrumentation exists to prevent. Now ceiling-rank.
3. `PerfTelemetry.maybeLogSummary` used `0L` as "never logged", breaking the
   30 s window whenever the first summary emitted at t=0. Now nullable.
4. `PerfTelemetry.recordFrameProcessed` updated its interval baseline even on
   a BACKWARDS timestamp, letting a late dropout echo drag the baseline down
   and emit a bogus inflated interval — precisely the estimator-poisoning
   class bug #11 hardened against. Non-advancing timestamps are now ignored
   entirely, and the `-1` "no baseline" sentinel replaced the ambiguous `0L`.

## Before/after measurement status

Proven statically / by test now:
- Per-frame allocation removed from the analyzer hot path (RGBA wrap vs 1.2 MB
  ARGB alloc — the LOS churn pattern in the capture cannot recur on the RGBA
  path); allocations eliminated in the face pipeline's idle tier (feature
  vector + normalizer objects per frame → zero when unconsumed).
- Main-thread blocking removed: 200 ms tracker close, model init, 2 s executor
  await — all off-Main by construction (verified by the removed code paths).
- Log volume: watchdog path is transition-only with a 60 s repeat floor;
  telemetry summaries ≥30 s apart, debug-only.
- FPS stability: `ThermalGovernor` transitions require 2 confirming samples to
  worsen (CRITICAL excepted) / 3 + 30 s cooldown to relax — unit-tested.

Requires hardware (debug build with `PerfTelemetry` enabled):
- Actual frame-interval p50/p95 before/after, real thermal-tier dynamics,
  RGBA zero-copy engagement, cold-start jank on a release build. The Debug
  screen now shows these live (actual/configured FPS, p50/p95 interval, drop
  counts, analyzer/hand/face latencies); the `PerfTelemetry` logcat line
  repeats them every ≥30 s for capture.

## Confirmation

- Gesture semantics, thresholds, FSM, `ActionDispatcher` semantics, cursor
  behavior, HID POC: **unmodified** (all 35 `ActionDispatcherTest`, 22
  `GestureMapConfigTest`, 18 `GestureDetectorImplTest`, 200 gesture-engine
  tests pass unchanged).
- Frame-interval/dropout hardening (commit `7d9bae4`, bug #11): **untouched** —
  `gesture-engine/` has zero modifications in this pass.
