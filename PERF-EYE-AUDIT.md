# Phase 0 — read-only audit: why eye tracking is unusable and why everything is laggy

Repo `skb648/Jskair`, fresh clone at `/home/user/jskair-perf` (nothing reused from earlier working
trees). Baseline `main` = `805c83b3d3838c19296e756bd92fd2d86123b5a7` ("docs: point the Phase 0
audit at the result log"), `git status --short` empty → clean.

Working branch for this round: `perf-eye-tracking-architecture-recovery`. `main` is not modified.

## Baseline record

| item | value |
|---|---|
| modules | `:app` (`com.aircontrol`), `:gesture-engine` (pure Kotlin) |
| toolchain | AGP 9.1.1, Kotlin 2.2.10, Gradle 9.3.1 (wrapper), JDK 17 in CI |
| SDK | compileSdk 37, targetSdk 37, minSdk 26, versionName 1.0.1 |
| gradle.properties | `org.gradle.jvmargs=-Xmx4096m`, parallel + build cache on |
| app build types | debug (`versionNameSuffix -debug`), release (R8 + signing via repo secrets) |
| tests | 583 `@Test` methods (`grep -rho "@Test" --include=*.kt app/src gesture-engine/src | wc -l`), 9 `androidTest` files |
| CI | `.github/workflows/build-apk.yml` → `testDebugUnitTest :gesture-engine:test` + `assembleDebug` + aapt badging check; `android-apk.yml` → debug APK + 2× signed release APK with `apksigner verify --print-certs` |
| last known build state | all CI jobs green at `805c83b` (debug, both signed release, unit tests) |
| eye tracking gating | `currentPreferences.eyeTrackingEnabled`, checked before `faceTracker.processFrame` (`CameraService.kt:661`) → opt-in preserved |

**Measurement environment, stated up front:** the sandbox has 2 vCPU, 1.9 GB RAM, no `adb`, no
system images, no emulator, and Gradle's own `-Xmx4096m` cannot fit — so `:app` cannot be compiled
or tested locally at all, and **no Android device measurement is possible here**. Everything marked
`NOT TESTED` in the performance sections is genuinely unmeasured, not assumed. What *can* be
measured here, and will be, is the pure-Kotlin part of the eye pipeline (feature extraction, head
pose, normalization, raw gaze, jump policy, eligibility): allocation bytes per frame and ns per
frame on the JVM, before and after each change. `:app` correctness/compile is verified by CI.

## The traced runtime path, as it actually is

```
CameraX ImageAnalysis ── executor "aircontrol-analysis" (single thread)      CameraService.kt:246
  └─ processImageFrame(proxy)                                                :644
       ├─ throttle: if (now - lastFrame < adaptiveFpsController.analysisIntervalMs) return   :648
       ├─ imageProxyToMPImage: proxy.toBitmap() → Canvas draw into ONE reusable ARGB bitmap
       │    (rotate matrix + the single mirror decision) → BitmapImageBuilder  :682-742
       ├─ handTracker.processFrame(mpImage, startMs)      → detectAsync (LIVE_STREAM, CPU)
       ├─ if (eyeTrackingEnabled) faceTracker.processFrame(mpImage, startMs) → detectAsync
       ├─ mpImage.close()                                                   :671
       └─ finally imageProxy.close(); PerfTelemetry.recordAnalyzerDuration  :676
HandLandmarker result  → handleResult → HandFrame → SharedFlow(64, DROP_OLDEST)  HandTracker.kt:53
FaceLandmarker result  → handleResult → (extract → pose → normalize → raw gaze →
                        eligibility → model predict → jump policy) → GazePoint
                        → SharedFlow(64, DROP_OLDEST)                        FaceTracker.kt:208-221
GestureControlAccessibilityService
  ├─ "hand frames"/"gesture events" collector → cursorSmoother.filter →
  │    withContext(Dispatchers.Main) { cursorOverlay.updatePosition(...) }   service:1059
  └─ gaze collector → blink/dwell gates → gazeCursorSmoother.filter →
       withContext(Main) { overlay.show(); overlay.updatePosition(direct=true) } :854-861
       + cursorController.updatePosition(x, y)  (StateFlow, latest-wins)     :864
CursorOverlay.updatePosition → CursorGeometry clamp → updateViewLayout()
  (16 ms throttle, coalesced into ONE deferred paint)                        CursorOverlay.kt:295-330
CursorHoverMonitor → latest-wins single-flight accessibility hit test        CursorHoverMonitor.kt
```

## Root causes

### P0-1 — Async inference has no in-flight bound, so latency becomes a growing queue

`processImageFrame` submits the frame to the hand graph and the face graph with `detectAsync`
(`HandTracker.kt:112`, `FaceTracker.kt:283`; both `RunningMode.LIVE_STREAM`, `Delegate.CPU`) and
returns immediately. Nothing anywhere records "a result for the frame I just submitted has not come
back yet". The only regulator is the *time* gate at `CameraService.kt:648`
(`analysisIntervalMs`, from `AdaptiveFpsController`, tiers {5,10,15,24,30}).

Consequence: whenever sustained inference latency exceeds the submission interval — which is exactly
what a 1-2 GB device does — frames pile into the graphs' internal queues. The cursor is then driven
by results for frames 5, 10, 20 old, and the lag is *self-reinforcing*: more queued work → longer
latency → more queue. No threshold change can fix that, because the failure is a missing capacity
condition, not a wrong constant. This is the single best explanation for "noticeably laggy,
especially on low-end devices" and it also manufactures eye-tracking garbage (stale frames arriving
out of the smoothing pipeline's expectations).

### P0-2 — One MPImage and one reusable bitmap are shared by both graphs and destroyed immediately

`imageProxyToMPImage` draws the frame into `reusableTransformBitmap` (a single field,
`CameraService.kt:200-742`), wraps it in an `MPImage`, hands that to *both* `detectAsync` calls,
and then `mpImage.close()` runs in the same `try` block (`:666-671`) — before either graph has
necessarily read the pixels. The next processed frame then draws into the *same* bitmap.

So the input buffer of an in-flight inference is (a) released by the submitting thread and (b)
overwritten by the next frame. `BitmapImageBuilder` does not copy into private storage
synchronously in a way the app can rely on, and nothing in the code claims it does. This is a use
of data after ownership ended (Rule 34 lists it explicitly as forbidden) and the mechanism by which
a device can produce intermittent, unreproducible landmark garbage — corrupt landmarks explain the
observed rejection storm (LOW_EYE_QUALITY / BAD_BINOCULAR_AGREEMENT / jump holds) far better than
any threshold does. P0-1 makes it worse: the deeper the queue, the longer a buffer is needed and the
more certainly it has been overwritten.

### P0-3 — The cursor is driven by a 64-slot queue plus a per-frame main-thread hop

`FaceTracker._gazePoints` and `HandTracker._handFrames` are `MutableSharedFlow(extraBufferCapacity =
64, DROP_OLDEST)` (`FaceTracker.kt:208-221`, `HandTracker.kt:53`), and each gaze frame's consumer
does `withContext(Dispatchers.Main) { overlay.updatePosition(...) }` (`service:854`, hand path
`:1059`). Two effects:

- it is a *queue* where the semantics must be *latest-wins*: for a cursor, frame 20 makes frame 19
  worthless, yet a collector that is 64 frames behind will faithfully walk through all of them;
- the collector suspends on the main dispatcher every frame, so the ML thread's rate is capped by UI
  busyness, and the main thread receives a burst of layout calls instead of one per frame.

The overlay itself is already correct about coalescing (`updateThrottleMs = 16L`, one deferred paint
that never drops the newest target, `CursorOverlay.kt:295-330`), and `CursorController`'s
`MutableStateFlow` is already latest-wins (`CursorController.kt:37-41`). The defect is the transport
*into* those, not their endpoints. `CursorHoverMonitor` is likewise already latest-wins with
generation numbers and single-flight traversal (`CursorHoverMonitor.kt:20-33`), so Rule 17 needs
verification and bounds, not a rewrite.

### P0-4 — The jump policy holds for a number of *frames*, so its freeze length is a function of FPS

`GazeJumpPolicy.MAX_HOLD_FRAMES = 10` and its own comment says "~0.5 s at eye-mode 20 fps"
(`GazeJumpPolicy.kt:150-152`). The pipeline's actual rate is not 20 fps: it is whatever
`AdaptiveFpsController` decided — 10 fps in scan mode, 12→10 under power save, and whatever the
device sustains. At 10 fps the hold is a full second of frozen cursor; if a low-end device drops
effective eye updates to 5 fps, it is two seconds. The hold is also a *freeze*, not a
prediction: nothing tracks the last accepted velocity.

Rule 5 asks for the stale period to be tightly bounded and for short bounded prediction instead of
long holds. The principled form of that: express the bound in **milliseconds derived from the
measured frame interval** (the unit the policy is actually evaluated in), and during the hold
*continue* along the last accepted velocity for at most one interval, then re-prime. No new magic
number is introduced; the previous constant is replaced by a measured quantity with a sanity clamp.

### P0-5 — The eye path allocates ~478 objects per frame to copy landmarks that are already objects

`FaceTracker.buildFaceLandmarkFrame` does `landmarks.map { FaceLandmark(it.x(), it.y(), it.z()) }`
(`FaceTracker.kt:598`) over the full 478-landmark face mesh, into a data class holding a
`List<FaceLandmark>` (`FaceLandmarkFrame.kt:36`). Then `EyeFeatureExtractor` needs ~30 of those
indices, `HeadPoseEstimator` a few more, and `GazeCalibrationFeatureVectorBuilder` is only built
when a model or calibration needs it (that part is already conditional, `FaceTracker.kt:390` ✓).

So the pipeline pays ~15 KB of boxed garbage per frame per the *shape* it finds convenient, not the
shape the maths needs. On a low-end ART heap that is a GC pause source (Rule 11/12) and it is
entirely avoidable with a primitive buffer of the used indices, with numerical equivalence provable
by test. Smaller instances of the same pattern in the same path: `EyeFeatureExtractor.kt:86-90`
(`listOfNotNull` + `map{}` + `average()`), `:222` (`ringPoints.map{}` for the iris radius), and
`CursorController.kt:63-66` (`listOf` of 4 landmarks + `sumOf { it.x.toDouble() }`, i.e. per-frame
boxing on the hand cursor path too).

### P1-1 — Camera rate, inference rate and render rate are one number

`AdaptiveFpsController` (`AdaptiveFpsController.kt`) is a single global analysis-FPS knob whose only
inputs are hand-present/absent (and external thermal/power-save caps). It gates *both* channels with
one interval, has no notion of a face budget vs a hand budget, and its "supported FPS" set is a
hard-coded list of tiers, not a measured device capability. Rule 7/8/18 is therefore unmet by
design, not by omission of a detail. Note the correction to the task's hypothesis: **Face and Hand do
not run as two colliding CPU jobs in the app's own threads — they are submitted serially from one
thread.** The CPU pressure is real but it lives in the two graphs' internal threads plus the missing
capacity bound (P0-1), so "stagger the submissions and bound each channel" is the fix, not
"serialize what is already serialized".

### P1-2 — Diagnostics cannot answer "which stage killed this frame" for the stages below eligibility

`GazeDiagnostics` + `PerfTelemetry` are a good base: per-stage reason enum, ring buffer, rate-limited
snapshot line, `framesProcessed/DroppedThrottle/DroppedNoTracker`, `analyzerDuration`,
`handInference`, `faceInference`, thermal/power-save/memory-trim transitions, p50/p95 of interval.
Missing for the required matrix: image-conversion time *separated from* inference; **result age**
(how stale the frame behind the cursor is — the direct measure of P0-1); in-flight depth per channel
and backpressure drops; end-to-end camera→cursor latency; GC/allocation counters; p90/p99. Also
`FaceTracker.kt:395-405` wraps the whole extract→pose→normalize→feature block in
`runCatching { }.onFailure { Timber.e(...) }`: when that throws, the frame is emitted as
`raw == null` and gets `LOW_EYE_QUALITY` (`:467-469`), which mis-attributes a pipeline failure as a
quality problem. Rule 1's reason list also has no `CALIBRATION_UNAVAILABLE`, `MODEL_LOW_CONFIDENCE`,
`JUMP_REJECTED` (present as `PREDICTION_SUPPRESSED` ✓), `LEFT_EYE_INVALID`/`RIGHT_EYE_INVALID`
(only implied by per-eye quality floats), or `CURSOR_NOT_READY`.

### P1-3 — Head pose loses its fast path to a build-version comment

`facialTransformationMatrix = null` with the comment "the accessor shape varies across tasks-vision
builds and did not resolve here" (`FaceTracker.kt:599-604`), so `HeadPoseEstimator` always runs its
landmark fallback at confidence 0.75. That is both a per-frame cost (extra trig over more landmarks)
and a quality cost (a lower-confidence pose feeds eligibility's head-angle term). Whether the matrix
is available is checkable against the pinned `mediapipe-vision = 0.10.20` — worth restoring rather
than leaving a permanent fallback.

### P1-4 — Personalized model and the raw path are two different target estimations

`FaceTracker.handleResult` computes raw `h/v` in CAMERA_RAW, and only if a personalized model *and* a
feature vector exist does it apply the jump policy and switch the space to SCREEN_NORMALIZED
(`FaceTracker.kt:429-455`). The affine/gain calibration is applied by a *different* layer
(`mapGazeToDisplay` in the accessibility service, `:801-806`). Two transforms, two owners, and the
consumer must branch on `gaze.personalized`. That is why "mode B: raw + calibration → cursor" cannot
be exercised without the service, i.e. why the isolation modes the task asks for do not exist and why
diagnosing a model-path failure and a calibration-path failure look the same from outside.

## What is NOT the cause (so nothing gets "fixed" here)

- Cursor overlay layout calls are already coalesced to 16 ms with a single deferred paint that never
  drops the newest target (`CursorOverlay.kt:295-330`).
- Accessibility hover traversal is already latest-wins/single-flight/generation-guarded and runs off
  the main thread (`CursorHoverMonitor.kt`, `HoverResolvePolicy.kt`).
- `CursorController` already keeps one authoritative logical cursor `StateFlow` (`:37-41`) and the
  gaze path updates it (`service:864`), so there is not a second cursor state machine — the overlay
  keeps a rendering position, which is a different thing. (Rule 33: preserve this, do not add a
  third.)
- Per-frame logging is already debug-gated (`diagnosticsEnabled = BuildConfig.DEBUG`,
  `FaceTracker.kt:200`; `if (BuildConfig.DEBUG)` in the service's metrics block).
- `HandTracker`/`FaceTracker` reuse loaded models across session stop/start and guard submission
  against `close()` with one lock (`HandTracker.kt:88-118`, `:236` region) — the native lifetime
  hazard is P0-2's *buffer*, not the landmarker object.
- Release signing, R8 config and the eye opt-in gating work and are not part of this problem.

## Change groups, in the order they will be implemented

Each group: audit → change → test → verify (harness + CI), one group at a time.

| # | change | root cause | verified by |
|---|---|---|---|
| 1 | One in-flight inference per channel + per-frame buffer lease with refcount and TTL; drop-and-count when the pool is empty | P0-1, P0-2 | new `RealtimeBackpressureTest` (pure Kotlin), CI compile of `:app`, on-device `drop(backpressure=…)` / `refused(…)` counters |
| 2 | **LANDED** — latest-wins cursor transport: `LatestWinsTransport` + Choreographer application, gaze flow 64 → 1, overlay skips no-op layout updates | P0-3 | new `LatestWinsTransportTest` (8 pure-JVM tests), CI compile of `:app`; on-device frame timing NOT TESTED |
| 3 | **LANDED** — jump hold bounded in pipeline time (`MAX_HOLD_MS = 120`) with the frame count kept as a no-clock safety ceiling, plus velocity continuation bounded by time and by the disputed sample | P0-4 | new `GazeJumpPolicyTimingTest` (9), existing 22 policy tests unchanged | Jump policy: measured-interval hold bound (ms, not frames) + one-interval velocity continuation | P0-4 | `GazeJumpPolicyTest` (harness) |
| 4 | **LANDED** — primitive slot buffer for the 25 landmarks the pipeline reads; alloc-free reads in the extractors; planar bulk copy | P0-5 | `FaceLandmarkFrameEquivalenceTest` (9 numerical-equivalence tests) + all pre-existing extractor/pose tests unchanged; same-run benchmark rows | Primitive landmark buffer for the eye path + allocation-free palm anchor; numerical-equivalence tests | P0-5 | equivalence tests + host alloc/ns measurement before/after |
| 5 | PerfTelemetry: conversion vs inference split, result age, in-flight depth, backpressure drops, GC/allocation counters, p90/p99; new per-stage rejection reasons incl. pipeline-failure reason | P1-2 | counters snapshot test, debug screen |
| 6 | One workload governor (hand/face budgets from sustained latency + thermal + power-save + memory), replacing the three competing knobs; hysteresis against oscillation | P1-1 | governor unit tests (pure), CI |
| 7 | Debug isolation modes A–G behind one `GazePipelineMode`, so raw / +calibration / model off / model on / smoothing bypass / jump bypass / production are each selectable and each emits its stage-by-stage verdict | P1-4 | mode tests (harness) + manual device checklist |
| 8 | Restore the facial transform matrix fast path where the pinned MediaPipe version exposes it | P1-3 | pose test + measured pose cost |

Docs (`Rule 35`) are updated with the final architecture and the honest measurement report, which
will list device classes as NOT TESTED because this environment has no device.

## Measured baseline (host JVM, pure-Kotlin stages)

`tools/eye-bench/run.sh` compiles the real production gaze sources with the project's Kotlin
2.2.10 and measures them with `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` plus
`System.nanoTime`, median of 7 passes after 2 000 warm-up frames, Serial GC, 64-frame fixture cycle.
At `805c83b`:

| stage | ns/frame | bytes/frame |
|---|---|---|
| materialising the 478-landmark frame (`buildFaceLandmarkFrame`'s shape) | 7 657 | **18 232** |
| `EyeFeatureExtractor.extract` | 58 | 0 |
| `HeadPoseEstimator.estimate` | 267 | 0 |
| `HeadPoseNormalizer.normalize` | 56 | 0 |
| `GazeCalibrationFeatureVectorBuilder.from` | 97 | 0 |
| `RawIrisGazeExtractor.from` | 56 | 0 |
| `GazeEligibilityPolicy.evaluate` | 627 | 24 |
| `GazeJumpPolicy.evaluate` | 251 | 32 |
| whole eye stage, extract → cursor target | 10 386 | **5 968** |

Read it as: **the arithmetic is not the problem.** Every real computation is tens to hundreds of
nanoseconds, and the standalone stages allocate nothing measurable (JIT escape analysis removes the
small records). What costs is *moving the landmarks into the shape the code prefers*: one line,
`landmarks.map { FaceLandmark(it.x(), it.y(), it.z()) }`, is ~7.7 µs and 18 KB per frame — about
10× the entire rest of the pipeline's allocation (5.97 KB) and the single largest CPU item in the
Android-free path. At a 20 fps eye rate that is ~364 KB/s of garbage from one copy, on devices where
a young-generation GC pause is what the user feels as "the cursor stutters every few seconds".

Two limits of this table, stated rather than glossed: the composite row's allocation is larger than
the sum of the rows above it because the standalone stages' small allocations are eliminated when the
result never escapes, while in the composite they do escape — so 5 968 B/frame is the honest number
for the chained path and the individual 0 B rows are not "free code". And the fixture frame yields
`pose=INVALID` (the estimator's degenerate-geometry guard rejects synthetic layouts), so head-pose
work is under-counted here: production cost is ≥ these numbers, never less. Device behaviour (ART
GC, MediaPipe's own native allocations, thermal limits) is NOT TESTED and cannot be measured here.

---

# Landing log

Each group lands with: the root cause it closes, the old and new behaviour, the tests that pin it,
and an explicit statement of what was measured here versus what needs a device. Nothing in this
section may claim a performance improvement it cannot show a number for.

## Group 1 — in-flight gate + leased frame buffers (closes P0-1, P0-2)

**Files.** New `tracking/InFlightGate.kt`, `tracking/SlotPool.kt`.
`tracking/HandTracker.kt` (interface + `HandTrackerImpl`), `tracking/FaceTracker.kt` (interface +
`FaceTrackerImpl`), `camera/CameraService.kt` (`processImageFrame`, new `convertIntoLeasedBitmap`,
frame watchdog), `runtime/PerfTelemetry.kt`,
`app/src/androidTest/java/com/aircontrol/ServiceLifecycleTest.kt` (fake updated to the new contract).

**Old behaviour.** `processImageFrame` called `landmarker.detectAsync(...)` unconditionally, at the
camera's rate, and MediaPipe's graph kept every submitted frame — so when inference was slower than
the camera the frames accumulated into an ever-growing queue, and the "latency" the user saw was
the queue draining, not the model being slow. The pixels were a single `reusableTransformBitmap`
field, drawn into again on the next frame while the graph still read them; the `MPImage` wrapper was
`close()`d in a `finally` the moment the *submission* returned, and a second `reusable` bitmap was
re-allocated whenever the stream size changed. On a 1–2 GB device that combination is the whole
complaint: corrupt/stale input → dropped detections → the camera thread eventually blocked behind
allocation and GC.

**New behaviour.**
1. **One outstanding submission per channel.** `InFlightGate.tryReserve(nowMs)` is the first thing
   `processFrame` does; a saturated channel refuses the frame (returns `false`) instead of queueing
   it. Refusal is the overload response — reduce work, never grow a queue (Rule 9).
2. **The reservation self-heals.** A submission whose result never arrives (graph wedge, model
   teardown race) used to be permanent. The gate reclaims any reservation older than
   `DEFAULT_TIMEOUT_MS` (1000 ms ≈ five intervals of the *slowest* tier, so a legitimately slow
   frame is never stolen), and the reclaimed frame's buffer is handed back rather than leaked. The
   gate takes its clock as a parameter, which is what makes the TTL testable without sleeping.
3. **Buffers are leased by consumption, not by callback exit.** `CameraService` acquires a `Bitmap`
   from `SlotPool(FRAME_BUFFER_SLOTS = 3)` and gives the trackers an `onConsumed` hook; the reference
   count equals the number of channels *asked* (hand always, face when eye tracking is on), the hook
   is invoked exactly once per channel — on refusal or on delivery — and the final decrement closes
   the `MPImage` and returns the bitmap. The tracker's `finally`/catch paths make "exactly once"
   hold even when `detectAsync` throws. `close()` on both trackers clears the gate and fires a
   stranded hook, so pausing the camera cannot park a buffer forever.
4. **Exhaustion drops and counts.** When no buffer is free the frame is dropped and
   `PerfTelemetry.recordFrameDroppedBackpressure()` increments; the camera thread never waits.
5. **Pool sizing moved out of the frame path.** The target size is derived from the proxy before the
   frame is drawn, and a size/rotation change calls `drain()` (a few times per session) instead of
   re-allocating a bitmap inside the hot path.
6. **New telemetry, sampled at watchdog cadence, never per frame**: `conv(avg,max)`,
   `drop(backpressure=N,conversion=N)`, `inFlight(hand,face)`, `refused(hand,face,expired)` in the debug summary
   line, plus `Snapshot` fields for the Debug screen. Gate state is read once per 5 s tick because
   saturation is only meaningful as a rate.

**Tests.** `RealtimeBackpressureTest` — 13 tests: refusal while outstanding, release reopens, TTL
reclaim plus stats, no double-fire; pool capacity bound, reuse (LIFO, zero allocations in steady
state), refusal of foreign releases, `discard`, `drain`, create-failure handling, and a test of the
*composition* the service uses (two channels, one hook, slot returned exactly once — including both
channels refusing and the eye-off single-channel case). `PerfTelemetryTest` — 3 new tests (conversion
avg/max, backpressure counted separately from throttle, gate stats surfaced without per-frame
logging) and `reset` now covers the new fields.
Repo-wide `@Test` count: **583 → 599**. No existing assertion was weakened or deleted; the only
pre-existing test file touched was the androidTest fake, which had to follow the interface.

**Measured here.** `/home/user/tools/kj.sh` — 29 test classes, **OK (319 tests)**. The host eye-path
benchmark (`tools/eye-bench`) is unchanged by this group: it measures the arithmetic between
`EyeFeatureExtractor` and the jump policy, which this change does not touch — so the BEFORE table
still stands and the AFTER table will be taken with the same script after Group 4.

**NOT TESTED (and how to test it).** The win is in queue depth and allocation churn under real
inference, which needs a device: on a 1–3 GB phone the expected signatures are `refused(hand=…)`
growing instead of `interval(p95…)` stretching, `drop(backpressure=…)` bounded by a small number
rather than by a fraction of frames, and no GC pause per second in Perfetto. Those numbers do not
exist yet and no improvement is claimed for them. `:app` cannot be compiled in this sandbox (1.9 GB
RAM vs `-Xmx4096m`), so the compile/unit-test oracle for `HandTracker`/`FaceTracker`/`CameraService`
is GitHub Actions on the PR — recorded as pending, not as passing.

## Group 2 — latest-wins cursor transport (closes P0-3)

**Files.** New `accessibility/LatestWinsTransport.kt`, `accessibility/GestureControlAccessibilityService.kt`,
`accessibility/CursorOverlay.kt`, `tracking/FaceTracker.kt`, new test
`app/src/test/java/com/aircontrol/accessibility/LatestWinsTransportTest.kt`; `tools/kj.sh` now also
covers `runtime/` and an explicit allowlist of pure-JVM `accessibility/` tests (Robolectric specs such
as `ActionDispatcherTest` stay Gradle-only, and the harness now *fails* when a filter discovers
nothing, which is what caught a bad glob in that very list).

**Old behaviour.** Two queues in series fed one pointer: `_gazePoints` was a
`MutableSharedFlow(extraBufferCapacity = 64, DROP_OLDEST)` — 3.2 s of eye samples at 20 fps — and the
collector then did `withContext(Dispatchers.Main) { cursorOverlay.updatePosition(...) }` per sample.
A collector that is even slightly behind therefore *replays the recent past in order*, while each
replayed sample costs a main-thread round trip whose duration the inference coroutine waits on. On a
1–2 GB device both halves of that show up as the same symptom the brief describes: cursor detached
from the eyes, then a sudden jump.

**New behaviour.**
1. The gaze flow holds **one** slot: `extraBufferCapacity = 1` with `DROP_OLDEST`. Overflow dropping
   becomes a no-op instead of a policy; calibration's `gazeObservations` keeps its 64-slot buffer
   because a window of samples is what that consumer is *for*.
2. The cursor move is **published, not pushed**: `gazeTransport.publish(smoothX to smoothY)` stores
   the newest target in one atomic slot and requests at most one `Choreographer` frame callback;
   `applyLatestGazeTarget()` runs on the UI thread, drains the slot, and does the single
   `updateViewLayout`. The producer never waits for the UI again (Rule 14).
3. `LatestWinsTransport` pins the properties the old shape violated: one pending target, newest
   wins, never a duplicate or an older value after a newer one (tested with 5 000 racing publishes),
   reset drops a stale target but leaves the transport usable, and a failed `schedule` rolls the
   flag back so a transient main-looper failure cannot permanently stop painting.
4. `CursorOverlay.applyLayout` now skips the `WindowManager` call when the rounded window position
   is unchanged (Rule 16), with the guard reset when the view is (re)added — so a still gaze costs
   zero binder traffic, and the deferred-throttle path is untouched.
5. Cancellation is explicit: `cancelPendingGazeMove()` runs on the tracking-lost transition, when
   eye/cursor preferences turn the path off, and from `stopTrackingPipeline()` (which `onDestroy`
   already calls) — a frame callback can never resurrect the cursor the frame after it was hidden.

**Behaviour change to be aware of.** A blink tap now reads the newest *smoothed* target, which can
lead the last painted position by one frame. That is what a native pointer does; the alternative —
tapping where the pixels happened to be — was the older, worse contract. `gazeCursorX/Y` are still
updated where they were, so dwell/hover/`CursorController` see the same values as before.

**Tests.** `LatestWinsTransportTest` — 8 tests (single-schedule, coalescing to the newest, no-op
frame, reset-before-paint, re-armability, schedule failure, the `published = applied + coalesced +
pending` identity, and the concurrency/monotonicity stress). Repo-wide `@Test` 599 → **607**; harness
**OK (327 tests)**; parse-level clean for the four Android-side files touched.

**Measured here / NOT TESTED.** This group changes *how many* UI operations a second of eye tracking
produces, which the host benchmark cannot see — it has no UI, no WindowManager, no vsync. Expected
device signature (to be measured, not claimed): `WindowManager.updateViewLayout` calls per second
bounded by the frame rate rather than by the sample rate, a flat `interval(p95…)` under load, no
"walk-through-the-past" in a Perfetto trace, and `coalesced > 0` in the transport counters once
Group 5 exposes them. Every device row remains NOT TESTED.

## Group 3 — the jump policy holds in milliseconds and glides instead of freezing (closes P0-4)

**Files.** `tracking/GazeJumpPolicy.kt`, `tracking/FaceTracker.kt` (passes the sample timestamp), new
`app/src/test/java/com/aircontrol/tracking/GazeJumpPolicyTimingTest.kt`.

**Old behaviour, quantified.** A hold ended at `heldFrames >= 10`. Frozen-cursor time per disputed
sample, as a function of the eye-mode frame rate the device actually achieved:

| sustained eye rate | before (10 frames) | after (`MAX_HOLD_MS`) |
|---|---|---|
| 30 fps | 333 ms | 120 ms |
| 20 fps | 500 ms | 120 ms |
| 10 fps | 1 000 ms | 120 ms |
| 5 fps | 2 000 ms | 200 ms (one frame) |

The bottom of that table is the whole argument that this was an architectural defect and not a
mis-tuned constant: the frame rate drops precisely when the device is loaded, so the old bound made
the freeze *longest* exactly when the user was already suffering, and 2 s of a dead pointer is
indistinguishable from "eye tracking is broken". It also held the position *rigidly*, so a legitimate
saccade in progress stopped dead mid-flight.

**New behaviour.**
1. `evaluate(..., timestampMs: Long = NO_CLOCK)` and `MAX_HOLD_MS = 120L`: a hold ends by elapsed
   pipeline time, measured from the *sample's own* timestamp (not `SystemClock`) so the policy stays
   deterministic and testable. 120 ms is derived, not picked for feel: a saccade is 20–80 ms and the
   cursor's One-Euro filter needs ~2 samples to converge on the new target, so past ~2 intervals the
   hold stabilises nothing and only withholds an already-computed position.
2. `MAX_HOLD_FRAMES` **stays as a safety ceiling**, not a lie: a caller with no clock, or a burst of
   samples sharing one timestamp (the tracker clamps MediaPipe timestamps to be monotonic, so this
   happens), must still not freeze the pointer forever. Tested explicitly.
3. Latest-valid + short bounded interpolation (Rule 5): while holding, the position advances along
   the velocity measured from the last two *accepted* samples, capped twice — by `CONTINUATION_MAX_MS`
   (60 ms of travel) and by the disputed position itself, so the glide can approach the new evidence
   but never pass it, and a still gaze (below `MIN_CONTINUATION_TRAVEL`) is held exactly, never
   invented into motion. `Decision.continued` reports it; `continuedFrameCount()` counts it.
4. Acceptance logic is untouched: `jump <= limit || irisMotion explains the jump` still accepts
   immediately, non-finite predictions still pass through, and `actionConfidenceFactor` still
   1 / 0.7 / 0.3 — no threshold was softened, and the eye-motion escape that already worked is
   regression-tested to prove it.

**Tests.** 9 new: hold expiry at 20 fps vs 5 fps from timestamps, frame ceiling with no clock,
same-timestamp burst, glide direction/magnitude (`0.31f` exactly one interval of measured velocity),
both continuation caps, still gaze not moved, no-clock callers unaffected, explained jump still
accepted, reset re-primes. The 22 existing eligibility/temporal tests pass **unchanged** — including
`holdingIsBoundedSoTheCursorCannotFreezeForever`, which asserts the frame bound and is why the ceiling
was kept rather than removed. Harness **OK (336 tests)**, repo `@Test` 599 → **616**.

**Measured with the same benchmark** (`tools/eye-bench`, three runs after): the isolated jump-policy
stage costs 528 / 538 / 652 ns/frame (before: 251), i.e. **≈ +0.3 µs per frame** with allocation
unchanged at 32 B/frame, and the whole eye stage is inside run-to-run noise at 10.2–10.7 µs/frame
(before 10.4 µs) with allocation identical at 5 968 B/frame. Cost: ~0.6% of one 20 fps frame budget.
Benefit: up to 1.9 s less frozen cursor per disputed event. Device-side perception of the glide
(no overshoot, no oscillation, no hundreds-of-ms freeze — Rule 15) is NOT TESTED here.

## Group 4 — the landmark frame stopped being 478 objects (closes P0-5)

**Files.** `tracking/FaceLandmarkFrame.kt` (rewritten storage), `tracking/EyeFeatureExtractor.kt`,
`tracking/HeadPoseEstimator.kt`, `tracking/FaceTracker.kt` (`buildFaceLandmarkFrame`), new
`app/src/test/java/com/aircontrol/tracking/FaceLandmarkFrameEquivalenceTest.kt`, `tools/eye-bench`.

**Old behaviour.** `buildFaceLandmarkFrame` ran `landmarks.map { FaceLandmark(it.x(), it.y(), it.z()) }`
— 478 objects, three of them actually used by head pose — and then `EyeFeatureExtractor` rebuilt a
`Map<Int, FaceLandmark?>` per eye (11 boxed entries, 22 allocated lookups) while `HeadPoseEstimator`
allocated one `FaceLandmark` per `landmark3()` call, all purely to read floats that were already in
memory one frame earlier. Measured in the BEFORE table at 7.7 us and 18 232 B per frame for the copy
alone — about three times the allocation of the entire rest of the eye path.

**New behaviour.**
1. The frame stores **three parallel `FloatArray`s** and nothing else. In slot mode they hold only
   `FaceLandmarkSlots.USED_INDICES` — the union of both eyes' `requiredIndices`, both `earPoints`
   sets and the three pose anchors, i.e. 25 ids (300 floats-worth of payload, 480 B with headers) —
   resolved once at class load, so "what is captured" has one definition instead of being implied by
   which loops index what.
2. `FaceLandmarkFrame.fromReader(reader)` reads 25 landmarks from the source instead of copying 478;
   `FaceTracker` supplies a 5-method adapter over MediaPipe's list. A source shorter than the
   canonical mesh yields no frame (the caller's `MIN_LANDMARK_COUNT` gate is now redundant, kept as
   documentation of intent).
3. Consumers read **without allocating**: `xOf/yOf/zOf` in the extractor (with the same up-front
   "present and finite" validity check as before) and in `HeadPoseEstimator.landmark3`; the iris ring
   is walked in place, accumulating sum/min/max in a `Double` exactly as `average()`/`maxOrNull()`
   did, and the per-eye map is gone.
4. A missing or un-captured index reads as **`NaN`**, never `0f`: every consumer already rejects
   non-finite coordinates, so a slot-mode frame cannot silently look like a landmark pinned to the
   top-left of the image (the failure mode an all-zeros default would have introduced).
5. `landmarks: List<FaceLandmark>` survives as a **view** over the arrays (`size` = 478, elements
   materialised on demand, un-captured ids NaN), so the legacy constructor, `copy(landmarks = …)`
   perturbation tests and any debug dump keep working unchanged — the frame just no longer *retains*
   the object graph.
6. `copyUsedCoordinates(FloatArray)` gives callers the planar bulk copy (all x, then all y, then all
   z) that a `System.arraycopy` can satisfy in slot mode, for the calibration vector and debug dumps.

**Measured, same run, same fixture source** (`tools/eye-bench`, median of 7 reps, three invocations;
allocation figures were identical every run, ns figures move ±35% on this 2-CPU host):

| stage | before | after | change |
|---|---|---|---|
| frame materialisation | 7 585–8 104 ns, **13 424 B** | 1 290–1 745 ns, **480 B** | **28× less garbage, ~5× faster** |
| `EyeFeatureExtractor.extract` | 58 ns, 0 B (escape-analysed) | 226–597 ns, 0 B | see note |
| whole eye stage, extract → cursor target | 10 386 ns, **5 968 B** | 5 262–9 770 ns, **3 248 B** | **−46% allocation** |

Summed per frame at the eye-mode rate: **≈19.4 KB → ≈3.7 KB of garbage (5.2×), i.e. ~388 KB/s →
~75 KB/s at 20 fps**. The legacy row in the AFTER table (13 424 B) is lower than the BEFORE table's
18 232 B because it now maps the *real* fixture list rather than a constant list, so within-run
comparisons are the ones to trust — the 480 B slot row is measured against 13 424 B in the same
process on the same input. The extract row's ns figure is not comparable across runs: the standalone
stage's small allocations are removed by escape analysis when nothing consumes them, which is why the
chained composite figure (3 248 B, −46%) is the honest measure of what this group changed.

**Tests.** `FaceLandmarkFrameEquivalenceTest` (9): slot and list modes read back identically for every
used index; `copyUsedCoordinates` matches per-landmark reads in both modes and both modes produce
byte-identical buffers; **`EyeFeatureExtractor.extract` results are equal as objects** through either
mode (the numerical-equivalence requirement of Rule 12), including a mirrored frame; head-pose yaw /
pitch / roll / confidence / source / validity identical; the 23-dimension calibration feature vector
identical element-for-element; a short source yields no frame; out-of-range reads are NaN and invalid;
and mirroring moves each eye's centre (sum = 1.0) without swapping anatomical identity (Rule 3).
Every pre-existing extractor / pose / raw-gaze / calibration test passes **unchanged** — including the
ones that construct 478-landmark fixtures and the one that injects NaN through `copy(landmarks = …)`
— which is the actual evidence that the representation change is behaviour-preserving. Harness
**OK (345 tests)**; repo `@Test` 607 → **616** (the 9 timing tests of Group 3 plus these, counted at
HEAD).

**NOT TESTED.** ART does not have this JVM's escape analysis, so the device-side saving is expected to
be *larger* than the host figure, not smaller — but that is inference, and the numbers on a real
1–3 GB device (young-gen GC count, pause durations, `Perfetto` allocation flamegraph) are NOT TESTED.
The `:app` compile of `FaceTracker`/`CameraService` also remains a CI-only check.

## CI verdict — `:app` compile, unit tests and both APKs (Groups 1–4)

The Android-side work that this sandbox cannot compile was verified in GitHub Actions on `main`.

| what | result |
|---|---|
| `Build Android APK` run `34353176645` @ `7051169` | **success** — `testDebugUnitTest` + `:gesture-engine:test`, `assembleDebug`, `aapt` badging check (`targetSdkVersion:'37'`), artifact `AirControl-debug-apk` |
| `Android APK CI` run `34353176617` @ `7051169` | **success** ×3 jobs — debug APK, and two `assembleRelease` jobs with `apksigner verify --print-certs` on the signed release APK |
| release identity | V3.0 signer `CN=AirControl Release, OU=Mobile, O=AirControl, L=Udaipur, ST=Rajasthan, C=IN`, certificate SHA-256 `8bae93eb…0c7985c2` — unchanged from the pre-work baseline, so the fix ships under the same key |
| artifacts | `app-debug.apk` 76 777 400 B sha256 `c86ef9b53a5aa2d2…8bb48090`; `app-release.apk` 55 165 199 B sha256 `d364892d85252804…bf1f19e1` |
| unit tests executed by CI | `@Test` count in the executed source sets went **538 → 580** (358 in `app/src/test`, 222 in `gesture-engine/src/test`); all green |
| instrumented tests | 45 `@Test` in `app/src/androidTest` are **compiled but never executed** — no emulator exists in CI or here. Their compilation is now enforced by a `:app:compileDebugAndroidTestKotlin` step, which the first green cycle did not have |

**What CI caught that nothing else could.** The first two pushes failed with three defects in
Android-only sources, all fixed in `7051169`: `FaceTrackerImpl` never received its `inFlight` /
`pendingConsumed` fields (the anchor pattern differed from `HandTracker`'s and the edit checked
nothing, so every gate reference was unresolved), the lease-releasing cleanup landed in `initialize()`
instead of `close()` in *both* trackers (a frame in flight at teardown would have kept its buffer
leased — the exact stranding the change exists to prevent), and `CameraService.onDestroy` still
recycled the removed `reusableTransformBitmap` field. A stray `lastFrameAspectRatio` block, copied
from the hand tracker into the face tracker where no such field exists, was the fourth. Lesson
recorded: a two-space formatting difference silently skipped a patch with no assertion on it; the
harness cannot see these files at all, so "the pure-Kotlin oracle is green" never meant "the app
compiles", and this doc says so explicitly now.

**Still NOT TESTED after a green CI:** everything that needs a phone. No device, emulator or `adb`
exists in this environment, so CPU/RSS/GC-pause/allocation-rate/thermal/`Perfetto` figures, the
1/2/3/4 GB matrix, sustained-load backpressure behaviour and the perceived cursor quality are
unmeasured — the counters added in Groups 1–2 exist to produce exactly those numbers on hardware.
