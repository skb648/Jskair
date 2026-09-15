# AirControl — Release-first fix report (2026-09-14)

Scope: P0 "signed Release APK: accessibility service does nothing; Debug works", then P1 pipeline items that
could be confirmed from source. Everything below was verified against the **actual built APKs**
(`aapt2`, `apksigner`, `zipalign`, `dexdump`, R8 `mapping.txt`/`configuration.txt`/`usage.txt`), not from
build logs or earlier audit documents (which were treated as untrusted).

**No physical device or emulator was available** (no `/dev/kvm`, no ADB device). Nothing here was
executed on Android hardware. See "Remaining limitations".

---

## 1. Root cause chain (P0)

### 1.1 What was *not* the cause — verified, so nobody re-investigates it

| Hypothesis | Verification on `app-release.apk` | Result |
|---|---|---|
| Service class missing / renamed by R8 | `dexdump`: `Lcom/aircontrol/accessibility/GestureControlAccessibilityService;` present, all overrides present; `onServiceConnected` bytecode flow matched source | Not the cause |
| Merged manifest differs | Normalized `aapt2 xmltree` diff debug vs release: only debug tooling (leakcanary, Compose preview activities, `debuggable`, storage perms) and release-only `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (from `ContextCompat.registerReceiver(RECEIVER_NOT_EXPORTED)`, benign) | Not the cause |
| `accessibility_service_config.xml` shrunk/renamed | Resource shrinker renamed the *file* to `res/-N.xml` but resource id `0x7f100000` is intact and referenced from the `<meta-data>`; `settingsActivity`/flags/capabilities intact | Not the cause |
| Hilt entry point broken (`EntryPoints.get` reflective cast) | `AccessibilityServiceEntryPoint` kept (was `o1` via Hilt's `allowobfuscation` rule); generated `SingletonC`/`ServiceC` impls present; `Hilt_AirControlApp` present; no `R8$$REMOVED` for any DI type | Not the cause |
| MediaPipe JNI / protobuf stripped | Every class the `.so` resolves via JNI (`framework.{Graph,Packet,PacketCreator,AndroidPacketCreator,AndroidAssetUtil,PacketGetter}`, `tasks_core.ModelResourcesCache*`) is unobfuscated; `HandLandmarkerGraphOptions`, `BaseOptions`, `CalculatorOptions` keep field names; `PARSER`/`ext` fields present | Not the cause |
| Model assets / native libs missing | `face_landmarker.task` 3,758,596 B and `hand_landmarker.task` 7,819,105 B identical in both APKs; 19 `.so` in both (arm64-v8a, armeabi-v7a, x86 — no x86_64 in upstream AAR) | Not the cause |
| Enum `name()`/`valueOf` used for gesture map / poses | Enum classes are renamed but every constant **string** (`OPEN_PALM`, `PINCH`, …) is in `<clinit>`, so `name`/`valueOf` round-trips | Not the cause |
| Coroutines `Dispatchers.Main` ServiceLoader | `MainDispatcherLoader` `<clinit>` rewritten by R8 to instantiate `AndroidDispatcherFactory` directly | Not the cause |
| `androidx.startup` initializers | Kept by library consumer rules | Not the cause |
| Signature / alignment | v2+v3 verified; 16 KB page alignment OK | Not the cause |
| R8 missing-class warnings | 0 | — |

### 1.2 What *was* the cause — a chain of three release-only behaviours

1. **Every startup stage log was invisible in release.**
   `Timber.d` is removed by R8 (`-assumenosideeffects` in `proguard-rules.pro`), and `Timber.i` is dropped
   by `AirControlApp.ReleaseTree` (WARN+). `onServiceConnected`, `startTrackingPipeline`, `startCameraService`,
   `HandTrackerImpl.initialize`, `FaceTrackerImpl.initialize`, `CameraService.onCreate/startTrackingLocked`
   were all `Timber.d`/`Timber.i`. A stalled or blocked stage in a signed build produced **zero** logcat
   output — indistinguishable from "the service never started". Several failure branches were also
   `Timber.d`/`Timber.v` (e.g. "Foreground camera start deferred: Activity not visible" was `Timber.v`).

2. **Service readiness was coupled to the camera start.**
   `publishConnectionState(true)` ran only *after* `createOverlays()` **and** `startTrackingPipeline()`.
   The pipeline's first settings emission calls `startCameraService()`, which is legitimately refused when
   `MainActivity.isVisible == false` (user is in Settings enabling the service), keyguard is up, or
   camera permission is missing. Any exception in the overlay or pipeline stage went to the catch block,
   which set `isConnected=false`, showed "could not start", and tore the pipeline down. `PermissionsManager`
   combines `accessibilityGranted = settingEnabled && isConnected`, so onboarding and
   `RuntimeHealthMonitor` reported "accessibility not running" for a service that *was* bound and able
   to dispatch. In Debug the same path is exercised with a full log trail and, typically, with the
   Activity visible (developer launches from IDE) — so it *appeared* to work.

3. **Tracker init failures returned silently.**
   `HandTrackerImpl.initialize()` / `FaceTrackerImpl.initialize()` return `Unit` and only logged via
   `Timber.e` inside nested `try { try {} catch(_) {false} }` that discarded the exception, and
   `CameraService` reacted with `TrackingState.FAILED reason="hand-tracker-not-ready"` — with no
   WARN+ record of *why* (asset unreadable vs. `createFromOptions` exception, class, message).

Net effect in a signed build: enabling the service from Settings → service binds → DI OK → camera start
deferred → `isConnected` never becomes `true` (or becomes `false` on any overlay hiccup) → UI says
"not connected", cursor never appears, and logcat has nothing to say. That matches the report exactly.
No shrinker rule change fixes this; it is a state-publication and observability defect that only the
release logging configuration exposes.

---

## 2. Files changed

| File | Change |
|---|---|
| `app/src/main/java/com/aircontrol/runtime/StageLog.kt` | **New.** Release-visible stage diagnostics (`tag=AirControlStage`, WARN on success / ERROR on failure, exception class + message, bounded ring for debug screen). |
| `app/src/main/java/com/aircontrol/accessibility/ServiceReadinessPolicy.kt` | **New.** Pure readiness rule: READY when DI + dispatcher attach succeed; overlay/camera are degradations. |
| `app/src/main/java/com/aircontrol/tracking/FrameFreshnessPolicy.kt` | **New (P1).** Sheds stale detected hand frames replayed from the 64-deep transport buffer after a collector stall. |
| `app/src/main/java/com/aircontrol/accessibility/GestureControlAccessibilityService.kt` | `onCreate` stage record; `onServiceConnected` restructured into staged sections (DI → dispatcher → **publish READY** → overlays → pipeline), each with its own try/catch + stage record; "not AirControlApp" branch now ERROR; injection give-up recorded; camera deferral/rejection recorded (rate-limited); `FrameFreshnessPolicy` in the hand-frame collector; `CrashGuard.onFatalLoop` records the dead collector. |
| `app/src/main/java/com/aircontrol/camera/CameraService.kt` | Stage records for SERVICE_CREATE, DI_INIT (+ explicit FAILED state reasons `di-app-class`/`di-failed`), CAMERA_INIT_FOREGROUND, HAND_TRACKER_INIT (session-level), CAMERA_INIT_BIND (with keyguard/interactive state), CAMERA_INIT_RUNNING; the three `runCatching { tracker.initialize() }` sites now report failures. |
| `app/src/main/java/com/aircontrol/tracking/HandTracker.kt`, `FaceTracker.kt` | `validateModelFile` no longer swallows the exception; `lastInitError` captured; HAND/FACE_TRACKER_INIT success/failure records with delegate, model name and exception. |
| `app/src/main/java/com/aircontrol/runtime/PerfTelemetry.kt` | New counter `handFramesShedStale` (+ snapshot field, summary `stale=`, reset). |
| `app/proguard-rules.pro` | Added narrow `-keep @dagger.hilt.EntryPoint interface com.aircontrol.di.** { *; }`. |
| `app/src/test/java/com/aircontrol/runtime/StageLogTest.kt` | **New**, 5 tests. |
| `app/src/test/java/com/aircontrol/accessibility/ServiceReadinessPolicyTest.kt` | **New**, 5 tests. |
| `app/src/test/java/com/aircontrol/tracking/FrameFreshnessPolicyTest.kt` | **New**, 5 tests. |
| `gradlew` | mode `+x` only (needed to build here; CI already does `chmod +x`). |

Not committed / not to be committed: `local.properties`, `app/release.keystore` (local test keystore,
both git-ignored).

---

## 3. Per-fix detail

### Fix 1 — Release-visible stage diagnostics (`StageLog`)
- **Problem:** No startup log survives release; failures in the service/camera/tracker boot path are silent.
- **Root cause:** `Timber.d` stripped by `-assumenosideeffects`; `Timber.i` filtered by `ReleaseTree`; several failure branches logged at `d`/`v`.
- **Fix:** `StageLog.success/failure(stage, component, detail, error)` → `Timber.tag("AirControlStage").w/e`. Emitted at: SERVICE_CREATE, SERVICE_CONNECTED, DI_INIT, DISPATCHER_ATTACH, SERVICE_READY, OVERLAY_INIT, PIPELINE_INIT, CAMERA_INIT_DEFERRED, CAMERA_INIT_FOREGROUND, CAMERA_INIT_BIND, CAMERA_INIT_RUNNING, HAND_TRACKER_INIT, FACE_TRACKER_INIT. Each record carries component, stage, exception class and message. Boot/lifecycle only — no per-frame callers.
- **Why this is the right fix:** It makes the release build diagnosable without changing Debug/Release behaviour (logging is an allowed difference) and without weakening R8 (WARN/ERROR were never stripped).
- **Regression risk:** Low. ~15 extra WARN lines per service start; the previous "no per-gesture trail in release" privacy rule (#115/#116) is preserved — nothing per gesture or per frame is logged.
- **Test:** `StageLogTest` (format, exception capture, FAIL without throwable, ring bound, required stage names). Verified in release DEX: all stage strings present and `StageLog` calls resolve to `Timber$Forest.w/e` (`il2.r/d/f`), not to stripped `d/v`.

### Fix 2 — Decouple service readiness from camera/overlay start
- **Problem:** Service bound and functional, but `isConnected=false` whenever the camera FGS could not start immediately or an overlay step threw; UI and health monitor call that "not running".
- **Root cause:** Single try/catch around all of init; `publishConnectionState(true)` last; camera start is gated by `MainActivity.isVisible` / keyguard / permission, which are *normal* at enable time.
- **Fix:** Staged init. After DI + `attachService` + `updateScreenMetrics` succeed, `isInitializedOk=true` and `isConnected=true` are published (via `ServiceReadinessPolicy`). Overlay and pipeline/camera each get their own try/catch; a failure there is recorded with its stage, reported to `CrashGuard`, and (for the pipeline) retried after 2 s via `launchGuarded` — the service is never torn down, never `stopSelf`/`disableSelf`. Camera deferral is recorded once per instance as `CAMERA_INIT_DEFERRED` with the reason; the existing watchdog/backoff path is unchanged.
- **Why:** The accessibility side (event reception, `dispatchGesture`) has no dependency on the camera; treating a deferred camera as "service not started" was the observable P0 symptom. `CameraService` already publishes its own `TrackingState` (`WAITING_FOR_CAMERA`, `FAILED reason=…`), which `RuntimeHealthMonitor` maps to `camera-waiting`/`tracker-not-ready`, so the degraded state is explicit and visible.
- **Regression risk:** Medium-low. `isConnected` now turns `true` slightly earlier (before overlays). Consumers (`PermissionsManager`, `RuntimeHealthMonitor`, onboarding) already combine it with camera state; no consumer assumed "connected ⇒ cursor overlay exists". Release/Debug behave identically.
- **Test:** `ServiceReadinessPolicyTest` (camera deferred ⇒ READY_DEGRADED publishes connected; overlay failure ⇒ still connected; DI or attach failure ⇒ NOT_READY). Release `onServiceConnected` bytecode inspected: `SERVICE_READY` record and `_isConnected` publish precede `createOverlays()` and `startTrackingPipeline()`.

### Fix 3 — Tracker init failures are explicit
- **Problem:** `HandTracker`/`FaceTracker.initialize()` failed silently (nested catch discarding the exception); `CameraService` then reported `FAILED hand-tracker-not-ready` with no cause.
- **Root cause:** `validateModelFile` = `try { try {…} catch(_) { false } }`; `tryInitializeWithDelegate` logged at `w` but the caller logged the summary at `e` without the exception.
- **Fix:** Exception captured into `lastInitError`, single-level catch, `HAND/FACE_TRACKER_INIT` FAIL record with detail ("… not readable from assets" vs "createFromOptions(CPU) failed") plus class/message; success record with delegate + model. `CameraService` adds a session-level `HAND_TRACKER_INIT FAIL` record and reports failures from the three previously bare `runCatching { tracker.initialize() }` sites.
- **Why:** Smallest change that turns "tracker not ready" into a diagnosable cause; no threshold, delegate or lifecycle change.
- **Regression risk:** Very low (logging + one field).
- **Test:** Covered by `StageLogTest`; tracker classes need MediaPipe natives so they are not unit-testable here (already the case before).

### Fix 4 — Narrow keep rule for our Hilt entry points
- **Problem/Root cause:** None today — verified the interface survives. Hilt's consumer rule is `-keep,allowobfuscation,allowshrinking @dagger.hilt.EntryPoint class *`, which permits R8 to rename/merge the interface; the reflective cast in `EntryPoints.get` tolerates renaming but not merging/removal.
- **Fix:** `-keep @dagger.hilt.EntryPoint interface com.aircontrol.di.** { *; }` — pins only our own entry-point interfaces (one class). No wildcard `-keep class ** { *; }`; R8 and resource shrinking stay enabled.
- **Regression risk:** Negligible (one interface un-renamed; verified `AccessibilityServiceEntryPoint` now appears by name in the release DEX).
- **Test:** `configuration.txt` line 252 shows the rule in effect; `mapping.txt` shows `com.aircontrol.di.AccessibilityServiceEntryPoint -> com.aircontrol.di.AccessibilityServiceEntryPoint`.

### Fix 5 (P1) — Stale hand-frame replay after a collector stall
- **Problem:** `HandTrackerImpl._handFrames` is `MutableSharedFlow(extraBufferCapacity=64, DROP_OLDEST)` (kept deliberately so a stalled collector cannot lose the frame that completes a pinch/swipe). When the service's `"hand frames"` collector resumes after a stall (GC, main-thread hop, thermal), it drains up to 64 frames "instantly" into `GestureDetector.processHandFrame`, which uses frame timestamps *and* wall-clock elapsed time (swipe velocity, arming timers). Result: cursor rewinds/catches up, and swipe velocity is computed from mismatched clocks.
- **Root cause:** No freshness check on the consumer side; the producer's `InFlightGate` bounds *inference* concurrency, not transport age.
- **Fix:** `FrameFreshnessPolicy(maxAgeMs=250)`: a **detected** frame older than 250 ms (against `SystemClock.elapsedRealtime`, the same clock the frame timestamps use) is shed and counted (`PerfTelemetry.recordHandFrameShed`, summary field `stale=`); a **non-detected** ("hand lost") frame is never shed so the engine still disarms cleanly. No allocation per frame; the collector already computed `elapsedRealtime()` so no extra syscall on the hot path.
- **Why:** Keeps the deep buffer and its backpressure semantics intact (not "removing backpressure blindly"); only refuses to treat replayed history as live input. 250 ms is a stall bound, not a jitter bound, so it cannot affect a healthy 15–30 fps pipeline.
- **Regression risk:** Low. Worst case on a badly overloaded device: fewer stale frames reach the engine (which is the intent). If a device is *steadily* > 250 ms behind, the `stale=` counter rises and points at the real problem instead of masking it.
- **Test:** `FrameFreshnessPolicyTest` (fresh accepted, stale shed+counted, hand-lost never shed, 20-frame replay burst keeps only the last 7, reset).

### P1/P2 items reviewed with no change (reasons)
- **GazeJumpPolicy / face-loss reacquire:** source has time-bounded hold + REPRIME and a `GAZE_REACQUIRE_PRIME_FRAMES` warm-up with hidden overlay, i.e. no teleport on reacquire; no defect demonstrated → no change (no blind edits).
- **AdaptiveFpsController:** presence-based downgrade with cancel on hand/face detection; correct. Its `Timber.d` lines are per-transition, not per-frame — fine.
- **Long-press ≠ "right click":** grepped resources/code — the app labels `LONG_PRESS` as "Long Press" and dispatches a 500 ms single-point stroke; there is no "right click" claim anywhere. Nothing to fix.
- **Enum `valueOf` keep rules:** verified unnecessary (constant strings retained) → not added.
- **`CameraService.stopSelf()` on DI failure:** left as is — it is the *camera* FGS, it now publishes an explicit `FAILED reason=di-failed` state and an ERROR stage record, and the accessibility service (the thing the user toggles) is unaffected. Removing it would leave a foreground service running with no trackers.

---

## 4. Release verification checklist (new `app-release.apk`, 55,238,115 B, versionName 1.0.1)

| Check | Result |
|---|---|
| `./gradlew :app:assembleRelease` (minify + shrinkResources ON, `--no-daemon`) | PASS (EXIT=0, `/home/user/release-build2.log`) |
| `./gradlew :app:assembleDebug` | PASS (EXIT=0, `/home/user/debug-build2.log`) |
| Unit tests `:app:testDebugUnitTest` (runtime, accessibility, camera, util, tracking.FrameFreshnessPolicyTest) — 170 tests | PASS, 0 failures |
| `apksigner verify` (v2/v3) | PASS |
| `zipalign -c -P 16 4` | PASS |
| Accessibility `<service>` + `<meta-data>` in merged manifest | PASS |
| `accessibility_service_config.xml` resource id resolvable after shrink | PASS |
| MediaPipe `.task` assets present and byte-identical to debug | PASS |
| 19 native libs present (same set as debug) | PASS |
| `GestureControlAccessibilityService`, `CameraService`, `ActionDispatcher`, `GestureAction`, `AirControlApp`, `Hilt_AirControlApp`, receivers kept | PASS |
| `AccessibilityServiceEntryPoint` kept by name (new rule active — `configuration.txt:252`) | PASS |
| Hilt generated components present | PASS |
| JNI-referenced MediaPipe classes + protobuf option fields kept | PASS |
| `StageLog` present; all stage strings in DEX; calls bind to `Timber.w/e` (not stripped `d/v`) | PASS |
| Release `onServiceConnected` bytecode: READY published **before** `createOverlays`/`startTrackingPipeline` | PASS |
| R8 missing-class / warnings | 0 |
| Debug↔Release manifest diff limited to debug tooling | PASS |
| Installed and exercised on a device/emulator | **NOT DONE** (no device, no KVM) |

---

## 5. Debug vs Release behaviour

| Aspect | Debug | Release | Same? |
|---|---|---|---|
| Service init order, readiness publication, degradation policy | staged (new) | staged (new) | Yes |
| Camera/tracker lifecycle, thresholds, filters | unchanged | unchanged | Yes |
| `StageLog` output | WARN/ERROR via `DebugTree` | WARN/ERROR via `ReleaseTree` | Yes (both visible) |
| `Timber.d/v`, `PerfTelemetry` summaries, gaze smoothing metrics, debug screen instrumentation | present | stripped / off | Differs — logging/telemetry only (allowed) |
| Application id / signing / optimization | `com.aircontrol.debug`, debug key, no R8 | `com.aircontrol`, release key, R8 + resource shrink | Differs — tooling only (allowed) |
| LeakCanary / Compose preview components | present | absent | Differs — tooling only |

---

## 6. Remaining limitations (honest)

1. **No on-device run.** The environment has no Android device and no KVM for an emulator. The fixes were
   verified by source reasoning, JVM tests and bytecode inspection of the signed release APK — not by
   toggling the service on a phone. First device step: `adb logcat -s AirControlStage` while enabling the
   service; the expected trail is `SERVICE_CREATE → SERVICE_CONNECTED → DI_INIT OK → DISPATCHER_ATTACH OK →
   SERVICE_READY → OVERLAY_INIT → PIPELINE_INIT → (CAMERA_INIT_DEFERRED while in Settings) →
   CAMERA_INIT_FOREGROUND → HAND_TRACKER_INIT → CAMERA_INIT_BIND/RUNNING`. Any `FAIL` line names the stage,
   component, exception class and message.
2. If the device shows `DI_INIT FAIL` or `HAND_TRACKER_INIT FAIL` with a real exception, that is a *new*
   finding this build will now expose; it was not reproducible here.
3. `x86_64` native libs are absent in **both** variants (upstream MediaPipe AAR) — unrelated to the
   report but worth knowing for emulator testing: use an arm64 or x86 image.
4. P1 items beyond the stale-frame guard (gaze arbitration, blink/dwell/pinch/swipe arbitration tuning,
   OEM/thermal P2) were reviewed and left unchanged because no defect could be demonstrated from source;
   they need the device session above before any threshold is touched.
5. The local `app/release.keystore` is a throwaway test key; CI signs with the real secret.
