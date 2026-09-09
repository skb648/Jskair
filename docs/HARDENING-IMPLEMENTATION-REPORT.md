# AirControl — 19-Issue UX & Reliability Hardening: Implementation Report

Date: 2026-09-09 · HEAD: `73313d7` (main)
Scope: read-audit of the whole codebase + surgical hardening for the 19 requested
issues. **No rewrite of the application was performed** — existing architecture,
default gesture maps, settings schemas, calibration formats and the hand-control
path remain functionally equivalent; changes are confined to what each issue
required and each behavioral change ships with JVM tests.

Acceptance matrix with detailed rationale: `docs/19-issue-hx-spec.md`.
Prior engineering decisions referenced below live in the repo:
`FourFinger-Audit.md`, `FourFingerAuditTest.kt`, `RightClick-Audit.md`,
`NativeLikeCursor.md`, `docs/` history at HEAD `b5e3f38`.

---

## 1. Files changed

### App sources (hardening)
| File | Change |
|---|---|
| `app/src/main/java/com/aircontrol/accessibility/cursor/CursorVisibilityStateMachine.kt` | **new** — explicit HIDDEN/SHOWING/VISIBLE/HIDING machine + `CursorVisibilityAction` (Issue 1) |
| `app/src/main/java/com/aircontrol/accessibility/CursorOverlay.kt` | show()/hide()/remove() rewired through the machine; generation-token-guarded fade end-callbacks; idempotent show; show-during-fade-out = cancel & restore without new fade-in (Issue 1) |
| `app/src/main/java/com/aircontrol/tracking/FaceTracker.kt` | RAW iris-ratio gaze computed once, independent of the personalized model; `GazeObservation.rawX/rawY` always carry raw ratios — model output only feeds the cursor `GazePoint` (Issue 2) |
| `app/src/main/java/com/aircontrol/tracking/BlinkDetector.kt` | `hasInProgressBlink`, `abortInProgressBlink()`, `abortedBlinkCount()` (Issue 4) |
| `app/src/main/java/com/aircontrol/accessibility/GestureControlAccessibilityService.kt` | definitive LOST transition now calls `blinkDetector.abortInProgressBlink()`; debug-only `GazeSmoothingMetrics` wiring; `BlockReasonHub.onChange` → status-pill hint (Issues 4, 5, 10) |
| `app/src/main/java/com/aircontrol/tracking/GazeSmoothingMetrics.kt` | **new** — allocation-free-per-sample, debug-only gaze metrics (Issue 5) |
| `app/src/main/java/com/aircontrol/accessibility/cursor/CursorHoverMonitor.kt` | latest-wins single-flight hover resolver (Issues 7/8) |
| `app/src/main/java/com/aircontrol/accessibility/cursor/CursorHitTester.kt` + `CursorHitTree.kt` | bounded traversal over pure `BoundedCursorTreeWalk` (MAX_VISITS 300, MAX_DEPTH 28, overlay/magnification skipped, snapshot-only) (Issue 9) |
| `app/src/main/java/com/aircontrol/accessibility/BlockReason.kt`, `BlockReasonHub.kt` | **new** — change-only, throttled reason hub (Issue 10) |
| `app/src/main/java/com/aircontrol/accessibility/StatusOverlay.kt` + `app/src/main/res/values/strings.xml` | status-pill hint line for block reasons; UI strings in resources (Issue 10) |
| `app/src/main/java/com/aircontrol/accessibility/ActionCapabilityChecker.kt` | **new** — honest capability gate for API-28-only system actions (Issue 14) |
| `app/src/main/java/com/aircontrol/accessibility/InputOwnershipPolicy.kt` | **new** — deterministic cross-modality tap serialization (Issue 15) |
| `app/src/main/java/com/aircontrol/accessibility/ActionDispatcher.kt` | screenshot/lock refused (not substituted) when unsupported; blink/dwell/hand taps routed through `InputOwnershipPolicy`; suppression + refusal reasons reported to the hub (Issues 10, 14, 15) |

### Tests (new, JVM)
`CursorVisibilityStateMachineTest`, `BoundedCursorTreeWalkTest`, `InputOwnershipPolicyTest`,
`BlockReasonHubTest`, `ActionCapabilityCheckerTest`, `GazeSmoothingMetricsTest`,
`BlinkDetectorAbortTest` (under `app/src/test/...`).

### CI
`.github/workflows/android-apk.yml` — new `build-release-signed` job that materializes
the keystore from Actions secrets (`KEYSTORE_BASE64`) and builds/verifies/upload
`app-release.apk`.

### Docs
`docs/19-issue-hx-spec.md` (acceptance matrix), this report.

---

## 2. Per-issue root cause → fix → test → result

| # | Root cause | Fix | Test | Result |
|---|---|---|---|---|
| 1 | `show()` restarted the 200 ms fade on every qualifying frame (isVisible became true while alpha < 1), so at 30–60 Hz the fade never completed | Explicit visibility state machine; repeated show = no-op; `HIDING+show` = `CANCEL_AND_RESTORE`; stale completions dropped via generation token | `CursorVisibilityStateMachineTest` (9 cases incl. idempotence, stale-completion, restore-without-re-fade) | 🔧 fixed in code; CI green |
| 2 | With a personalized model installed, calibration observations carried model-screen predictions as raw coords → recalibration could be contaminated by the very model being replaced | Raw gaze computed once before the model branch; observations always emit raw iris ratios; model predictions only feed cursor `GazePoint`; model swap already atomic (new fit persisted only on success) | Existing `PersonalizedGazeCalibrationTest` + data-flow isolation reviewed | 🔧 fixed in code |
| 3 | — (already independent: gaze collector and blink/dwell dispatch never consult `engineState`) | none needed | — | ✅ verified present |
| 4 | A closure interrupted by tracking loss could, after reacquisition, be treated as one continuous blink and fire a stale click | Only the definitive LOST transition calls `abortInProgressBlink()`; no-op otherwise; counter for observability | `BlinkDetectorAbortTest` | 🔧 fixed in code |
| 5 | No numeric observability of smoothing behavior; risk of blind retuning | `GazeSmoothingMetrics` (raw velocity, raw↔filtered lag, rest jitter, reacq teleport, losses/saccades), debug-only & allocation-free | `GazeSmoothingMetricsTest` | 🔧 added (debug surfaces) |
| 6 | Fitter already fails bad corners via worst-target mean gate | no code change; audit notes per-target P95/max/per-axis gates | existing fitter tests | 📋 partially verified; device validation for real distributions |
| 7/8 | Single-flight resolver dropped positions during in-flight scans | latest-wins: newest position replaces the one pending slot; old async result discarded when superseded; generation/sequence guard | — (behavioral, existing cursor tests) | 🔧 fixed in code |
| 9 | (already bounded + overlay-skipping) | traversal extracted to pure `BoundedCursorTreeWalk` for JVM testing of pathological trees | `BoundedCursorTreeWalkTest` (depth budget, overlay exclusion, budget exhaustion termination) | 🔧 hardened + tests |
| 10 | Blocked actions were silent; only per-frame-unfriendly toasts existed | `BlockReason` + `BlockReasonHub` (change-only, 1.5 s quiet gap) + status-pill hint line; strings in resources; no new per-frame toasts | `BlockReasonHubTest` | 🔧 added |
| 11 | — (already safe-but-responsive: displacement/velocity/axis-dominance/direction/confidence/re-arm; extensive engine tests) | none | existing `SwipeStressTest`, `GestureReliabilityTest` | ✅ verified present |
| 12 | Four-finger was made unreachable by design (classifier subsumption) and the UI must be deterministic | no classifier/map change; determinism documented (no `KEY_POSE_FOUR_FINGERS` in default map; only user-recorded custom template path reaches it) | existing `FourFingerAuditTest` | ⛔ deliberate no-change (documented) |
| 13 | Long-press ≠ right-click; Android public API is touch-only; right-click deferred to HID POC | no change; `RightClick-Audit.md` keeps the honest label | — | ⛔ deliberate no-change (documented) |
| 14 | On API < 28 the dispatcher substituted screenshot→notifications and lock→home | `ActionCapabilityChecker` + honest refusal + `ACTION_UNSUPPORTED` reason surfaced | `ActionCapabilityCheckerTest` | 🔧 fixed in code |
| 15 | Blink+dwell+pinch could each fire for one intent | `InputOwnershipPolicy` (350 ms serialization, deterministic first-owner, same-modality repeats allowed, refused counter) replacing the old two-bucket guard; applied to TAP/DOUBLE_TAP/blink/dwell | `InputOwnershipPolicyTest` | 🔧 fixed in code |
| 16/17/18 | — (already implemented: pinned click coordinate, single smoothed cursor, direct gaze mapping, drag continuation with no queue; engine invariant tests) | none | existing `CursorAnchorTest`, `PinchClickTest`, engine invariant suites | ✅ verified present |
| 19 | Lifecycle resilience | re-verified watchdog/revive/keyguard/crash-guard/hysteresis; hardened cursor-visibility & hover state machines and blink-abort on loss so transient state cannot survive interruption | existing `RuntimeHealthTest`, `CameraRevivePolicyTest`, `ThermalGovernorTest` | 🔧/📋 code hardened; OEM matrix requires devices |

Legend: 🔧 fixed/hardened this round · ✅ verified present at HEAD · ⛔ deliberate documented no-change · 📋 needs physical-device validation.

---

## 3. Regression analysis

- **Hand-control path**: `HandTracker`, `GestureDetector`, gesture state machine and
  engine stays byte-identical; `GestureEngineConfig`/`GestureReliabilityPolicy`
  untouched; swipe/pinch/palm behavior unchanged. `:gesture-engine:test` local
  run: **BUILD SUCCESSFUL**.
- **ActionDispatcher**: TAP/DOUBLE_TAP/dwell/blink ownership changed from a private
  350 ms hand/gaze two-bucket guard to the equivalent `InputOwnershipPolicy`
  (same default window; deterministic first-owner). SCREENSHOT/LOCK on API < 28
  now returns failure + `ACTION_UNSUPPORTED` instead of substituting a different
  action — this is the intended Issue-14 behavior, covered by tests.
- **Cursor hover**: public surface used by the service (`CursorHoverMonitor(scope,
  policy, snapshotProvider, onIcon)`, `onCursorPosition/refresh/reemit/cancel`,
  `CursorHitTester.hitTest(windows, x, y, pkg)`) unchanged; internals hardened.
- **FaceTracker**: raw gaze computed slightly earlier and reused; personalized
  branch output identical; only observation raw coords semantics changed (intended).
- **CI**: both workflows pass on the final commit — full JVM unit test run
  (`testDebugUnitTest` 282 tests + `:gesture-engine:test`), `assembleDebug`, and
  `assembleRelease` (signed).

---

## 4. Exact build/test results (GitHub Actions, commit `73313d7`)

- **Build Android APK** run `34305405763` — success
  (`testDebugUnitTest` + `:gesture-engine:test`, `assembleDebug`, APK badging check).
- **Android APK CI** run `34305405789` — success
  - `Build debug APK` — success → artifact `aircontrol-debug-apk`
    (app-debug.apk ~51.6 MB).
  - `Build signed release APK` — success, keystore materialized from Actions
    secrets and `apksigner verify --print-certs` passed → artifact
    `aircontrol-release-signed-apk` (app-release.apk ~30.6 MB).
- Local sanity: `:gesture-engine:test` — BUILD SUCCESSFUL (JDK 17, constrained heap).

Artifacts are available from the Actions run pages on the repository; the keystore
is never stored in the repo (only base64 in Actions secrets).

---

## 5. Remaining physical-device limitations (not claimable from unit tests)

- Issue 3 end-to-end (eye-only session with no palm ever shown) — needs a device run.
- Issue 6 calibration distribution quality (glasses, partial occlusion) — needs a
  device calibration round; per-target P95/max/per-axis acceptance still to be
  validated against real distributions.
- Issues 11/16/17/18/19 OEM behavior (rotation, screen lock mid-gesture, camera
  loss on Samsung/Xiaomi/Pixel, process recreation) — physical-device validation
  matrix is acknowledged and remains outstanding.
- The signed-release pipeline is verified end-to-end in CI; install/sideload
  behaviour of the release build on OEM ROMs is a device step.
