# Right-Click Audit — TWO-FINGER GESTURE → RIGHT CLICK (Phase 2 candidate)

**Verdict: RIGHT CLICK ACTION SEMANTICS: NOT CURRENTLY AVAILABLE.**
**No implementation performed (by design). Recognition already exists (VICTORY).**

## A. Action-layer audit (the blocking finding)

The complete production path was inspected end-to-end:

```
GestureEngine (recognition) → GestureEvent → GestureControlAccessibilityService
  → ActionDispatcher → GestureAction → Android accessibility input
```

`GestureAction` (ActionDispatcher.kt) supports exactly:
`NONE, SCROLL_UP/DOWN/LEFT/RIGHT, BACK, HOME, RECENTS, NOTIFICATIONS,
QUICK_SETTINGS, VOLUME_UP/DOWN, MEDIA_PLAY_PAUSE, SCREENSHOT, LOCK_SCREEN,
TAP, DOUBLE_TAP, LONG_PRESS, DRAG`.

There is **no RIGHT_CLICK action**, and none can be honestly built today:

1. **The public accessibility API is touch-only.**
   `AccessibilityService.dispatchGesture` (the app's only input-injection
   mechanism, used by `dispatchTap`/`dispatchLongPress`/`dispatchDrag`)
   injects *touch* strokes. Android exposes **no public accessibility API to
   inject a mouse right-click** (BTN_RIGHT).
2. **`GestureAction.LONG_PRESS` is a synthesized 500 ms touch hold**
   (`dispatchLongPress`), i.e. long-press semantics — it often *opens the
   same context menus* a right-click would, but it is not a right-click.
   Mapping two-finger → LONG_PRESS and calling it right-click would be a fake
   mapping, which the spec (§1/§17) forbids unless the product spec defines
   it — it does not.
3. **`AccessibilityNodeInfo.performAction(ACTION_LONG_CLICK)`** exists on the
   node layer, but (a) it is still long-click semantics, (b) it requires a
   node-under-cursor lookup layer for actions (the hover resolver is
   read-only and intentionally does not perform actions), and (c) it would
   change ActionDispatcher semantics — explicitly out of scope.
4. Grep-verified: zero occurrences of `performAction`, `ACTION_LONG_CLICK`,
   `rightClick`, or `contextMenu` anywhere in `app/src/main`.

**The only TRUE right-click path in the Jskair architecture is the Bluetooth
HID POC's future Phase 2** (HID button report with BTN_RIGHT on a receiver
device) — see `NativeHidMouse-POC.md`, explicitly out of scope for this task.

## B. Two-finger recognition capability (already production-ready)

The ✌️ pose already exists end-to-end and is fully hardened:

- Engine: `Pose.VICTORY` = index + middle extended, ring + pinky curled
  (`StaticPoseClassifier.classifyRaw` step 5), behind the shared finger-
  extension detector (hand-size-normalized ratios), 120 ms wall-clock pose
  debounce, engine-armed requirement, `lastExecutedPose` one-shot lock with
  neutral re-arm (Bug #3 fix), cooldown, low-confidence execution suppression
  (round 10), tracking-loss reset.
- Mapping: `PoseTriggered(VICTORY)` → user-configurable `GestureAction` via
  the gesture map (`CustomGesturePose.VICTORY` is also a custom-gesture
  trigger option).
- Notably: **a user who wants context-menu-like behavior today can already
  map VICTORY → LONG_PRESS in settings** — honestly labelled as long press.

Per the spec's "recognition-only work is not unnecessarily implemented"
criterion, no recognition changes were made either.

## C. Smallest safe future architecture (proposal, NOT implemented)

1. **Real right-click (recommended):** HID POC Phase 2 — extend the existing
   movement-only HID report with the button byte (BTN_RIGHT) on the receiver
   device. This is a *true* right-click because the host OS interprets an
   actual mouse device. Requires the Phase-1 hardware test to pass first.
2. **Same-device context action (if ever wanted):** an isolated, honestly
   named `GestureAction.NODE_LONG_CLICK` built on node
   `performAction(ACTION_LONG_CLICK)` through a new opt-in action-capable node
   lookup — a separate, explicitly scoped task (it touches action semantics
   and needs its own safety audit). It must be labelled "long click /
   context action", never "right click".

## D. Scope compliance

- No production code changed. No tests changed. HID POC untouched.
- Files changed in this commit: this document only.
