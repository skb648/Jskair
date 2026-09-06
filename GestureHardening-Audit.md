# Gesture Pipeline Hardening — Audit & Round 9 Record

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

## Verified non-issues (searched, not changed)
- Config/sensitivity swap mid-gesture preserves state (H-06 design).
- Engine serialized per frame from a single collector; no concurrent processFrame.
- `reset()` clears every transient field (verified line-by-line both classes).
- Double-fire within one window impossible (windows cleared on commit).
- Dormant IntentEngine not wired — documented as future work (its rollout must
  not change existing action semantics).
