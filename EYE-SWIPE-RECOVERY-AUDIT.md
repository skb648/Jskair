# Phase 0 Forensic Audit — Eye Tracking + Swipe Intent

Baseline: fresh clone of `skb648/Jskair`, branch `main`, HEAD `7b234c51b3a2469f7cf0fc56969a6f7dfcc0427c`,
working tree clean. Nothing below was inferred from commit messages, comments or test names; every
claim cites the code path that produces it.

## Baseline record

| item | value |
|---|---|
| repository | `skb648/Jskair` (public), fresh clone in `/home/user/jskair-recovery` |
| branch / HEAD | `main` / `7b234c51b3a2469f7cf0fc56969a6f7dfcc0427c`, clean |
| latest commits | `7b234c5` docs report · `73313d7` ci signed release · `6972b4f` dwell test · `a6192c5` `363bb38` compile fixes · `15ae867` 10-issue harden |
| modules | `:app` (Android), `:gesture-engine` (pure JVM Kotlin) |
| AGP / Kotlin / Gradle | 9.1.1 / 2.2.10 / wrapper 9.3.1 (`gradle/libs.versions.toml`, `gradle-wrapper.properties`) |
| compile / target / min SDK | 37 / 37 / 26 (`app/build.gradle.kts`) |
| toolchain | JDK 17 (`compileOptions`), Compose BOM 2025.05.00, MediaPipe tasks-vision 0.10.20 |
| @Test annotations in repo | 482 (`app/src/test` + `gesture-engine/src/test`) |
| CI at baseline | both workflows **green** on `7b234c5` (`#7` Android APK CI, `#228` Build Android APK) |
| local test run at baseline | **235 tests, OK** via `tools/kj.sh` (real sources; `:app` Gradle build is not runnable here — 2 GB RAM, see §Local verification) |

CI being green matters: "eye tracking is broken" is **not** a build/compile problem, it is a
signal-processing and control-flow problem in shipped code.

---

## A. Eye tracking — root cause, by category

### A1 · CATEGORY B (feature extraction) — horizontal gaze is arithmetically cancelled

`FaceTracker.computeGaze` (`tracking/FaceTracker.kt:454-509`) is the *only* source of the raw gaze
that reaches the cursor whenever no personalized model is active, and the only source of
`GazeObservation.rawX/rawY` used by the affine calibration fallback:

```
leftH  = gazeRatio(lm(468).x, lm(133).x, lm(33).x)   // span = outer - inner
rightH = gazeRatio(lm(473).x, lm(362).x, lm(263).x)  // span = outer - inner
h = ((leftH + rightH) / 2f).coerceIn(0f, 1f)         // :481
```

`gazeRatio` yields *how far the iris sits from the nasal corner toward the temporal corner* — it is a
per-eye, temporal-positive quantity, not a screen direction. For one eye the temporal corner is on the
left, for the other it is on the right, so the two ratios have **opposite signs with respect to any
viewer axis**. When the eyes rotate one way, `leftH` rises while `rightH` falls by the same amount and
their mean stays at ~0.5.

Consequence: horizontal gaze produces a near-zero signal. The cursor responds to vertical eye motion
(`leftV`/`rightV` both use top→bottom ordering, so they add) and does **not** respond to horizontal eye
motion. That matches "eye tracking is effectively broken" exactly: usable up/down, dead left/right.

Note the anatomical naming is also swapped relative to the project's canonical definitions
(`CanonicalEyes.LEFT` = 473, `RIGHT` = 468 in `tracking/FaceLandmarkFrame.kt:97-120`, whereas
`computeGaze` calls 468 "left"). The swap is harmless to a symmetric mean, but it is the reason nobody
noticed: the two eyes are "both handled", so nothing crashed and nothing looked obviously wrong.

This has been in the code since the feature was introduced (`git log -S'leftH + rightH'` → `9f4c54c`)
and was **never covered by a test** — `EyeFeatureExtractorTest` tests the extractor, not this
duplicate legacy math. Category **K** applies too: it survives because three other layers
(`gazeInvertX`, the gain slider, the affine calibration) each look like the thing that is supposed to
fix direction.

### A2 · CATEGORY B/K — head pose hard-requires both eyes

`HeadPoseEstimator.faceGeometry` (`:123-149`) returns `null` unless **both** `features.left` and
`features.right` are non-null (`eyeCenter3` returns null when the matching feature is null).
`GazeCalibrationFeatureVectorBuilder.from` (`:43-49`) likewise returns null unless both eyes exist.
So any single-eye degradation (hair, glasses frame, partial occlusion, one eye out of the crop) makes
the pose invalid → the feature vector null → the personalized path cannot predict at all → the frame
falls back to A1's broken legacy path. One occluded eye converts a working system into a horizontally
dead one, instead of a monocular-but-usable one.

### A3 · CATEGORY F (confidence) — one scalar, two different meanings, one gate

`GazePoint.isDetected` is `confidence >= 0.45` (`:39-42`) and it is the *only* gate in the collector
(`accessibility/GestureControlAccessibilityService.kt:704`). But `confidence` is computed with two
incompatible definitions:

* legacy path: `geometryConfidence * headStability` where `geometryConfidence = 0.25 + 0.5*sym +
  0.25*sep` (`:494`) — typically ≈ 0.75, i.e. comfortably above the floor;
* personalized path: `advancedQuality = min(left.quality, right.quality, pose.confidence)` (`:305-309`)
  where `pose.confidence = symmetryConf * 0.75` (`HeadPoseEstimator:104,233`) — so a perfectly good
  face starts near `0.75*0.75 ≈ 0.56` and any dip takes it below the same 0.45 floor.

Installing a calibrated model therefore makes detection *more* likely to be rejected than having no
model. That is the definition of "valid gaze rejected by an overly strict confidence condition".

Worse, `headStability` (`:503-506`) multiplies that single gate by a head-angle factor:
full confidence only up to |yaw|/|pitch| ≤ 15°, zero at 60°. With `eyeWidthPx` quality derating, a
user who turns their head ~30-35° to look at the edge of the screen lands at
`0.75 * (1 - (35-15)/45) ≈ 0.42 < 0.45` → `!isDetected` → after 4 such frames (`GAZE_HIDE_MISS_FRAMES`)
the cursor is hidden, the smoother is reset and any in-progress blink is aborted. Looking at the side of
the screen is precisely what the product must support; head rotation must not be conflated with "no
gaze".

### A4 · CATEGORY G (filter) — the personalized jump suppressor discards whole frames

`FaceTracker.handleResult` (`:361-374`):

```
if (featureDelta < STABLE_FEATURE_DELTA /* 0.8 */ && outputJump > PREDICTION_JUMP /* 0.12 */) return
```

`featureDelta` is the sum of |Δ| over all 23 feature dimensions, most of which are *irrelevant* to eye
motion (head yaw/pitch/roll, face translation, face scale, eye/iris ratios). A real medium saccade
changes `irisAlongAxis` by ~0.1 in the four eye-local dims → total Δ ≈ 0.4 < 0.8 — while its screen-space
effect (0.1–0.4 of the display) is far above 0.12. So **a legitimate gaze change is classified as
model noise and the entire frame is dropped**: the early `return` skips *both* the `GazePoint` and the
`GazeObservation`, so during calibration it silently steals samples too. And because
`lastPersonalizedFeatures/X/Y` are not updated on a rejected frame, the next accepted frame is compared
against a stale baseline → the cursor holds, then teleports. That is exactly the reported
"cursor does not follow, then jumps".

This is a second temporal filter layered on top of `CursorSmoother` (One Euro) in the collector
(`:794`) — the "RAW ↓ filter ↓ gate ↓ filter ↓ gate ↓ jump suppress" shape Phase 6 forbids.

### A5 · CATEGORY C/H (coordinate + lifecycle) — mirror handling is correct but implicit

The analysis bitmap is mirrored once and only once, in `camera/CameraService.imageProxyToMPImage`
(`:714 m.postScale(-1f, 1f, ...)`), applied for *every* camera. `FaceTracker.buildFaceLandmarkFrame`
then hardcodes `isFrontCameraMirrored = true` (`:444`) and both `EyeFeatureExtractor.p()` (`:151`) and
`HeadPoseEstimator.landmark3()` (`:169`) un-flip x into a canonical "person view". Verified: no double
inversion in the advanced path, because both consumers read the same flag on the same frame object.

Fragility, not a bug: the flag is a hardcoded constant rather than the camera's actual state, so the
one intentional interpretation is asserted in a different file from where the mirror happens. And the
*legacy* path (`computeGaze`) never consults it at all — it consumes raw mirrored landmark x, which is
why the user-facing `gazeInvertX` preference exists as an escape hatch. Two conventions coexist.

### A6 · CATEGORY E — the personalized model is gated so hard that "no model" is the normal state

Fitting requires ≥ 90 retained samples across all 9 targets (`PersonalizedGazeCalibration:548,362`) and
fails if the worst target's mean error exceeds 0.10 (`:410,556`). Calibration windows are
1200 ms fixate + 2200 ms collect with `MIN_SAMPLES_PER_TARGET = 12`
(`ui/gazecalibration/GazeCalibrationViewModel.kt:377-387`), and every sample additionally needs
`quality ≥ 0.45` *and* a valid pose *and* both eyes (A2) *and* `EAR > 0.16`. Realistically many sessions
land on `insufficient robust calibration samples` → the log line at `:281` says "falling back to affine"
→ and the affine fit is built from A1's horizontally-dead centroids, whose X values are all ≈ equal, so
`solveAxis` is near-singular (`GazeCalibration:117`) or the residual gate (`:320`) rejects it. Result:
calibration "completes", nothing is saved, and the runtime runs the broken legacy path.

There is one more guard that silently rejects an honest user: `MIN_TARGET_SIGNAL_SPREAD = 0.05` compares
raw centroids (`:242-261`). Because horizontal is cancelled (A1), spread is driven by Y only; a user
who calibrated correctly can still be told their eyes "did not track the targets".

**Invariant 4A is satisfied**, incidentally: `GazeObservation.rawX/rawY` always come from `rawGaze`
(`FaceTracker:394-404,414-422`), never from the model prediction, and failure/cancel writes no
preferences, so an old model survives a failed recalibration. It needs tests, not a fix.

### A7 · Categories A/H — detection floor is fine, but no frame-age or ordering guard

`processFrame` bumps `lastSubmittedTimestampMs + 1` on ties (`:196-201`), so timestamps are monotonic
but can drift ahead of the true capture time when the graph lags; `handleResult` then re-derives
`latencyMs` from it (`:248`). Results are delivered by MediaPipe's own thread in submission order and
the tracker is never re-created between frames, so out-of-order/stale-frame application is *not* a live
failure mode here — recorded as verified-not-broken rather than assumed.

---

## B. Swipe — why it went from too sensitive to too hard

The live path is `GestureEngine.processFrame → DynamicGestureDetector.process → analyzeWindow`
(`gesture-engine/detection/DynamicGestureDetector.kt:330-523`).

### B1 · Eight sequential hard AND-gates, each independently able to veto

```
cooldown → displacement → axis dominance → vertical strict 2x → peak velocity
         → vertical monotonic reversals → moving steps → directional consistency
```

Every one is a `return SwipeResult(detected = false)`. Individually reasonable; jointly they require a
gesture that is simultaneously long, straight, fast, monotonic, multi-step *and* consistent. Phase 6's
objection applies verbatim to the hand pipeline.

### B2 · Velocity is a hard gate with a floor no real deliberate swipe clears comfortably

`swipeVelocityThreshold = 1.2`, sensitivity-scaled then clamped: `(1.2/swipeEase).coerceIn(0.9, 1.8)`
(`config/GestureEngineConfig.kt:163,240`). At the default sensitivity 70, `swipeEase = 0.60 +
0.7*0.80 = 1.16` → threshold **1.034 normalized-units/s**, and — critically — **0.9 is the minimum
achievable at any sensitivity**, so the slider cannot rescue it.

Peak velocity is the max over single inter-sample steps (`computePeakVelocity`), with the window at
350 ms. Crossing `0.069` (the displacement floor) evenly inside 350 ms implies ~0.2 units/s average;
to produce a single 41 ms step of ≥ 1.03 u/s the hand must move ≥ ~0.043 of the frame *per frame*,
i.e. ~27 px at 640 px width. That is a flick, not a swipe. A deliberately thrown hand over ~0.5 s —
the motion Phase 12D says must still work — peaks below 1.03 and is rejected `TOO_SLOW`. This is the
single largest contributor to "now it is too difficult to trigger".

### B3 · Axis dominance 2.0 is a ±26.5° cone; human swipes are arcs

`dominantDisp / secondaryDisp < 2.0 → DIAGONAL_AMBIGUOUS` (`:366`), and vertical swipes get an
additional `absDispY < 2.0 * absDispX → VERTICAL_TOO_DIAGONAL` (`:405-416`). A forearm swinging past a
front camera rises or falls while it crosses: the *path* is an arc and the horizontal/vertical ratio of
the endpoints is commonly 1.2–1.8, which is exactly the rejected band. `signedThrowDirection` (`:534`)
makes the *choice* of direction arc-robust, but the *veto* still uses the endpoint ratio, so an
imperfect-but-clearly-intentional human swipe dies before direction is ever consulted.

### B4 · The composite "confidence" is not calibrated to anything, and is dead weight

`analyzeWindow` ends by computing `confidence` (`:506-512`) whose own documentation admits a
gate-passing bare-minimum swipe scores 0.35. It is wired only to telemetry: `IntentEngine`
(`minimumConfidence = 0.55`), `GestureReliability`, `SafetyPolicy` and `GestureReliabilityPolicy` are
**never constructed by production code** (`grep IntentEngine( app/src/main gesture-engine/src/main` →
only the declaration). The tests `IntentEngineTest`, `IntentSafetyTest`, `GestureReliabilityTest`,
`SafetyPolicyTest`, `GestureReliabilityPolicyTest` are green against unreachable code. This is the case
the "do not trust test names" rule was written for: hardening rounds added an arbitration layer that
the runtime never calls.

(Positive side of the same finding: because `IntentEngine` is unreachable, its 0.55 floor is *not* a
second rejection source. Had I trusted the report in `docs/HARDENING-IMPLEMENTATION-REPORT.md`, this
fix would have targeted dead code.)

### B5 · `lowConfidence` mutes swipes during exactly the fastest motion

`GestureEngine.processFrame:134` sets `isLowConfidence = input.confidence < 0.7`, and
`input.confidence` is the **handedness** score (`tracking/HandTracker.kt:183`). Motion blur during a
deliberate throw lowers handedness, so after 3 such frames `lowConfidenceMode` latches and swipes are
dropped at `:272` — while 3 further good frames are needed to un-latch. A fast swipe is therefore
penalised for being fast. Combined with B2 this is the "moved correctly, nothing happened" report.

### B6 · Neutral re-arm demands inhuman stillness, and wipes the evidence

After a detected swipe: `awaitingNeutralRearm = true` and *both* windows are cleared (`:242-254`). While
latched, every frame must satisfy `moved < NEUTRAL_STILL_PER_FRAME = 0.006` per frame
(`:156,745`) — ≈ 0.144 u/s, a hand held "still" in front of a webcam exceeds that in individual frames —
and any single moving frame resets `neutralRearmElapsedMs` to 0 (`:163-164`), so 250 ms must be
accumulated **without one noisy frame**. Meanwhile `wristWindow.clear()` / `indexTipWindow.clear()`
run on *every* latched frame (`:166-167`), so even after re-arming the window must refill to
`requiredSampleCount` before another swipe is analyzable. Net effect: the second and third consecutive
swipe of a real scrolling session are swallowed. Over-rejection again, from a different mechanism.

### B7 · Why it used to be too sensitive (and what the fix must preserve)

The pre-hardening sensitivity came from structural gates, not numbers: no open-palm requirement,
500 ms window, and endpoint-displacement direction (`git show 2fe2ccc` window 500→350 ms,
cooldown 300→220 ms; `f05f941` added the neutral re-arm latch). Those structural protections
(`swipeRequiresOpenHand`, arming FSM, pinch-vs-swipe arbitration at `GestureEngine:269-271`) are the
right ones and are **kept**. What must go is the practice of expressing each protection as an
independent boolean veto.

---

## C. Classification summary

| category | eye tracking | swipe |
|---|---|---|
| A detection | no (`MIN_*_CONFIDENCE 0.5` is reasonable) | no |
| B feature extraction | **yes — A1 horizontal cancellation, A2 both-eyes requirement** | partially (endpoint-ratio geometry) |
| C coordinate | **yes — legacy path ignores the mirror flag (A5)** | no |
| D calibration | yes, downstream (A6 affine on dead signal) | n/a |
| E personalized model | yes — gate too strict, effectively always off (A6) | n/a |
| F confidence | **yes — one scalar, two meanings, head angle inside it (A3)** | **yes — handedness mutes fast motion (B5)** |
| G filter | **yes — frame-discarding jump suppressor (A4)** | n/a |
| H lifecycle | no evidence of mis-start; `initialize()` reuses model safely | **yes — re-arm latch swallows follow-on swipes (B6)** |
| I concurrency | no ordering hazard found (see A7) | no |
| J rendering/state | cursor follows the smoother; nothing to fix once input is correct | n/a |
| K multi-stage interaction | **yes — dominant cause** | **yes — dominant cause** |

## D. Design rules adopted (no magic fixes)

1. Raw iris geometry is the single source of truth for direction; every derived value states its
   coordinate space, axis orientation and normalization in its own type (`GazeCoordinateSpaces`).
2. Sign conventions are **derived from landmark geometry**, never hardcoded multipliers or offsets.
3. Exactly one temporal filter per channel (One Euro for the cursor). Anti-jump protection modulates
   confidence; it does not discard frames or reset other filters.
4. Uncertainty sources stay separate (`face / eye quality / pose / model / signal`) and are combined
   once, into per-purpose eligibility: `cursorUpdate`, `cursorVisible`, `actionEligible`,
   `calibrationEligible`.
5. Swipe recognition is one state machine with a continuous intent score and hysteresis
   (`candidate < commit`); quality raises or lowers the bar instead of muting the channel; ambiguity
   yields no direction; motion evidence is never destroyed by a protection latch.
6. Thresholds are justified by human movement: a deliberate one-hand throw covers ≥ 15 % of the gesture
   space in 200-600 ms; a natural fixation holds at < 0.3°/frame of iris travel; an arc of 20-30° off
   axis is normal and must not be a veto.

## E. Local verification

`:app:testDebugUnitTest` cannot run here: the sandbox has 1.9 GB RAM, AGP+Kotlin+Compose needs more
(the daemon is OOM-killed at `-Xmx1100m` as well, twice observed). `tools/kj.sh` therefore compiles and
runs the *real* Android-free production sources with the project's own Kotlin 2.2.10 and JUnit 4.13.2:
all 19 gesture-engine test sources plus the 5 Android-free gaze test sources
(`EyeFeatureExtractorTest`, `HeadPoseEstimatorTest`, `OneEuroFilterTest`, `BlinkDetectorAbortTest`,
`GazeSmoothingMetricsTest`) = 24 test classes, **235 tests, all passing at baseline**. The 3 gaze test
files needing Robolectric/Android (`PersonalizedGazeCalibrationTest`, `AdaptiveFpsControllerTest`,
`ThermalGovernorTest`) run in CI. Everything Android/MediaPipe/Hilt-dependent is verified by the project's own CI
(`.github/workflows/*.yml`), which runs `testDebugUnitTest`, `:gesture-engine:test`, `assembleDebug`
and signed `assembleRelease` on push.
