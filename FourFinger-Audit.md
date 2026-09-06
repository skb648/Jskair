# Task 15 Audit — FOUR-FINGER POSE → EXISTING ACTION

**Verdict: STOP — NO IMPLEMENTATION. The FOUR_FINGERS pose is UNREACHABLE in
the current classifier. Mapping it to any action would be dead configuration:
the pose can never fire.**

Baseline: `7d9bae4` (CI #219 GREEN, 196 gesture-engine tests).

---

## 1. Action side — NOT the blocker

`GestureAction` (19 values) has eight actions unused by any default mapping:
BACK, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, SCREENSHOT, LOCK_SCREEN,
DOUBLE_TAP, LONG_PRESS. Several are benign and fully implemented (dispatched
today via user remapping, e.g. victory → BACK). So a "safe existing action"
EXISTS — the right-click blocker does not apply here.

The blocker is upstream: **recognition**.

## 2. Recognition — the FOUR_FINGERS branch is dead code

`StaticPoseClassifier.classifyRaw` (verbatim order):

```
priority 3: if (fingerState.totalExtendedCount >= 4) return Pose.OPEN_PALM
...
priority 7: if (index && middle && ring && pinky) return Pose.FOUR_FINGERS
```

`FingerExtensionState.totalExtendedCount` counts **thumb + 4 fingers**.
Any hand satisfying priority 7 (all four fingers extended) necessarily has
`totalExtendedCount >= 4` and has **already returned OPEN_PALM** at priority 3.
The subsumption is strict — for BOTH thumb states:

| Hand shape | totalExtendedCount | Classified as |
|---|---|---|
| 4 fingers + thumb tucked | 4 | OPEN_PALM |
| 4 fingers + thumb out | 5 | OPEN_PALM |

`Pose.FOUR_FINGERS` therefore has **zero producing paths**: the only emitter of
`GestureEvent.PoseTriggered` is `GestureEngine` (line 297) fed exclusively by
this classifier. Pinned deterministically by `FourFingerAuditTest` (4 tests):
finger-state unit proof (index/middle/ring/pinky extended, thumb not, count 4
→ OPEN_PALM), thumb-out variant (count 5 → OPEN_PALM), engine-level proof that
the four-finger hand **arms the engine** (it IS the arming pose) with zero
FOUR_FINGERS events, and a held-pose proof (OPEN_PALM is neutral → zero pose
actions, held or not).

Note: `DynamicGestureDetector`'s Fix-S1 comment mentions an historical
"OPEN_PALM → FOUR_FINGERS → OPEN_PALM" flicker. That predates the current
4-of-5 OPEN_PALM rule (which tolerates a curled pinky AND a tucked thumb);
under today's rule the flicker path cannot produce FOUR_FINGERS. The swipe
grace logic remains valid protection regardless and was NOT touched.

## 3. Why the "obvious fix" is banned (and wrong)

Making FOUR_FINGERS reachable requires carving "4 fingers + thumb tucked" out
of OPEN_PALM (e.g. changing priority 3 to require the thumb). That changes
EXISTING meanings — explicitly out of scope — and would be a regression risk,
not a fix:

- **OPEN_PALM is the ARMING pose** (`processDisarmed` requires it). A relaxed
  natural open palm very commonly reads thumb-not-extended; those hands would
  stop arming the engine entirely — the 4-of-5 tolerance exists precisely to
  prevent that (classifier comment: "tolerates a curled pinky"; the count
  including the thumb extends the same tolerance to a loose thumb).
- **OPEN_PALM is the palm-home trigger** (2.5s hold), the **swipe open-hand
  gate** (`swipeRequiresOpenHand`), and a **neutral re-arm pose** (Fix A-5).
  Redefining it invalidates the arming/palm-home/swipe audits.

This is not a false-positive bug (task spec §4): no wrong action can ever fire
from this shadowing — no action fires at all. No production change is
justified. (Contrast with Task-14's THREE_FINGERS: it was reachable —
3 fingers + curled pinky = count ≤ 3 escapes the OPEN_PALM gate.)

## 4. Smallest future path (requires explicit user decision + fresh audit)

If a four-finger-triggered action is ever wanted, the options are:

1. **Redefine OPEN_PALM** to require the thumb (5-of-5, or 4-of-5 counting
   fingers only) and let thumb-tucked-4-fingers mean FOUR_FINGERS. Cost:
   re-audit arming UX (relaxed palms stop arming — likely a false-negative
   disaster), palm-home, swipe gate, neutral re-arm. Not recommended.
2. **Keep OPEN_PALM ≥4-of-5 but require thumb-extended for OPEN_PALM only
   during ARMED** — splits the pose by state, adds complexity, still changes
   palm-home/swipe behavior mid-session. Not recommended.
3. **User-recorded custom gesture** — `CustomGesturePose.FOUR_FINGERS`,
   `pose_four_fingers` string, the `FINGERS_PER_POSE` recording hint, and the
   template matcher (`CustomGestureTriggered`, separate from pose priority)
   ALREADY work today. A user who wants a four-finger-ish action can record
   it as a custom gesture without touching the classifier. This is the
   existing honest path.

## 5. Sections 5–14 of the task spec (orientation, temporal, confidence, loss, numerics)

All are moot for FOUR_FINGERS-as-a-pose (it cannot fire, so it cannot
mis-fire) and unchanged for what the shape actually is (OPEN_PALM): the
OPEN_PALM pipeline is already covered by the hardened suites — 120ms
wall-clock debounce (bug #11-fixed estimator), ARMED gate, one-shot latch
irrelevant (OPEN_PALM is never actionable), low-confidence suppression,
tracking-loss reset, numeric fail-safes (INV7), and the ThreeFingerVolumeTest
battery that pins the neighboring THREE_FINGERS/VICTORY/OPEN_PALM
arbitration. Geometry notes: finger extension uses tip/PIP-to-wrist distance
ratios and the thumb uses an IP-joint angle — both invariant to in-plane
rotation; no orientation normalization is needed or added. Fixture math for
the new tests: extended worst ratio 1.397 > 1.12 (max threshold), curled worst
0.747 < 0.90 (min threshold), tucked thumb ≈26.6° < 118°, straight thumb 180°
> 158° — valid at every sensitivity.

## 6. Changes made (audit-evidence only)

- ADDED `gesture-engine/.../FourFingerAuditTest.kt` (4 tests) — pins the
  subsumption so the dead branch cannot silently start firing without a red
  test (if it ever turns green-worthy, the OPEN_PALM/arming interactions must
  be re-audited first).
- ADDED this document.
- NO production changes. NO mapping changes. NO threshold/priority changes.

**HARDWARE VALIDATION: NOT PERFORMED** — irrelevant to the verdict (the
finding is code-structural), but stated for honesty: no physical-device test
was done in this task.
