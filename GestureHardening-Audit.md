# Gesture Pipeline Hardening — Audit & Round 9 Record

> **Phase 2 audit (PINCH → LEFT CLICK):** see the "Phase 2" section at the
> bottom — the pinch→click path already existed as the hardened production
> path; Phase 2 verified it against every spec requirement and added the
> missing click-specific adversarial/invariant battery (no production changes
> required).

Audit-first document per the production-hardening spec. No proprietary system
was reverse-engineered; principles used are the publicly observable ones
(explicit intent, arming, temporal validation, hysteresis, arbitration,
cancellation, recovery, cooldown, neutral re-arm, one motion → one action).

## A. Current gesture list (production path)

| Gesture | Event | Effect |
|---|---|---|
| Swipe L/R/U/D | `Swipe` | mapped action (scroll/nav…) |
| Pinch click/drag | `Pinch(START/MOVE/END)` | tap at anchor / drag / release |
| Static poses | `PoseTriggered(VICTORY, THUMB_UP, THUMB_DOWN, THREE_FINGERS, FOUR_FINGERS)` | mapped actions |
| Palm→Home | `PalmHome` | Home |
| Custom shape | `CustomGestureTriggered` | user-mapped action |
| Arming lifecycle | `Armed`/`Disarmed`/`CursorMoved` | UI/cursor |

Dormant parallel layer (module-only, NOT wired to actions):
`IntentEngine`/`SafetyPolicy`/`GestureReliability` — intent classification with
confidence gates; kept for the future arbitration rollout, unused in production
(deliberate: wiring it now would change action semantics).

## B. Recognition conditions
Open-palm arming (pose debounce ~120 ms wall-clock + 100 ms hold) → ARMED.
Pose priority: FIST > PINCH > OPEN_PALM > POINTING > VICTORY > THREE > FOUR >
THUMB_* (thumb requires near-still hand + clear-extension margin). Pinch FSM:
IDLE→HOVER(<enter×1.9)→PINCH_START(<enter, 80 ms)→HOLD→RELEASE(>exit=enter×1.45,
80 ms). Swipe: sliding window (350 ms, stretch-adaptive), 7 layered gates.

## C. Thresholds (GestureEngineConfig)
pinch enter 0.22 (band 0.14–0.32, ease 0.85–1.15, calibration ×1.15) / release
×1.45 / hover ×1.9; swipe displacement 0.08 (0.06–0.09), velocity 1.2 (0.9–1.8),
axis dominance 2.0, vertical |dY|≥2|dX|, consistency ≥0.7, vertical reversals
≤2, moving steps ≥3, window 350 ms, cooldown 220 ms; neutral re-arm 250 ms at
<0.006/frame; thumb hold 600 ms @ ≤0.35 u/s; palm-home 2500 ms, hand ≥0.26,
cursor travel ≤0.05; arming 100 ms; engine cooldown 100 ms; auto-disarm 10 s;
fist-disarm 1 s; low-confidence: <0.7 for 3 frames.

## D. Debounce/cooldown today
Pose debounce (adaptive frames); swipe cooldown + neutral re-arm latch;
lastExecutedPose rapid-fire lock (cleared by neutral pose); pinch 80 ms entry
debounce + 80 ms post-end cooldown + release-required semantics; swipe
suppression 60 ms after pinch END; custom-gesture id dedupe.

## E. Gesture conflicts (deterministic resolution today)
swipe vs cursor (open-palm pose gate + grace 2 frames); swipe vs pinch (pose
gate default-on + 60 ms suppression); pinch-pose vs click (ONE shared
threshold); palm-home vs swipe (stillness vs motion); thumb vs fist-disarm
(velocity gate + hold time); custom-template ambiguity (runner-up ratio reject).

## F. Repeated-trigger behavior
Covered by: swipe cooldown + neutral re-arm; pinch release-required +
cooldown; pose rapid-fire lock; palmHomeFired latch; template id dedupe.
**GAP FOUND (Bug 1):** a 1-frame tracking dropout right after a swipe cleared
the neutral re-arm latch — the hand's return sweep (>220 ms later, past
cooldown) could fire a PHANTOM second swipe. Spec §12 says hand loss must
require *fresh* evidence, not cancel the recovery requirement.

## G. Hand-loss behavior
Windows/buffers cleared; pose cleared; pinch END emitted (drag cancelled);
auto-disarm after 10 s; swipe recovery previously via (buggy) latch reset.

## H. Tracking-confidence handling
`confidence < 0.7` for ≥3 frames → low-confidence mode: longer pose debounce
(7 frames), swipe emission blocked, custom templates blocked, cursor smoother
hint. **GAP FOUND (Bug 2):** the pinch FSM ignored low-confidence mode — a
click/drag could commit from blurry frames, violating "prefer CANCEL over
GUESS".

## I. Swipe implementation
Layered: pose gate → neutral re-arm gate → window (adaptive) → sample count →
displacement → axis dominance → arc-robust signed-throw direction → vertical
angle filter → velocity → vertical monotonicity → moving steps → directional
consistency → cooldown. Already trajectory-based (not just `finalX-initialX`).

## Identified risks → this round's fixes

| # | Risk | Fix |
|---|---|---|
| 1 | Phantom repeat swipe after tracking dropout (F/G) | Neutral re-arm latch now SURVIVES hand loss; only `reset()` clears it; stillness must re-accumulate after reappearance |
| 2 | Low-confidence pinch clicks (H) | Pinch FSM entry transitions (IDLE→HOVER, HOVER→PINCH_START) gated on `!lowConfidence`; ongoing HOLD/MOVE/END unaffected so drags never dangle |
| 3 | Swipe could fire mid-pinch when the open-palm gate is user-disabled | Explicit arbitration: swipe emission suppressed while a pinch is active (`wasPinching || currentPinchPhase != null`) |
| 4 | No rejection telemetry / confidence score (spec §14/§18) | `SwipeResult` gains normalized `confidence` + `reason` codes; engine exposes an optional (null-by-default) `onSwipeDecision` debug hook — committed AND rejected-with-evidence, no production log spam |

## Round 10 (this pass) — adversarial loop findings

| # | Bug | Root cause | Fix + regression test |
|---|---|---|---|
| 5 | Low-confidence mode had ENTRY hysteresis (3 bad frames) but NO EXIT hysteresis — a single good frame reopened pinch/pose entry, so confidence flickering around 0.7 could click | counter reset on any good frame | Symmetric exit: 3 consecutive good frames to exit; undetected frames hold state (`GestureEngine`) — test: `confidence flicker around the threshold never clicks` |
| 6 | `PoseTriggered` was never gated on low confidence — 7 stable but blurry frames could misclassify a pose and fire a destructive action (volume) | emission gate covered swipes/templates only, not poses | Pose emission gated `!lowConfidence` AND pose EXECUTION suppressed in the SM (via the existing `suppressPoseExecution` path, now applied to all poses) so the one-shot `lastExecutedPose` latch is not silently consumed while muted — tests: `low confidence victory pose is muted until tracking recovers` + `low confidence tracking never starts a pinch click` (recovery part) |
| 7 | SM applied `suppressPoseExecution` to thumb poses only (design predated the low-confidence use of the flag) | `thumbPoseSuppressed` wrapper | SM suppression now applies to every actionable pose; thumb semantics unchanged (the engine already folds thumb-specific conditions into the flag) |
| 8 | **False positive (found by adversarial test, reproduced twice in CI):** a TWO-moving-step motion could commit as a swipe whenever the 350ms window held <8 samples at full frame rate (right after arming / while the window slides) — e.g. an out-and-half-back wiggle scrolled | `requiredSteps` had a `window.size >= 8 → 3, else 2` size heuristic that relaxed to 2 steps for small windows even at normal frame rate; scan mode was already covered by the interval branch | Size heuristic removed: normal frame rate always requires 3 moving steps; scan mode (interval ≥120ms) still requires 1 — regression tests: `direction reversal is not a swipe` + `outward half alone does not fire` |

Adversarial suite added (`GestureAdversarialTest`, 10 deterministic scenarios:
seeded jitter, slow drift, single-frame teleport, out-and-back reversal, 3
identical swipes → exactly 3 events, displacement boundary pair (binary-exact
0.0625/0.25 anchors per spec §18), confidence flicker, interrupted-before-
commit motion (no stitching across dropout), pinch hover-boundary flutter,
low-confidence pose mute + recovery). Three first-draft test premises were
themselves wrong (documented): a 4-frame half-swipe IS a legitimate commit;
alternating single bad frames never enter low-confidence mode; 4 fresh
post-reappearance frames are a valid NEW swipe — tests were corrected to probe
the actual invariants (anti-stitching, exit hysteresis).

## Round 11 (final stress audit) — independent re-audit findings

Baseline 18e1139 treated as a candidate release. Two NEW bug classes found
(both §11/§12 numeric/lifecycle safety, both fail-safe fixed):

| # | Bug | Root cause | Fix + regression test |
|---|---|---|---|
| 9 | **Degenerate landmarks could CLICK:** collapsed tracker output (all 21 landmarks identical, hand size ≤ ε, or NaN) made `thumbIndexDistance = 0`, and 0 < every pinch threshold → HOVER → PINCH_START → CLICK committed from garbage geometry | the FSM converted a division guard failure into a "perfect pinch" (`else 0f`) instead of refusing | Degenerate hand reads as maximally-open (no entry; existing HOVER-exit branch demotes to IDLE); a collapse during HOLD now also cleanly ENDS the press — test: `INV7 degenerate collapsed landmarks never click` |
| 10 | **Unconfirmed pinch candidate survived tracking loss:** PINCH_START/HOVER were only reset on loss when a HOLD was active — a hand lost mid-candidate and reappearing with fingers together resumed the stale candidate (80ms debounce satisfied across the gap → START without fresh validation) | the `!isDetected` branch only handled the `wasPinching` case | Loss now cancels HOVER/PINCH_START back to IDLE; the returning hand re-validates from scratch — test: `INV2 pinch candidate abandoned by tracking loss never clicks` (timestamp-gated: START must be ≥120ms after reappearance; a count-only assertion would pass both pre- and post-fix, §13) |

New suites: `GestureInvariantsTest` (INV1–INV10 incl. §4 confidence sequences
GGBGBG / BBBGGG / BGBGBG / gradual / sudden) and `SwipeStressTest` (§5
trajectories: L-shape, circular, high-velocity 2-step, low-velocity long,
growing wiggle, single-commit; §6 frame-rate matrix 30/20/15/12/11/10 fps ×
valid+too-slow, irregular intervals; §7 cooldown→re-arm timing, mid-motion
frame gap). Test-quality self-audit (§13) caught and fixed one flawed premise
in my own first draft (an L-shape leg at 1.25 u/s is a legitimately valid
swipe — the fixture was slowed so both legs are individually sub-gate).

No thresholds were changed in this round (§14).

Test-quality audit (§13) — four of this round's own first-draft premises were
wrong and FIXED AS TESTS (production untouched): (1) L-shape fixture skipped
the corner point, creating a 0.0424/step diagonal (1.06 u/s) — legitimately
above the velocity gate (verified by a faithful Python port of the detector);
(2) INV9 fed 18 open-palm frames after reset(), which legitimately RE-ARMS
the engine — redesigned to re-arm deliberately and swipe the opposite
direction (catches both stale-block and phantom-commit); (3) INV10 combined
teleport + same-direction steps into what is a legitimate 3-step trajectory —
scenarios split; (4) the "sudden degradation" sequence degraded AFTER pinch
formation — an already-formed candidate completing its 80ms confirm on
frame-4 is by-design (entry gating, not completion cancelling); pinned
explicitly as `INV3 degradation after formation does not cancel a real pinch`.

## Verified non-issues (searched, not changed)

- Config/sensitivity swap mid-gesture preserves state (H-06 design).
- Engine serialized per frame from a single collector; no concurrent processFrame.
- `reset()` clears every transient field (verified line-by-line both classes).
- Double-fire within one window impossible (windows cleared on commit).
- Dormant IntentEngine not wired — documented as future work (its rollout must
  not change existing action semantics).

## Phase 2 — PINCH → LEFT CLICK audit & hardening

**Existing path (audited, reused — NOT rewritten):**
`Pinch FSM (GestureEngine.processPinch)` → `Pinch(START/MOVE/END)` event →
GCAService (pins the click at the smoothed cursor dot / gaze position) →
`ActionDispatcher.dispatch` → `GestureAction.TAP` → `dispatchTap` (accessibility
tap at the cursor pixel). One START per pinch = one left click; MOVE = drag
continuation; END = release.

**Spec-vs-code verification (all already in place from rounds 9–11):**

| Requirement | Status |
|---|---|
| Intent model OPEN→CANDIDATE→VALIDATION→COMMIT→RELEASE_REQUIRED→RECOVERY→NEUTRAL | ✓ IDLE→HOVER→PINCH_START(80ms)→HOLD(START=click)→PINCH_RELEASE(80ms)+80ms cooldown→IDLE |
| Entry needs valid geometry | ✓ degenerate/NaN guard (bug #9) |
| Entry needs good tracking | ✓ low-confidence entry gate (round 9) |
| Enter/exit hysteresis | ✓ enter 0.2332 / exit 0.3381 / hover 0.4430 (s=70); enter≠exit everywhere |
| Temporal validation | ✓ 80ms candidate debounce; NO one-frame commits |
| One pinch = one click | ✓ START once per HOLD; re-click requires release + fresh pinch (R5) |
| Tracking loss cancels candidates | ✓ bug #10 fix; HOLD loss emits END (drag release, no dangling press) |
| Low-confidence = entry gating, not completion cancellation | ✓ documented boundary (INV3 boundary test) |
| handSize 0 / NaN / ∞ / collapsed | ✓ INV7 suite |
| Swipe/drag arbitration | ✓ swipe suppressed while pinch active (round 9) |
| Click uses existing cursor coordinates | ✓ pinned smoothed dot / gaze (service-side, untouched) |
| Duplicate-event prevention | ✓ FSM + engine event dedupe semantics |

**Phase 2 additions:** `PinchClickTest` — the click-specific adversarial cases
not previously pinned (barely-valid/barely-invalid entry, enter/exit threshold
oscillation → exactly one click and no repeats, never-stabilizing jitter above
enter, long-hold release, rapid repeated pinch across the 80ms cooldown
boundary, release-threshold hold/release, extreme hand scales 0.02/3.0,
swipe-then-pinch sequencing, cursor-only movement, reset-with-stale-HOLD).
No thresholds changed (§16); no production code changed in Phase 2.

---

## Phase 2 — Task 14: THREE-FINGERS → VOLUME_UP (audit 2026-09-06)

**§1 Action layer trace.** `GestureAction.VOLUME_UP` is a real public-API
action: `ActionDispatcher.pressVolume(true)` → `AudioManager.adjustStreamVolume(
STREAM_USE_DEFAULT_STREAM_TYPE, ADJUST_RAISE, FLAG_SHOW_UI)`. Recognition
already exists and is hardened: `StaticPoseClassifier` priority 6 —
index+middle+ring extended, pinky curled (THUMB state irrelevant at this
priority; thumb-out + 3 fingers = 4 extended digits → OPEN_PALM by priority 3,
the intended conservative arbitration). Behind it: 120ms wall-clock pose
debounce (3 frames @40ms), ARMED gate, EXECUTING→COOLDOWN(100ms), one-shot
`lastExecutedPose` latch cleared only by neutral (NONE/POINTING/OPEN_PALM),
low-confidence execution suppression + raised debounce (round 10), tracking
loss clears classifier history (fresh validation on return). The dispatcher
resolves the key generically (`poseKey()` else-branch → `pose_three_fingers`);
missing key fails safe to `GestureAction.NONE`. VICTORY dispatches through the
identical path today.

**Gap found (the only production change needed).**
`GestureMapConfig.defaultEntries()` had no `pose_three_fingers` entry → the
pose was invisible/unmappable in the gesture map UI and `migrate()` (which
rebuilds from defaults) would strip it from any config that carried it.
Fixed: entry `("pose_three_fingers", "Three Fingers", VOLUME_UP)` +
`CURRENT_SCHEMA_VERSION` 4→5 (migrate adds the entry to existing installs,
preserving user customizations — pinned by a v4→v5 migration test). Same
divergence existed in `ActionDispatcher.buildDefaultMap()` (the fallback map
used before the first settings emission and the base for the collector
overlay): added `KEY_POSE_THREE_FINGERS → VOLUME_UP` — data-only, dispatch
logic untouched. `HandPoseIcon`: added the `pose_three_fingers` branch
(palm + three spread fingers + curled pinky). `strings.xml`
`pose_three_fingers` ("Three Fingers") and `CustomGesturePose.THREE_FINGERS`
display name already existed.

**No changes** to: GestureEngine, GestureStateMachine, StaticPoseClassifier,
thresholds, ActionDispatcher dispatch semantics, VICTORY/PINCH/swipe behavior.

**Adversarial battery:** `ThreeFingerVolumeTest` (12 tests) — clean pose =
exactly 1 event, 30-frame hold never repeats; one-frame pose between palms =
0; three→neutral→three = 2 (OPEN_PALM clears the one-shot latch);
victory↔three_fingers transitions = each pose fires once; open→three = 1;
thumb-out+3-fingers reads OPEN_PALM (never fires — priority pin); pinky
oscillation three↔four = 0; low-confidence 0.5 muted then recovery fires once
(enter 3 bad / exit 3 good / debounce 7 verified); tracking loss mid-candidate
→ fresh validation on return (event timestamp ≥ 3rd return frame); NaN +
collapsed landmarks = 0 (fail safe); extreme scales 0.02/3.0 fire through
normalization; pose during cursor drift fires exactly once (velocity gate is
thumb-only — documented existing semantics). Fixture math self-audited:
extended-finger tip/PIP-to-wrist worst ratio 1.387 > max threshold 1.12;
curled worst ratio 0.747 < min threshold 0.90; tucked-thumb IP angle ≈27° <
118° min; straight-thumb 180° > 158° max — valid at every sensitivity.

**Bug #11 found by this battery (fixed + pinned).** Spec §6 (tracking
loss → reappearance = fresh validation): the frame-interval EMA that drives
`effectiveDebounceFrames` was fed the post-dropout GAP as if it were a frame
interval (updateMeasuredFrameInterval only ran on detected frames, but the
first detected delta after a gap IS the gap). A 240ms dropout inflated the
estimate 40→90ms and dropped the pose debounce to `ceil(120/90)` = 2 frames —
the returning pose committed at 40ms. A multi-second dropout (still inside the
10s auto-disarm window) collapsed it to ONE frame: a single misread
reacquisition frame could fire a pose action. Fix: an undetected frame zeroes
`prevFrameTimestampMs`, so the meaningless post-gap delta is skipped and the
estimator keeps its pre-dropout value. Pinned by the tracking-loss test
(timestamp ≥ firstReturn+80) and a new long-dropout (3s) test. Thresholds
untouched; the debounce formula is unchanged — only the estimator input was
wrong. OBSERVATION (not changed, out of Task-14 scope): the swipe detector's
own interval EMA has the same post-gap pattern, but its neutral-re-arm latch,
displacement+velocity+dominance+consistency gates, and the green
"interrupted motion does not resume after reappearance" pin cover the
reappearance path; flagged for a future swipe-focused audit if ever needed.

**Hardware validation NOT performed** — code-level proof only (CI-green unit
tests). Real-camera three-finger → volume behavior must be verified on device.
