# Recovery result — eye tracking + swipe intent (evidence log)

Companion to `EYE-SWIPE-RECOVERY-AUDIT.md` (the read-only Phase 0 forensics). This file records
what was actually changed, what was verified, and what was not. Every number below comes from a
command run against this clone; nothing is quoted from a previous report, commit message or test name.

## Branch / commits

Fresh clone at `/home/user/jskair-recovery`, branch `fix/eye-tracking-swipe-intent-recovery`,
starting from `main` = `7b234c5` (clean tree, CI green at that SHA).

| commit | what it is | files |
|---|---|---|
| `a5d043d` | Phase 0 forensic audit (read-only; no production change) | 1 (+287) |
| `31f9578` | raw gaze pipeline repair, coordinate contract, confidence separation | 13 (+1782/−284) |
| `7f9679f` | swipe recognition rebuilt as a human-intent state machine | 12 (+2059/−612) |
| `335e278` | CI fix: display/action predicates moved onto the eligibility enum | 1 (+20/−3) |
| `66c9fb3` | CI fix: gaze-calibration test fixture given the new viewer-frame fields | 1 (+13/−1) |
| `bd32c01` | debug screen: swipe state machine made visible | 7 (+424/−4) |
| `4d34aae` | CI fix: match DebugViewModel's own flow-exposure idiom | 1 (+3/−3) |

`335e278`, `66c9fb3` and `4d34aae` exist only because the local harness cannot type-check the
Android module (see "Verification limits"). They are not design changes.

## Root causes → what replaced them

| # | defect (verified in code, not in reports) | after | pinned by |
|---|---|---|---|
| A1 | horizontal gaze was arithmetically cancelled: `computeGaze` averaged each eye's offset *toward its own temporal corner*, and the two viewer signs are opposite, so the mean was a constant → cursor pinned near x=0.5 and calibration was fed noise | one eye geometry (`irisAlongAxis`/`irisPerpendicular` + derived `axisSign`), viewer-frame `irisViewerX/Y`, `RawIrisGaze` averages in the viewer frame then folds once | `RawIrisGazeTest` |
| A2 | binocular requirement rejected monocular tracking outright | one usable eye is allowed, weighted | `GazeEligibilityAndTemporalPolicyTest` |
| A3 | one scalar confidence did double duty (display *and* action), head angle folded into it | `GazeEligibilityPolicy.evaluate(face, eyeGeometry, headPose, gazeSignal, model)` → NOTHING / UPDATE_ONLY / VISIBLE / ACTIONABLE; head angle degrades instead of vetoing | same test |
| A4 | the jump suppressor kept a stale baseline and froze the cursor | `GazeJumpPolicy`: the arbiter is *measured iris motion*, not prediction error; ACCEPT / HOLD / **REPRIME** after a bounded hold | same test |
| A5 | mirror interpretation existed twice with different meanings | one constant drives both `CameraService.imageProxyToMPImage` and the flag reported to the tracker | code inspection + compile-time single source |
| A6 | the personalized model could never engage (gate too strict) and could be clobbered by a failed calibration | versioned persistence (`RAW_SCHEMA_V2` refuses pre-v2 data), atomic replace on success, failure/cancel keeps the old model | `PersonalizedGazeCalibrationTest` (CI-only: needs Robolectric) |
| D1 | nine serial AND gates in `analyzeWindow()`; a deliberate sweep failed the velocity gate, a natural arc failed the dominance cone, and no single threshold change could fix both | one `SwipeIntentArbiter` state machine: NEUTRAL → TRACKING → CANDIDATE → COMMITTED → COOLDOWN → NEUTRAL, one weighted intent score, candidate < commit hysteresis | `SwipeIntentTest` (22 tests), `SwipeStressTest`, `DynamicGestureDetectorTest` |
| D2 | swipe thresholds were frame fractions, so the same gesture meant different things per sensor and per user | displacement in the user's own palm spans (wrist → middle MCP, clamped 0.06–0.22), x multiplied by the analysis image's aspect ratio, threaded HandTracker → HandFrame → HandInput → detector | `SwipeIntentTest` aspect/palm tests |
| D3 | velocity was a gate (a slow-but-real swipe could never pass) | velocity is weighted evidence, not a requirement: weights sum to 0.86 so a no-velocity swipe can still reach the 0.62 commit level | `too slow swipe rejected across frame rates` (rewritten, see below) |
| D4 | a re-arm latch swallowed follow-on swipes; the "sensitivity" slider clamps deadened the lower half of its own range | cooldown ends only after accumulated stillness *credit* (≤0.55 spans/s adds time, faster subtracts); the re-arm frame reports nothing, the next sample is dropped as a boundary; sensitivity shifts both thresholds together so the gap cannot collapse | `back to back swipes both fire when the hand settles between them`, `a wave is never a swipe at any frame rate` |
| D5 | the handedness score (`lowConfidence`) muted fast motion — the exact moments a flick depresses it | removed from the swipe branch; tracking quality is one weighted term with a 0.30 floor ("a hand is genuinely there") instead of 0.70 | `GestureAdversarialTest` |
| I1 | the debug channel for swipe had no subscriber (`grep -rn onSwipeDecision app/src` → empty), so an over-rejecting detector was invisible | `SwipeDebugInfo` snapshot of exactly what the arbiter used, onset-counted rejections, capped 12-line verdict log, `SwipeIntentStrip` on the debug screen; hook attached only when `BuildConfig.DEBUG` | 3 new snapshot tests |

Superseded code was deleted rather than left dormant: both `analyzeWindow()` overloads,
`signedThrowDirection`, `computeDirectionalConsistency`, `countDirectionalReversals`,
`countMovingSteps`, `requiredSampleCount`, the neutral-rearm latch fields, the dead
`lastSwipeTimestampMs`, and the config knobs `swipeDisplacementRatio` / `swipeVelocityThreshold` /
`swipeAxisDominanceRatio` (grep proved no remaining references).

## Tests

Command: `grep -rho "@Test" --include=*.kt app/src gesture-engine/src | wc -l`

- baseline `7b234c5`: **527** `@Test` methods, 56 files
- now `4d34aae`: **583** `@Test` methods (+56), 56 files

Local pure-Kotlin suite (`tools/kj.sh`, the project's own Kotlin 2.2.10 + JUnit 4.13.2 over every
Android-free production and test source): **235 → 269 (eye phase) → 288 (swipe rebuild) → 291
(debug snapshot), all passing**, compiled warning-clean — which matters because CI builds with
`allWarningsAsErrors`.

Nothing was deleted. Three swipe assertions and two fixtures were rewritten, each because the
fixture or the expectation encoded the *old* mechanism; none was loosened toward "accept
anything":

1. `too slow swipe rejected across frame rates` — a slow sweep is now a scoring question, not a gate; it asserts refusal below the commit level.
2. `diagonal motion with insufficient axis dominance rejected` → `a true diagonal is not guessed and a dominant one is resolved` — the band replaces the cone.
3. `L shaped movement is rejected` — kept rejecting; the reason moved from "not monotonic" to path-shape evidence.
4. `three identical swipes fire exactly three events` (`GestureAdversarialTest`) — needed two honest fixes: the fixture teleported the hand across half the frame in one frame (the detector correctly truncates at a teleport, so the fixture now returns naturally over 6 frames), and the test never drained the `MutableSharedFlow` between reps, so the third event was lost in the harness, not in the engine (`runCurrent()` per rep). Asserted count is unchanged: 3.
5. `PersonalizedGazeCalibrationTest` fixture — `NormalizedEyeFeatures` gained `irisViewerX/irisViewerY`; the values are derived with the relation `EyeFeatureExtractor` publishes, not invented. Asserted numbers untouched.

## CI evidence

`.github/workflows/android-apk.yml` (debug APK + signed release APK) and `build-apk.yml`
(`:app:testDebugUnitTest`, `:gesture-engine:test`, Android 17 debug APK), triggered on push:

| head | Build debug APK | Android 17 Debug APK (unit tests) | Build signed release APK ×2 |
|---|---|---|---|
| `31f9578` | failure (`FaceTracker.kt:69/72` unresolved `showsCursor`/`canAct`) | failure | failure |
| `66c9fb3` | **success** | **success** | **success** |
| `bd32c01` | failure (`DebugViewModel.kt:99/102/105` unresolved `asStateFlow`) | failure | failure |
| `4d34aae` | **success** | **success** | running at the time of writing |

Artifacts (retained 14 days): `aircontrol-debug-apk` and `aircontrol-release-signed-apk`, uploaded
by `.github/workflows/android-apk.yml`; the release job runs
`apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` before uploading, so
the signature is checked in CI rather than assumed.

## Verification limits — read this before calling it done

- **Code-level verified only.** Deterministic unit tests + compilation of both modules in CI. The
  sentence "eye tracking and swipe are fixed on device" is not supported by anything in this log.
- The local harness compiles only Android-free sources. It never compiled `FaceTracker.kt`,
  `CameraService.kt`, the accessibility service or any Compose file, because they need the real
  MediaPipe/Hilt/AGP class graph (and `:app:*` Gradle tasks are OOM-killed in this 1.9 GB sandbox).
  That blind spot is precisely what produced `335e278` and `4d34aae`: both errors were in files
  the harness cannot see. **CI is the only oracle for the app module**, so treat any future
  app-only change here as unverified until a run turns green.
- `androidTest` (instrumented) was not run at all: it needs a device or emulator, which this
  environment does not have. That includes the camera-driven gaze and swipe tests.
- Phase 19 device acceptance remains the real bar and was **not** performed: 20 deliberate
  swipes in each direction with ≥18 firing, no scroll while moving the cursor, no double-fire on
  a returning hand, cursor visibly equal to the click point, blink click only after gaze settles,
  dwell re-arming after a saccade, and a calibration re-run that replaces the model atomically.
- Gaze accuracy numbers (degrees of error, latency under real lighting, behaviour with glasses or
  partial occlusion) are not measured anywhere here: the repo has no recorded ground-truth data and
  no camera in this sandbox. The improvements claimed are structural — direction no longer
  cancels itself, one mirror interpretation, confidence no longer double-used, jumps reprime
  instead of freezing.
- The swipe thresholds (0.42 candidate / 0.62 commit, 0.42-span minimum travel, 80 ms candidate
  maturity, 220 ms cooldown, 30° heading drift, 0.72 path efficiency) are justified in this log by
  signal and human-motion reasoning plus the deterministic tests, not by user data. A short
  device session is the only thing that can confirm they are right for a real hand at a real
  distance; that is what the debug strip on the screen is for.

## Two things this round found in itself

1. The regression that cost the most time was not a threshold: a continuity check ran *after* the
   current sample was appended to the window it compared itself against, so `dt == 0`, every frame
   read as a discontinuity, every window was cleared, and nothing ever committed. Symptom: 34
   failures with `expected:<3> but was:<0>`. Ordering bugs of that shape are invisible to review
   that reads each statement in isolation — the fix records the rule in a comment at the site.
2. `git log`/comments/reports in this repo were repeatedly wrong about their own code (e.g. an
   `IntentEngine`/`SafetyPolicy` "over-rejection" chain that is never constructed; a
   `HandTrackingPipeline` that does not exist). Everything in this file is the output of a grep or
   a test run against this tree.

## Phase 17 — static, concurrency and performance pass

Run over the changed files only, with the questions the phase asks: is anything unreachable,
is anything shared across threads without a reason, and does any of it cost the frame path.

**Static / dead code.** `git grep` over `main` and both test sources, not a reading of names:

- the legacy `SwipeRejectReason` constants `VERTICAL_TOO_DIAGONAL`, `VERTICAL_NON_MONOTONIC` and
  `COOLDOWN` had zero references in production and tests (their gates were deleted in `7f9679f`) —
  removed, with the enum's KDoc now stating that it contains exactly what `mapHoldReason` can
  return and where the cooldown went instead (the phase plus `SwipeDebugInfo.cooldownRemainingMs`).
- all eight `SwipeHoldReason` values *are* produced: `LOST_MID_GESTURE` at
  `SwipeIntentArbiter.kt:209` (candidate released while a cooldown was still running) and
  `COOLDOWN` at `:219`; the other six are produced by `holdReasonFor`. An earlier grep that counted
  occurrences per file suggested both were dead — reading the two lines showed the opposite, which
  is why this section cites line numbers.
- deleted with the rebuild: `lastSwipeTimestampMs` (written, never read), `analyzeWindow()` ×2,
  `signedThrowDirection`, `computeDirectionalConsistency`, `countDirectionalReversals`,
  `countMovingSteps`, `requiredSampleCount`, the neutral-rearm latch fields, and the config knobs
  `swipeDisplacementRatio` / `swipeVelocityThreshold` / `swipeAxisDominanceRatio`.
- `SwipeIntentArbiter.heldForMs()` was a private one-line delegate to `heldMsAt()` with no call
  sites — removed.
- The `else` branch of the score-deficit arm in `holdReasonFor` returned `LOW_VELOCITY` for frames
  that were moving fast (above `VELOCITY_REPOSITION_SPANS_PER_S = 1.5` spans/s) but scored weakly,
  i.e. it named the wrong thing for the one case it could only reach by *not* being slow. That case
  now has its own label, `LOW_INTENT_SCORE`, mapped to `SwipeRejectReason.BELOW_INTENT_SCORE`. No
  test pins the new label: reaching it needs healthy travel, steps, quality, drift, consistency and
  dominance with a score under the candidate level, and no fixture in this repo lands there — the
  change exists so the reported reason is not a lie, not to alter behaviour, and the 291 tests that
  do assert reasons are unchanged by it.
- compilation is warning-clean for both modules' pure-Kotlin sources, which is what CI needs
  (`allWarningsAsErrors` in `gesture-engine/build.gradle.kts`).

**Concurrency.** The swipe machine is confined to the thread that calls
`DynamicGestureDetector.process()` — one producer, no reordering to reason about. The only
cross-thread reads are the debug ones: `lastDebugDecision` / `lastDebugEvidence` /
`lastDebugTimestampMs` are each `@Volatile` and are written from the frame thread at one call site,
so a reader on the UI thread can combine a decision from frame *N* with a timestamp from frame *N-1*
(one frame of skew in an overlay; it cannot change a phase or a reason). That limitation is written
into the field comment rather than claimed away — the alternative (publishing one prebuilt
immutable snapshot) allocates on every frame in release builds where nobody reads it.
`GestureEngine.onSwipeDecision` is `@Volatile` because it is assigned once after construction and
read per frame; the only assignment in the app is `attachSwipeDecisionHook()` called from
`GestureDetectorImpl`'s `init` block, which runs before the object is injectable and therefore
before any frame can be fed. The app-side log is updated under a `synchronized` block, but only the frame thread
ever enters it — the UI reads `_swipeLog.value`, a `StateFlow` value, not the buffer — so there is
no contention to protect against, and no lock is held across anything that can block.
`StateFlow`'s equality conflation is what keeps a held reason from republishing to Compose each
frame.

**Performance / memory bounds.** Per frame in a *release* build: the evidence and decision records
the pipeline already needed (one `SwipeEvidence` per candidate window per channel, one `Decision`
per frame), no string building, no logging — `swipeDebugInfo()` is reachable only behind
`if (debugInstrumentation)`. No main-thread work is added, and nothing in the path does
I/O. Windows are bounded twice over: shape windows by count
(`MAX_SHAPE_SAMPLES = 24`, trimmed with `removeFirst`) and throw windows by time
(`swipeWindowMs = 350`), so a stalled or hyperactive feed cannot grow them; the debug verdict log
is capped at 12 lines; the rejection counter map is keyed by a closed set (5 reject reasons plus the
engine's `COMMITTED`/`REJECTED` labels), so it cannot grow either. Counters and log are cleared by
`reset()` — per session, matching the gaze ring buffer. The event flow keeps the pre-existing
`extraBufferCapacity = 64` with `DROP_OLDEST`, i.e. a slow collector drops rather than backs up the
frame thread. Nothing in the swipe path does I/O, allocation-free? no: allocation yes, I/O none —
and nothing blocks the main thread: the debug screen only reads `StateFlow` values it is handed.
