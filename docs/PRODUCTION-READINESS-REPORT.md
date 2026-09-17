# Jskair Production Readiness Report

**Project:** Jskair / AirControl  
**Repository:** `skb648/Jskair`  
**Audit date:** 2026-09-17  
**Baseline commit:** `84d9b46e0e3980617527b4016772946b15a370a7`  
**Current main commit:** `057542ef030d711182f527062ad1118760ee81eb`  
**Release versionName:** `1.0.1`  
**compileSdk / targetSdk / minSdk:** `37 / 37 / 26`  
**JVM target:** Java 17  
**Gradle:** 9.3.1  
**AGP:** 9.1.1  
**Kotlin:** 2.2.10  

## 1. Executive Summary

A repository-wide production-hardening pass was performed against the current `main` branch. Existing working architecture was preserved; changes were focused on correctness, action safety, lifecycle behavior, privacy disclosure, release controls, and regression coverage.

Major source-level fixes now present include:

- monotonic release `versionCode` derivation with an explicit published-release comparison in CI;
- FPS quantization that never rounds a configured/thermal cap upward;
- bilateral gaze validation using horizontal and vertical eye agreement;
- explicit `NOTHING → UPDATE_ONLY → VISIBLE → ACTIONABLE` gaze policy separation;
- monocular gaze excluded from actionable interaction;
- minimum head-pose confidence for action authorization;
- MediaPipe facial transformation matrix output enabled and wired into head-pose estimation;
- fail-closed handling of non-finite personalized gaze predictions;
- independent hand landmark geometry quality versus handedness score;
- invalid-EAR blink recovery and baseline reset;
- linear and quadratic personalized gaze model persistence validation;
- allocation-free hand cursor hot path;
- removal of the tracked `.env` file and addition of a high-signal secret scan;
- privacy/accessibility disclosure updated to match `canRetrieveWindowContent=true` and `flagRetrieveInteractiveWindows`;
- expanded release CI with lint, unit tests, executed instrumentation tests, APK verification, and release-version checks;
- expanded manual QA matrix and regression checklist.

This report deliberately distinguishes source-verified fixes from validations that require a physical Android device. A build passing is not treated as proof of runtime correctness.

## 2. Baseline

The requested baseline was the `main` branch at `84d9b46e0e3980617527b4016772946b15a370a7`, dated 2026-09-15. The repository already had successful earlier CI evidence for unit tests, debug APK generation, signed release APK creation, and signature verification.

During this hardening pass, CI also exposed real problems that were fixed rather than hidden:

- an initial hardened lint run reported **12 errors and 152 warnings**;
- a first Bluetooth HID API-level fix introduced a Kotlin annotation placement compile error;
- the API-level guard was then corrected by guarding the delegated property getter and public paths explicitly.

The local execution environment used for this audit cannot reach GitHub's network endpoints directly, therefore local `./gradlew` results are not claimed as executed locally. GitHub Actions was used as the authoritative remote execution environment where available.

## 3. Issues Found

| ID | Severity | Component | Root Cause | User Impact | Fix | Regression Test | Validation Status |
|---|---|---|---|---|---|---|---|
| R-01 | P0 | Release | Static `versionCode` baseline could repeat published versions | Update/install failures or forced reinstall | CI-aware monotonic version derivation plus published-APK comparison | CI release version gate | FIXED — NEEDS PUBLISHED-RELEASE VALIDATION |
| R-02 | P1 | CI | Instrumentation tests were compile-only | Runtime regressions could pass CI | `connectedDebugAndroidTest` on API 35 emulator | CI instrumentation gate | FIXED — NEEDS CI RUN |
| E-01 | P1 | Gaze | FPS cap could quantize 20 → 24 | Runtime rate differed from configured/thermal cap | Round down to highest supported rate ≤ requested | FPS quantization test | VERIFIED FIXED |
| E-02 | P1 | Head pose | Transformation matrix existed conceptually but was not wired | Less accurate head compensation and extra fallback work | Enable and consume facial transformation matrix | Head-pose unit coverage + device QA | FIXED — NEEDS DEVICE VALIDATION |
| E-03 | P1 | Gaze throughput | Camera FPS can exceed FaceLandmarker result rate | Eye cursor can feel sticky despite high camera FPS | Preserve one-in-flight latest-frame strategy; expose latency telemetry | Existing in-flight tests + device profiling required | OPEN — DEVICE/PERFORMANCE VALIDATION |
| E-04 | P1 | Gaze action safety | One-eye evidence could satisfy action gate | Accidental click on partial eye visibility | Monocular samples are cursor-only and non-actionable | Gaze policy tests | VERIFIED FIXED |
| E-05 | P1 | Pose/action safety | Pose confidence was not independently required for actions | Weak head geometry could authorize a tap | Added explicit action pose-confidence floor | Gaze eligibility tests | VERIFIED FIXED |
| E-06 | P1 | Hand quality | Handedness score was used as general tracking confidence | Corrupt landmark geometry could look trustworthy | Added independent geometric landmark quality | HandFrame quality tests | VERIFIED FIXED |
| E-07 | P1 | Gaze consistency | Binocular agreement only checked horizontal disagreement | Vertical corruption could pass action validation | Agreement now uses X and Y; conservative minimum | Direction/vertical disagreement tests | VERIFIED FIXED |
| E-08 | P1 | Numeric safety | Non-finite personalized prediction could enter jump policy | Cursor/action could become invalid | Invalid predictions now HOLD at safe baseline/neutral | Jump-policy safety tests | VERIFIED FIXED |
| G-01 | P1 | Gesture transport | Semantic events used DROP_OLDEST buffered flow | PINCH START/END or discrete events can be lost during sustained stalls | Requires event-channel separation without breaking ordering | Pending targeted FSM/transport test | OPEN |
| G-02 | P1 | Gesture state | Lifecycle/interrupt paths can cut semantic sequences | Potential stranded drag or missed terminal event | Existing transient reset hardening preserved; transport still needs guaranteed semantic delivery | Manual/device cancellation matrix | OPEN — DEVICE + TRANSPORT WORK |
| C-01 | P2 | Cursor | Per-frame list/boxing in palm anchor | Possible micro-GC pauses and cursor jitter | Allocation-free MCP/wrist/index arithmetic | Hot-path code inspection | VERIFIED FIXED |
| C-02 | P2 | Gaze history | `CopyOnWriteArrayList` is used for a tiny hot history | Repeated mutation can copy the backing array | Potential extra allocation under long sessions | Needs bounded ring-buffer replacement | OPEN |
| CAL-01 | P1 | Calibration | Both linear and quadratic runtime models exist; persistence had to match both | Calibration could be lost after restart | Serializer validates both model types and coefficient basis sizes | Linear round-trip test + existing serializer tests | FIXED — NEEDS TEST EXECUTION |
| CAL-02 | P1 | Calibration | Poor/noisy calibration could survive global-average quality checks | Good center with bad corner | Worst-target error gate retained and documented | Existing fitter tests | FIXED — NEEDS TEST EXECUTION |
| CAL-03 | P1 | Calibration | Corrupt/incompatible persisted model could be trusted | Wrong cursor mapping after restart or schema change | Strict schema/signature/dimension/screen/timestamp validation | Serializer corruption/mismatch tests | VERIFIED FIXED at source level |
| B-01 | P2 | Blink | Invalid EAR could leave stale closure state | Blink click could recover incorrectly | Non-finite/negative EAR aborts closure | Blink production tests | VERIFIED FIXED |
| B-02 | P2 | Blink | Personalized baseline could outlive a meaningful reset | Blink behavior could become inconsistent after environment change | Reset clears learned baseline | Blink reset test | VERIFIED FIXED |
| L-01 | P1 | Lifecycle | Camera/MediaPipe close and restart paths are complex | Potential stale inference/use-after-close if unguarded | Existing close lock + in-flight reset preserved | Existing lifecycle tests/manual matrix | FIXED — NEEDS DEVICE VALIDATION |
| A-01 | P1 | Accessibility | Touch injection behavior depends on OEM/platform input stack | Pinch/drag/swipe may differ across devices | Explicit OEM/device validation matrix; fail-safe transient resets | Manual device test matrix | OPEN — DEVICE REQUIRED |
| A-02 | P1 | Accessibility | Service restart/interruption needs physical lifecycle coverage | Stuck press or stale overlay possible | onInterrupt cleanup and service reset paths retained | Instrumentation/manual lifecycle tests | FIXED — NEEDS DEVICE VALIDATION |
| O-01 | P2 | Overlay | WindowManager behavior is OEM-specific | Cursor can disappear or overlay creation can fail | Exceptions are surfaced; degraded operation is tolerated | Instrumentation/manual overlay matrix | FIXED — NEEDS DEVICE VALIDATION |
| U-01 | P2 | UX | Older settings terminology could contradict behavior | User can misunderstand FPS, boot, or accessibility behavior | Current labels/docs aligned with runtime behavior | UI/manual checklist | FIXED — NEEDS UI EXECUTION |
| P-01 | P1 | Privacy | Accessibility capability disclosure was broader/narrower than actual capability | Trust/privacy confusion | Precise disclosure of limited local UI metadata | Static docs/manifest review | VERIFIED FIXED |
| S-01 | P1 | Security | Tracked `.env` placeholder and no automated high-signal secret scan | Risk of accidental credential commits | `.env` removed; `.gitignore` hardened; secret scan added to CI | Secret-scan script | VERIFIED FIXED at repository level |
| S-02 | P2 | Security | HID logs could expose host identifiers unnecessarily | Unneeded device metadata in logs | Logs no longer print Bluetooth addresses | Source inspection | VERIFIED FIXED |
| T-01 | P1 | Thermal | Thermal cap shares FPS quantization behavior | Hot device could operate above intended reduced rate | Controller never rounds above requested cap | FPS tests | VERIFIED FIXED at controller level |
| T-02 | P1 | Thermal | Actual long-session temperature/CPU behavior is device-specific | Progressive degradation possible | QA soak plan and lightweight telemetry | 15/30/60 minute physical tests | OPEN — DEVICE REQUIRED |
| D-01 | P2 | Diagnostics | Rich gaze telemetry is debug-oriented | Harder production diagnosis without raw camera data | Aggregated privacy-safe metrics exist; detailed debug remains debug-only | Telemetry tests/manual inspection | FIXED — NEEDS DEVICE VALIDATION |

## 4. Gaze Validation

### Coordinate contract

Current design uses `GazePointSpace.CAMERA_RAW` for raw camera gaze and `SCREEN_NORMALIZED` for calibrated predictions. The personalized path therefore avoids applying the raw gain/invert transform twice.

Synthetic direction tests now explicitly cover:

- center → center;
- look left → X decreases;
- look right → X increases;
- look up → Y decreases;
- look down → Y increases;
- bilateral vertical disagreement lowers agreement.

### Action safety

The gaze policy now distinguishes cursor visibility from action eligibility. Bilateral gaze, valid pose, sufficient eye quality, and model quality are independently checked. Monocular gaze cannot be actionable.

### Head pose

MediaPipe facial transformation matrix output is enabled and the first valid 4×4 matrix is passed into the head-pose estimator. The estimator still has a landmark fallback for compatibility, with explicit validity and confidence.

### Remaining gaze validation

The following cannot be marked verified without real camera samples: absolute calibration accuracy, saccade/latency feel, glasses/lighting performance, camera rotation behavior, and target-device inference throughput.

## 5. Gesture Validation

The gesture engine retains temporal debounce, confidence hysteresis, pinch arbitration, swipe arbitration, hand-loss cleanup, and interruption reset behavior.

A remaining architectural concern is semantic event transport: `GestureEngine` and its bridge currently use buffered flows with `DROP_OLDEST`. Continuous cursor movement is correctly latest-wins, but semantic events need stronger delivery guarantees. A targeted transport redesign is still required before this can be marked fully verified.

## 6. Cursor Validation

The hand cursor palm-anchor hot path no longer allocates temporary collections for every frame. Gaze and hand screen targets are transported through latest-wins UI scheduling, which is appropriate for continuous position updates.

The cursor still requires physical validation for exact pixel alignment, overlay behavior, density/rotation, and action/cursor coincidence.

## 7. Calibration Validation

Calibration uses a deterministic 9-point target layout. Robust filtering, target-balanced validation, model-quality gating, and a worst-target error gate are implemented.

Both linear and quadratic models are valid runtime representations and the persistence layer validates the correct coefficient basis size for each.

Calibration still requires on-device execution with real eye landmarks to verify real-world accuracy across distance, lighting, glasses, orientation, and screen size.

## 8. Accessibility Validation

The service uses `canRetrieveWindowContent=true` and `flagRetrieveInteractiveWindows` for native-like cursor hit testing and limited metadata inspection. The service is also designed to keep action infrastructure alive if optional camera/overlay stages fail.

The strongest remaining risk is OEM-dependent `AccessibilityService.dispatchGesture()` behavior. This must be validated on Samsung/One UI and Android 17 hardware for tap, double tap, long press, drag, swipe, keyboard/IME, and interruption scenarios.

## 9. Privacy/Security Validation

The current documentation distinguishes transient camera/tracking data from persisted user settings and calibration state. It also documents the accessibility metadata capability and states that the metadata is processed locally and not transmitted.

Repository security controls now include:

- no tracked `.env` file;
- keystore/signing material ignored;
- CI high-signal credential scan;
- reduced Bluetooth identifier logging;
- no runtime `INTERNET` permission requested for the tracking pipeline.

This source-level review does not replace legal/store-policy review of the final Play Console Data Safety declaration.

## 10. Performance Validation

| Metric | Current implementation | Measured value in this audit |
|---|---|---|
| Camera FPS | Adaptive CameraX pipeline | NOT MEASURED ON PHYSICAL DEVICE |
| Hand inference FPS | One in-flight inference | NOT MEASURED ON PHYSICAL DEVICE |
| Face inference FPS | One in-flight inference | NOT MEASURED ON PHYSICAL DEVICE |
| Cursor update FPS | Latest-wins UI-frame application | NOT MEASURED ON PHYSICAL DEVICE |
| End-to-end latency | Telemetry hooks present | NOT MEASURED ON PHYSICAL DEVICE |
| CPU | No device profiler available in audit environment | NOT RUN |
| RAM | Bounded frame pools/flows, no physical soak | NOT RUN |
| Thermal | Thermal governor present | NOT RUN |
| Dropped frames | In-flight/backpressure counters present | NOT RUN |

No numeric CPU/RAM/temperature claim is made without a real device measurement.

## 11. Test Results

### PASS / source-verified

- gaze action policy structure;
- monocular non-actionability;
- bilateral X/Y agreement;
- fail-closed non-finite gaze predictions;
- hand geometric quality separation;
- cursor hot-path allocation removal;
- release signing configuration inspection;
- privacy/security source review;
- tracked `.env` removal;
- strict release-version validation logic;
- CI configuration includes lint, unit tests, instrumentation, debug build, release build, signature verification.

### FAIL / corrected during this pass

- original hardened lint run: 12 errors / 152 warnings;
- first API-level annotation attempt: Kotlin compilation error on delegated property annotation.

Both were engineering fixes, not suppressed failures.

### NOT RUN / requires CI or device execution

- latest full GitHub Actions run for the final commit;
- current release APK artifact from the final commit;
- connected Android instrumentation against the final commit;
- 15/30/60 minute thermal soak;
- Samsung/One UI gesture injection;
- Android 17 physical validation;
- real gaze accuracy/latency measurement;
- multi-resolution/rotation physical validation.

## 12. CI/CD Results

The workflow has been upgraded to distinguish:

`static checks → unit tests → executed instrumentation → debug build → signed release build → APK sanity/signature → version monotonicity`.

The final commit must have a green run through all of these stages before release publication.

## 13. Release APK Results

Release configuration uses R8/resource shrinking and release signing. The CI workflow verifies the APK with `apksigner` and inspects package/targetSdk/version information.

The repository currently has no published GitHub Release from which a previous APK `versionCode` can be compared. The CI check therefore establishes the first published baseline and enforces `candidate > previous published` on subsequent releases.

A final signed release APK for the current head is **not marked verified in this report until the corresponding final CI run completes successfully**.

## 14. Remaining Risks

1. Semantic gesture events still need guaranteed delivery semantics distinct from continuous cursor state.
2. Gaze inference throughput and true end-to-end latency still need measurement on the target hardware.
3. `CopyOnWriteArrayList` remains in the gaze-history hot path.
4. Android accessibility gesture injection remains OEM/platform dependent and requires physical testing.
5. Samsung/One UI and Android 17 validation have not been executed in this environment.
6. Long-session CPU/RAM/thermal behavior has not been measured on physical hardware.
7. Final release APK produced from the current head has not yet been independently verified by the final CI run available at report generation time.

## 15. Production Readiness Decision

**NOT READY**

Reason: source-level hardening is substantially complete, but the release cannot be considered fully production-ready until the final automated CI gates pass and the required physical-device matrix validates accessibility injection, gaze accuracy/latency, lifecycle recovery, rotation, thermal behavior, and long-session stability.

This is a factual release gate, not a quality ranking.

## Modification Changelog

- Added monotonic release version logic and published-release validation.
- Hardened FPS quantization against cap violations.
- Added explicit gaze direction regression tests.
- Added monocular/bilateral gaze action safety gates.
- Enabled MediaPipe facial transformation matrix output.
- Hardened non-finite gaze prediction behavior.
- Removed per-frame hand cursor collection allocation.
- Added geometric hand landmark quality independent from handedness score.
- Hardened blink invalid-sample/relearn behavior.
- Added linear calibration persistence support and round-trip coverage.
- Removed tracked `.env` and added secret scan.
- Updated accessibility/privacy disclosure and supporting documentation.
- Expanded CI with executed instrumentation testing and release sanity checks.
- Expanded manual QA checklist for device, gesture, gaze, lifecycle, thermal, permissions, calibration, and TalkBack testing.
