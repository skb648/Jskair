# AirControl — 19-Issue UX & Reliability Hardening Spec

Status legend per issue: ✅ verified-present / 🔧 hardened this round / 📋 needs physical-device validation / ⛔ deliberately no-change (documented).

Ground truth is the code at HEAD (`b5e3f38`) plus the uncommitted hardening below; commit messages are never trusted alone.

---

1. **Cursor show/hide idempotence** — show() on every gaze frame must not restart the 200 ms fade-in.
   - Fixed: explicit `CursorVisibilityStateMachine` (HIDDEN/SHOWING/VISIBLE/HIDING). Repeated show → no-op; show during fade-out → `CANCEL_AND_RESTORE` (no new fade-in); stale fade completions are dropped via a generation token. `CursorOverlay` rewired to the machine.
   - Tests: `CursorVisibilityStateMachineTest`.
   - Acceptance: cursor reaches alpha 1 while gaze frames keep calling show() at 30–60 Hz.

2. **Recalibration isolation** — a personalized model being replaced must not contaminate its own replacement's training data; old model stays active until the new one validates atomically.
   - Present: calibration collector consumes `faceTracker.gazeObservations`; fit only runs after 9 targets; new model is persisted via `settingsRepository.updatePersonalizedGazeCalibration(...)` only when the fit succeeds (old model untouched on failure).
   - Hardened: `FaceTracker` now computes the RAW iris-ratio gaze once, independently of the model branch, and `GazeObservation.rawX/rawY` ALWAYS carry raw ratios — model predictions only ever feed the cursor `GazePoint`, never a calibration observation.
   - Tests: `PersonalizedGazeCalibrationTest` (existing) + code-level data-flow separation.
   - Acceptance: recalibrating while an old model is installed must not bias the affine fallback or the new model.

3. **Eye cursor / blink / dwell independent of hand arming** — only pinch needs ARMED.
   - Verified: the gaze collector and `dispatchBlinkTap`/`dispatchDwellTap` never consult `engineState`; they need `gesturesEnabled && eyeTrackingEnabled && (cursorEnabled || blinkClickEnabled)`. Hand gestures gate only pinch/swipe/palm via the gesture engine.
   - Acceptance: eye-tracking-only session clicks with a visible face and no palm. 📋 device test.

4. **Blink aborts on face loss** — VALID→UNCERTAIN→LOST; only the LOST transition aborts an in-progress blink.
   - Present: 4-miss hysteresis before "lost".
   - Hardened: `BlinkDetector.abortInProgressBlink()` (metrics `abortedBlinkCount`) called only at the definitive LOST transition in the service; uncertain frames never disturb the detector. Re-acquisition can no longer complete an interrupted blink into a stale click.
   - Tests: `BlinkDetectorAbortTest`.

5. **Velocity-aware gaze smoothing + debug-only metrics** — One Euro already velocity-adaptive.
   - Hardened: `GazeSmoothingMetrics` (raw velocity, raw↔filtered displacement/lag, rest jitter, loss/reacquisition teleport displacement, saccade count). Allocation-free per sample; recorded only under `BuildConfig.DEBUG` in the gaze collector.
   - Tests: `GazeSmoothingMetricsTest`.

6. **Edge/corner calibration quality gates** — global + per-target mean/P95/max, per-axis errors; a broken corner must fail calibration.
   - Present: fitter rejects when fewer robust samples than required or when the worst per-target mean normalized error exceeds `WORST_TARGET_MAX_NORMALIZED_ERROR` ("screen region … error too high … recalibrate"); validation uses a held-out split; metrics include p95/max normalized + pixel.
   - 📋 Verify per-target P95/max/per-axis gates exist to the letter of the issue; numeric thresholds read during audit.

7. **Stale async hit-test results discarded** — request identity + generation; latest wins.
   - Hardened: `CursorHoverMonitor` — single-flight; the newest qualifying position replaces the single pending slot while a scan is running; an in-flight result is DISCARDED when a newer request arrived, and the newer position is resolved instead. Icon applies only when its sequence is still current.
   - Present: rate-limiting via `HoverResolvePolicy` (8 dp move threshold, 120 ms interval).

8. **Cursor context responsiveness without backlog** — throttled, single-flight, latest-position.
   - Present+hardened: `HoverResolvePolicy` + single-flight `CursorHoverMonitor` (never queues; bounded one-slot pending). Hit-test runs on `Dispatchers.Default`; icon hops to main only on change.

9. **Hit-testing never leaks/retains nodes; bounded traversal; ignore overlay/magnification windows.**
   - Present+hardened: `CursorHitTester` + pure `BoundedCursorTreeWalk` (MAX_VISITS=300, MAX_DEPTH=28, overlay windows skipped, deepest last-child-first, snapshot-only returns — no `AccessibilityNodeInfo` escapes).
   - Tests: `BoundedCursorTreeWalkTest` with pathological synthetic trees.

10. **Explain why actions are blocked** via status pill / hints on meaningful state changes (no per-frame toasts).
    - Hardened: `BlockReason` enum (stable ids) + `BlockReasonHub` (change-only, same-reason quiet gap 1.5 s). Dispatcher reports capability refusals, cross-modality refusals, calibration suppression. Service relays effective changes to the `StatusOverlay` hint line (3 s auto-clear, generation-guarded). Strings live in resources.
    - Tests: `BlockReasonHubTest`.

11. **Swipe safe-but-responsive** — displacement/velocity/axis-dominance/direction-consistency/confidence/neutral re-arm, adaptive to tracking quality.
    - Present: swipe intent requires open hand + displacement + velocity + axis dominance + direction consistency (gesture engine, extensive tests: `SwipeStressTest`, `GestureReliabilityTest`, `GestureEngineInvariants`). No code change this round (already hardened upstream).

12. **Four-finger semantics deterministic** — the repo made FOUR_FINGERS deliberately unreachable; user-visible config must be deterministic.
    - ⛔ No classifier change: FOUR_FINGERS is subsumed by OPEN_PALM (priority), pinned by `FourFingerAuditTest`; there is no `KEY_POSE_FOUR_FINGERS` default-map entry. The only reachable four-finger semantics is the user-recorded custom-gesture path (template matcher). Documented in `FourFinger-Audit.md`.

13. **True right-click semantics** — long-press ≠ right-click; never label a touch-hold as right-click; HID path isolated/experimental.
    - ⛔ No change: no RIGHT_CLICK action exists; LONG_PRESS is an honest 500 ms touch-hold; real right-click is deferred to the Bluetooth-HID POC (`NativeHidMouse`, experimental). Documented in `RightClick-Audit.md`.

14. **Unsupported actions capability-checked and honestly refused** — never silently substitute.
    - Fixed: `ActionCapabilityChecker` (screenshot/lock need API 28). Dispatcher refuses and reports `ACTION_UNSUPPORTED` instead of the old screenshot→notifications / lock→home fallbacks.
    - Tests: `ActionCapabilityCheckerTest`.

15. **Cross-modality ownership** — one physical intent → one action; blink+dwell+pinch must not triple-click.
    - Fixed: `InputOwnershipPolicy` (deterministic first-come 350 ms serialization window; same-modality repeats allowed; refused counter). Replaces the old two-slot hand/gaze guard in `ActionDispatcher` and is applied to hand taps, double-taps, blink taps and gaze/hand dwell taps.
    - Tests: `InputOwnershipPolicyTest`.

16. **Pinch/cursor coherence** — action always at the displayed logical cursor; no coordinate jumps.
    - Present (gesture-engine `CursorAnchorTest`, dispatcher pinned click coords + direct gaze mapping). No further change this round.

17. **Drag bounded lag vs cursor; no queued strokes; safe termination** — never a stuck touch-down.
    - Present (drag continuation tied to the single live cursor, engine-level invariants). No further change this round. 📋 device validation.

18. **One canonical logical cursor state per modality** — visible cursor & input coordinates never diverge.
    - Present (single smoothed cursor; gaze maps directly to pixels; pinned click coordinate at pinch). No further change this round.

19. **Lifecycle/OEM/rotation/camera-loss/recovery audit** — transient state reset; no stuck drag/click/blink/dwell/calibration/async results.
    - Present: watchdog + camera revive policy, keyguard handling, user-pause honored, crash-guard, hysteresis everywhere; `RuntimeHealthTest`, `CameraRevivePolicyTest`, `ThermalGovernorTest`. Hardened: state-machine/generation guards in cursor visibility & hover; blink abort on loss. 📋 OEM matrix remains a physical-device activity.
